package com.bookmind.app.processing

import androidx.room.withTransaction
import com.bookmind.app.data.db.BookEntity
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.ChapterEntity
import com.bookmind.app.data.db.ConceptEntity
import com.bookmind.app.data.db.LearningItemEntity
import com.bookmind.app.data.db.LearningItemType
import com.bookmind.app.data.db.ProcessingStatus
import com.bookmind.app.data.db.ReviewStateEntity
import com.bookmind.app.data.files.BookFileStore
import com.bookmind.app.data.files.ImportException
import com.bookmind.app.data.repo.SyncRecorder
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.AppJson
import com.bookmind.app.util.newId
import com.bookmind.app.util.toJson
import com.bookmind.core.ai.AiResult
import com.bookmind.core.ai.BookAnalysis
import com.bookmind.core.ai.BookGenre
import com.bookmind.core.ai.ChapterAnalysis
import com.bookmind.core.ai.ConceptDraft
import com.bookmind.core.ai.GenerationInfo
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.LocalHeuristicEngine
import com.bookmind.core.srs.CardPhase
import com.bookmind.core.text.Checksums
import com.bookmind.core.text.Chunker
import com.bookmind.core.text.SourceRef
import com.bookmind.core.text.SourceType
import com.bookmind.core.text.TextNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Book analysis pipeline. Idempotent and resumable: extracted chapters are reused when the processing version
 * matches, each analysed chapter is committed in its own transaction, and a restart continues with the
 * chapters that are not READY yet. Partial results are usable while the rest is processed.
 */
@Singleton
class BookProcessor @Inject constructor(
    private val db: BookMindDatabase,
    private val files: BookFileStore,
    private val extractor: TextExtractor,
    private val ai: LearningAi,
    private val users: UserRepository,
    private val sync: SyncRecorder,
    private val clock: AppClock,
) {
    companion object {
        /** Bump when extraction/analysis logic changes so old books can be re-processed. */
        const val PROCESSING_VERSION = 3
        const val MAX_CONCEPTS_PER_CHAPTER = 12
        fun chapterId(bookId: String, index: Int) = "$bookId:$index"
    }

    suspend fun process(bookId: String, useOcr: Boolean = false) {
        val book = db.books().get(bookId) ?: return
        try {
            var chapters = db.chapters().forBook(bookId)
            val source = db.sources().forBook(bookId)
            val textMissing = chapters.any { !files.resolve(it.textFile).exists() }
            if (chapters.isEmpty() || textMissing || source?.processingVersion != PROCESSING_VERSION) {
                chapters = extract(book, useOcr) ?: return
            }
            analyze(db.books().get(bookId)!!, chapters)
        } catch (e: CancellationException) {
            // WorkManager stopped us (constraints, app update). Status stays as-is and the next run resumes.
            throw e
        } catch (e: ImportException) {
            val status = if (e.reason == ImportException.Reason.NO_TEXT) ProcessingStatus.NEEDS_OCR else ProcessingStatus.FAILED
            db.books().setStatus(bookId, status, 0, e.reason.name + (e.message?.let { ": $it" } ?: ""), clock.now())
        } catch (e: Exception) {
            val hasChapters = db.chapters().forBook(bookId).isNotEmpty()
            db.books().setStatus(bookId, if (hasChapters) ProcessingStatus.PARTIAL else ProcessingStatus.FAILED, 0, e.javaClass.simpleName + ": " + (e.message ?: ""), clock.now())
        }
    }

    private suspend fun extract(book: BookEntity, useOcr: Boolean): List<ChapterEntity>? {
        db.books().setStatus(book.id, ProcessingStatus.EXTRACTING, 2, null, clock.now())
        val source = db.sources().forBook(book.id) ?: throw ImportException(ImportException.Reason.UNREADABLE, "kaynak yok")
        val file = files.resolve(source.fileReference ?: throw ImportException(ImportException.Reason.UNREADABLE))
        val onProgress: (Int) -> Unit = { _ -> }
        val extracted = if (useOcr && book.sourceType == SourceType.PDF) extractor.ocrPdf(file, onProgress = onProgress) else extractor.extract(file, book.sourceType, onProgress)
        if (extracted.needsOcr) {
            db.books().setStatus(book.id, ProcessingStatus.NEEDS_OCR, 0, extracted.warnings.joinToString("\n"), clock.now())
            return null
        }
        if (extracted.chapters.isEmpty() || extracted.totalChars < 200) throw ImportException(ImportException.Reason.NO_TEXT, "okunabilir metin bulunamadı")

        val now = clock.now()
        val existing = db.chapters().forBook(book.id).associateBy { it.id }
        val entities = extracted.chapters.mapIndexed { i, ch ->
            val id = chapterId(book.id, i)
            val hash = Checksums.sha256(ch.text)
            val textFile = files.writeChapterText(book.id, i, ch.text)
            val previous = existing[id]
            // Same text as before (e.g. re-extracting on a second device): keep analysis, concepts and cards.
            previous?.takeIf { it.contentHash == hash }?.copy(textFile = textFile, updatedAt = now) ?: ChapterEntity(
                id = id,
                bookId = book.id,
                title = ch.title.take(200),
                orderIndex = i,
                startLocation = ch.startLocation,
                endLocation = ch.endLocation,
                pageStart = ch.pageStart,
                pageEnd = ch.pageEnd,
                wordCount = TextNormalizer.wordCount(ch.text),
                textFile = textFile,
                contentHash = hash,
                processingStatus = ProcessingStatus.QUEUED,
                processingVersion = PROCESSING_VERSION,
                updatedAt = now,
            )
        }
        db.withTransaction {
            // Upsert in place (no delete-and-insert) so unchanged chapters keep their concepts via the FK.
            entities.forEach { db.chapters().upsert(it) }
            existing.values.filter { old -> entities.none { it.id == old.id } }.forEach { db.chapters().deleteById(it.id) }
            db.sources().setVersion(book.id, PROCESSING_VERSION)
            db.books().upsert(
                book.copy(
                    title = if (book.title.isBlank() || book.title == source.originalName?.substringBeforeLast('.')) extracted.title ?: book.title else book.title,
                    author = book.author ?: extracted.author,
                    language = book.language ?: extracted.language,
                    chapterCount = entities.size,
                    wordCount = entities.sumOf { it.wordCount },
                    processingStatus = ProcessingStatus.ANALYZING,
                    processingProgress = 20,
                    processingMessage = extracted.warnings.joinToString("\n").ifBlank { null },
                    updatedAt = now,
                ),
            )
        }
        sync.upsert("books", book.id)
        return entities
    }

    private suspend fun analyze(bookIn: BookEntity, chapters: List<ChapterEntity>) {
        var book = bookIn
        val inputs = chapters.map { LocalHeuristicEngine.ChapterInput(it.orderIndex, it.title, files.readChapterText(it.textFile), it.pageStart) }

        if (book.analysisJson == null) {
            db.books().setStatus(book.id, ProcessingStatus.ANALYZING, 22, null, clock.now())
            when (val r = ai.analyzeBook(book.title, book.author, book.language, inputs)) {
                is AiResult.Ok -> {
                    book = book.copy(
                        analysisJson = AppJson.encodeToString(BookAnalysis.serializer(), r.value),
                        category = r.value.genre.name,
                        description = book.description ?: r.value.mainTopic.takeIf { it.isNotBlank() },
                        author = book.author ?: r.value.author?.takeIf { it.isNotBlank() },
                        updatedAt = clock.now(),
                    )
                    db.books().upsert(book)
                }
                is AiResult.Failed -> Unit
            }
        }
        val genre = book.category?.let { runCatching { BookGenre.valueOf(it) }.getOrNull() } ?: BookGenre.INFORMATIONAL
        val info = LearningAi.Book(book.title, book.author, book.language, genre)
        val userId = users.userId()
        var failures = 0

        for ((i, chapter) in chapters.withIndex()) {
            coroutineContext.ensureActive()
            if (chapter.processingStatus == ProcessingStatus.READY && chapter.processingVersion == PROCESSING_VERSION) continue
            db.books().setStatus(book.id, ProcessingStatus.ANALYZING, 25 + 75 * i / chapters.size, chapter.title, clock.now())
            try {
                analyzeChapter(book, info, chapter, inputs[i], userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                db.chapters().upsert(chapter.copy(processingStatus = ProcessingStatus.FAILED, updatedAt = clock.now()))
            }
        }
        val status = if (failures == 0) ProcessingStatus.READY else ProcessingStatus.PARTIAL
        db.books().setStatus(book.id, status, 100, if (failures > 0) "$failures bölüm analiz edilemedi" else book.processingMessage, clock.now())
        sync.upsert("books", book.id)
    }

    private suspend fun analyzeChapter(book: BookEntity, info: LearningAi.Book, chapter: ChapterEntity, input: LocalHeuristicEngine.ChapterInput, userId: String) {
        val chunks = Chunker.chunk(input.index, input.text, maxChars = 12_000, overlapChars = 400)
        val analyses = mutableListOf<ChapterAnalysis>()
        var generatedBy: GenerationInfo? = null
        val warnings = mutableListOf<String>()
        for (chunk in chunks) {
            val part = if (chunks.size > 1) "(part ${chunk.index + 1}/${chunks.size})" else ""
            when (val r = ai.analyzeChapter(info, input, chunk.text, part)) {
                is AiResult.Ok -> { analyses += r.value; generatedBy = generatedBy ?: r.info; warnings += r.warnings }
                is AiResult.Failed -> throw r.error
            }
        }
        val merged = mergeAnalyses(analyses)
        val now = clock.now()
        val concepts = merged.concepts.take(MAX_CONCEPTS_PER_CHAPTER).map { c -> conceptEntity(book, chapter, c, generatedBy, now) }
        val isFiction = info.genre == BookGenre.FICTION
        val lang = book.language ?: "tr"

        db.withTransaction {
            db.concepts().deleteGeneratedForChapter(chapter.id)
            db.concepts().insertAll(concepts)
            for (c in concepts) {
                val item = LearningItemEntity(
                    id = newId(),
                    userId = userId,
                    bookId = book.id,
                    conceptId = c.id,
                    itemType = if (isFiction) LearningItemType.CHARACTER_RELATION else LearningItemType.CONCEPT,
                    prompt = if (lang == "en") "What is “${c.title}”? Explain in your own words." else "“${c.title}” nedir? Kendi cümlelerinle açıkla.",
                    expectedAnswer = c.explanation,
                    explanation = null,
                    sourceReferences = c.sourceReferences,
                    difficulty = c.difficulty,
                    dedupeKey = "concept:${book.id}:${c.normalizedTitle}",
                    createdAt = now,
                    updatedAt = now,
                )
                if (db.learning().insertItem(item) != -1L) {
                    db.learning().insertState(
                        ReviewStateEntity(newId(), userId, item.id, CardPhase.NEW.name, 0.0, 0.0, 2.5, reviewCount = 0, lapseCount = 0, algorithmVersion = "", updatedAt = now),
                    )
                } else {
                    // Re-analysis: keep the existing card (and its review history) and point it at the new concept row.
                    db.learning().relinkConcept(userId, item.dedupeKey, c.id, c.explanation, now)
                }
            }
            db.chapters().upsert(
                chapter.copy(
                    summary = merged.summary,
                    analysisJson = AppJson.encodeToString(ChapterAnalysis.serializer(), merged),
                    processingStatus = ProcessingStatus.READY,
                    processingVersion = PROCESSING_VERSION,
                    generatedBy = generatedBy?.toJson(),
                    updatedAt = now,
                ),
            )
        }
    }

    private fun conceptEntity(book: BookEntity, chapter: ChapterEntity, c: ConceptDraft, info: GenerationInfo?, now: Long): ConceptEntity {
        val normalized = TextNormalizer.asciiFold(c.title).trim()
        return ConceptEntity(
            id = newId(),
            bookId = book.id,
            chapterId = chapter.id,
            title = c.title,
            normalizedTitle = normalized,
            explanation = c.explanation,
            importance = c.importance,
            difficulty = c.difficulty,
            isPrerequisite = c.isPrerequisite,
            sourceReferences = listOf(SourceRef(chapter.orderIndex, chapter.title, chapter.pageStart, chapter.pageEnd, quote = c.quote, verified = c.quote != null)).toJson(),
            generatedBy = info?.toJson(),
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Combines per-chunk results: summaries concatenated briefly, concepts de-duplicated by normalised title. */
    internal fun mergeAnalyses(parts: List<ChapterAnalysis>): ChapterAnalysis {
        if (parts.size == 1) return parts.first()
        val concepts = parts.flatMap { it.concepts }
            .groupBy { TextNormalizer.asciiFold(it.title) }
            .map { (_, same) -> same.maxBy { it.importance * 10 + it.explanation.length.coerceAtMost(300) / 30 } }
            .sortedByDescending { it.importance }
        return ChapterAnalysis(
            summary = parts.joinToString(" ") { it.summary.substringBefore(". ") + "." }.take(1_500),
            keyPoints = parts.flatMap { it.keyPoints }.distinct().take(10),
            concepts = concepts,
            claims = parts.flatMap { it.claims }.distinct().take(10),
            examples = parts.flatMap { it.examples }.distinct().take(10),
            people = parts.flatMap { it.people }.distinct().take(15),
        )
    }
}
