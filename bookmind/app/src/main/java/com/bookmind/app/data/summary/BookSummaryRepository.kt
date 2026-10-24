package com.bookmind.app.data.summary

import android.content.Context
import androidx.core.content.edit
import com.bookmind.app.ai.GeminiConfig
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.ProcessingStatus
import com.bookmind.app.data.files.BookFileStore
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.di.GeminiModule
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.AppJson
import com.bookmind.app.work.WorkScheduler
import com.bookmind.core.ai.AiException
import com.bookmind.core.ai.gemini.TextModel
import com.bookmind.core.ai.summary.BookSummarizer
import com.bookmind.core.ai.summary.BookSummary
import com.bookmind.core.ai.summary.ChapterText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Book summaries made with Gemini: chapter summaries (about 10 % of each chapter) and a 2,000–4,000-word summary of
 * the whole book (see ai-config.properties). Results are saved on the phone as JSON, named after the SHA-256 of the
 * book text, so the same book is never sent to the API twice, even after it was deleted and added again. Progress
 * is saved after every request; an interrupted run continues with the next unfinished part.
 */
@Singleton
class BookSummaryRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val db: BookMindDatabase,
    private val files: BookFileStore,
    private val settings: SettingsRepository,
    private val config: GeminiConfig,
    @Named(GeminiModule.BACKGROUND) private val model: TextModel,
    private val scheduler: WorkScheduler,
    private val clock: AppClock,
) {
    enum class Phase { NOT_STARTED, QUEUED, RUNNING, MERGING, WAITING_FOR_LIMIT, FAILED, DONE }

    @Serializable
    data class Status(
        val phase: Phase = Phase.NOT_STARTED,
        val done: Int = 0,
        val total: Int = 0,
        val contentHash: String? = null,
        /** When a rate limit or daily quota is expected to allow the next attempt. */
        val retryAt: Long? = null,
        val error: String? = null,
    )

    /** Why summaries cannot be made right now, if they cannot. */
    enum class Unavailable { NO_API_KEY, AI_OFF }

    private val dir = File(context.filesDir, "book-summaries").apply { mkdirs() }
    private val statusPrefs = context.getSharedPreferences("book_summary_status", Context.MODE_PRIVATE)
    private val statuses = MutableStateFlow<Map<String, Status>>(emptyMap())

    val modelName: String get() = config.model
    val settingsInfo get() = config.summary

    suspend fun unavailable(): Unavailable? = when {
        !config.configured -> Unavailable.NO_API_KEY
        settings.current().aiMode != AiMode.GEMINI -> Unavailable.AI_OFF
        else -> null
    }

    fun status(bookId: String): Status = statuses.value[bookId]
        ?: statusPrefs.getString(bookId, null)?.let { runCatching { AppJson.decodeFromString(Status.serializer(), it) }.getOrNull() }
        ?: Status()

    fun observeStatus(bookId: String): Flow<Status> = statuses.map { it[bookId] ?: status(bookId) }

    private fun setStatus(bookId: String, status: Status) {
        statusPrefs.edit { putString(bookId, AppJson.encodeToString(Status.serializer(), status)) }
        statuses.value = statuses.value + (bookId to status)
    }

    /** The finished (or partial) summary of the book, if one exists for its current text. */
    suspend fun summary(bookId: String): BookSummary? = withContext(Dispatchers.IO) {
        val hash = status(bookId).contentHash ?: chapters(bookId).takeIf { it.isNotEmpty() }?.let { summarizer().contentHash(it) }
        hash?.let(::read)
    }

    /** Starts the summary in the background (when AI is available); [force] throws away an earlier result. */
    suspend fun request(bookId: String, force: Boolean = false) {
        if (unavailable() != null) return
        if (force) {
            status(bookId).contentHash?.let { file(it).delete() }
            setStatus(bookId, Status(Phase.QUEUED))
            scheduler.summarizeBook(bookId, replace = true)
            return
        }
        if (status(bookId).phase == Phase.DONE && summary(bookId)?.complete == true) return
        setStatus(bookId, status(bookId).copy(phase = Phase.QUEUED, error = null))
        scheduler.summarizeBook(bookId)
    }

    /** Called when a book has been processed: new books are summarised automatically. */
    suspend fun autoStart(bookId: String) {
        val book = db.books().get(bookId) ?: return
        if (book.processingStatus != ProcessingStatus.READY && book.processingStatus != ProcessingStatus.PARTIAL) return
        if (status(bookId).phase == Phase.NOT_STARTED || status(bookId).phase == Phase.FAILED) request(bookId)
    }

    sealed interface Outcome {
        data object Done : Outcome
        data object Skipped : Outcome
        data class RetryLater(val at: Long) : Outcome
        data class Failed(val error: AiException, val retryable: Boolean) : Outcome
    }

    /** Does the work (from [com.bookmind.app.work.BookSummaryWorker]); [progress] reports finished requests. */
    suspend fun run(bookId: String, progress: suspend (Status) -> Unit = {}): Outcome {
        if (unavailable() != null) return Outcome.Skipped
        val book = db.books().get(bookId)?.takeIf { it.deletedAt == null } ?: return Outcome.Skipped
        val chapters = chapters(bookId)
        if (chapters.isEmpty()) return Outcome.Skipped
        val summarizer = summarizer()
        val hash = summarizer.contentHash(chapters)
        val start = summarizer.start(book.title, book.author, AppLanguage.tag, chapters, read(hash))
        if (start.complete) {
            setStatus(bookId, Status(Phase.DONE, start.chunkCount, start.chunkCount, hash))
            return Outcome.Done
        }
        var current = Status(Phase.RUNNING, start.chunksDone.size, start.chunkCount, hash)
        setStatus(bookId, current)
        return try {
            summarizer.run(
                start, chapters,
                save = { s -> withContext(Dispatchers.IO) { write(s) } },
                progress = { p ->
                    current = current.copy(phase = if (p.merging) Phase.MERGING else Phase.RUNNING, done = p.chunksDone, total = p.chunkCount, retryAt = null, error = null)
                    setStatus(bookId, current)
                },
            )
            setStatus(bookId, Status(Phase.DONE, start.chunkCount, start.chunkCount, hash))
            progress(status(bookId))
            Outcome.Done
        } catch (e: CancellationException) {
            setStatus(bookId, current.copy(phase = Phase.QUEUED))
            throw e
        } catch (e: AiException) {
            when (e) {
                is AiException.QuotaExceeded -> {
                    val at = e.resetAt ?: (clock.now() + 15 * 60_000L)
                    setStatus(bookId, current.copy(phase = Phase.WAITING_FOR_LIMIT, retryAt = at, error = null))
                    Outcome.RetryLater(at)
                }
                else -> {
                    val retryable = e.retryable || e is AiException.InvalidOutput
                    setStatus(bookId, current.copy(phase = Phase.FAILED, error = AppLanguage.text.aiError(e)))
                    Outcome.Failed(e, retryable)
                }
            }
        }
    }

    /** Marks a run that will be retried by WorkManager as queued again. */
    fun markQueued(bookId: String) = setStatus(bookId, status(bookId).copy(phase = Phase.QUEUED))

    /** The book was deleted: forget its status. The summary file stays so the same book is not summarised again. */
    fun forget(bookId: String) {
        statusPrefs.edit { remove(bookId) }
        statuses.value = statuses.value - bookId
    }

    /** "Delete all my data": summaries go too. */
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        statusPrefs.edit { clear() }
        statuses.value = emptyMap()
    }

    private fun summarizer() = BookSummarizer(model, config.summary, clock::now)

    private suspend fun chapters(bookId: String): List<ChapterText> = withContext(Dispatchers.IO) {
        db.chapters().forBook(bookId).mapNotNull { ch ->
            runCatching { files.readChapterText(ch.textFile) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ChapterText(ch.orderIndex, ch.title, it) }
        }
    }

    private fun file(hash: String) = File(dir, "$hash.json")

    private fun read(hash: String): BookSummary? =
        file(hash).takeIf { it.isFile }?.let { f -> runCatching { AppJson.decodeFromString(BookSummary.serializer(), f.readText()) }.getOrNull() }

    /** Write-then-rename, so a crash mid-write never leaves a broken file. */
    private fun write(summary: BookSummary) {
        val target = file(summary.contentHash)
        val tmp = File(dir, "${summary.contentHash}.tmp")
        tmp.writeText(AppJson.encodeToString(BookSummary.serializer(), summary))
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}
