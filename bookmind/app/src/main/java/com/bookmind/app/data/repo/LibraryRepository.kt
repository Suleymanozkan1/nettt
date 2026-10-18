package com.bookmind.app.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.bookmind.app.data.db.BookEntity
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.BookSourceEntity
import com.bookmind.app.data.db.ChapterEntity
import com.bookmind.app.data.db.ProcessingStatus
import com.bookmind.app.data.files.BookFileStore
import com.bookmind.app.data.files.ImportException
import com.bookmind.app.processing.BookProcessor
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.newId
import com.bookmind.app.work.WorkScheduler
import com.bookmind.core.text.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

class DuplicateBookException(val existingBookId: String) : Exception("duplicate")

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BookMindDatabase,
    private val files: BookFileStore,
    private val users: UserRepository,
    private val scheduler: WorkScheduler,
    private val sync: SyncRecorder,
    private val clock: AppClock,
) {
    fun books(query: String = ""): Flow<List<BookEntity>> = flow { emit(users.userId()) }.flatMapLatest { uid ->
        if (query.isBlank()) db.books().observeAll(uid) else db.books().search(uid, query.trim())
    }

    fun book(id: String): Flow<BookEntity?> = db.books().observe(id)
    fun chapters(bookId: String): Flow<List<ChapterEntity>> = db.chapters().observe(bookId)
    suspend fun chapter(id: String): ChapterEntity? = db.chapters().get(id)
    suspend fun chapterText(chapter: ChapterEntity): String = withContext(Dispatchers.IO) { files.readChapterText(chapter.textFile) }
    fun processingWork(bookId: String) = scheduler.processingInfo(bookId)

    /** Imports a user-picked file. Throws [ImportException] or [DuplicateBookException]. */
    suspend fun importFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val bookId = newId()
        val imported = try {
            files.import(uri, bookId)
        } catch (e: Exception) {
            files.deleteBook(bookId)
            throw e
        }
        val title = imported.originalName?.substringBeforeLast('.')?.replace('_', ' ')?.trim()?.ifBlank { null } ?: "Adsız kitap"
        register(bookId, title, null, imported, isDemo = false)
    }

    suspend fun importText(title: String, author: String?, text: String, markdown: Boolean, isDemo: Boolean = false): String = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "title" }
        if (text.trim().length < 200) throw ImportException(ImportException.Reason.NO_TEXT, "metin çok kısa")
        val bookId = newId()
        val imported = files.importText(text, bookId, markdown)
        register(bookId, title.trim(), author?.trim()?.ifBlank { null }, imported, isDemo)
    }

    /** Adds the bundled, original CC0 demo book ("Öğrenmenin Bilimi"). */
    suspend fun addDemoBook(): String {
        val text = withContext(Dispatchers.IO) { context.assets.open("demo/ogrenmenin_bilimi.md").bufferedReader().use { it.readText() } }
        return try {
            importText("Öğrenmenin Bilimi", "BookMind (demo, CC0)", text, markdown = true, isDemo = true)
        } catch (e: DuplicateBookException) {
            e.existingBookId
        }
    }

    private suspend fun register(bookId: String, title: String, author: String?, imported: BookFileStore.Imported, isDemo: Boolean): String {
        val userId = users.userId()
        db.books().findByChecksum(userId, imported.checksum)?.let { existing ->
            files.deleteBook(bookId)
            throw DuplicateBookException(existing.id)
        }
        val now = clock.now()
        db.withTransaction {
            db.books().insert(
                BookEntity(
                    id = bookId, userId = userId, title = title, author = author, sourceType = imported.type,
                    processingStatus = ProcessingStatus.QUEUED, isDemo = isDemo, createdAt = now, updatedAt = now,
                ),
            )
            db.sources().insert(BookSourceEntity(newId(), bookId, imported.relativePath, imported.checksum, imported.size, imported.originalName, null, 0, now))
        }
        sync.upsert("books", bookId)
        db.sources().forBook(bookId)?.let { sync.upsert("book_sources", it.id) }
        db.sync().enqueue(com.bookmind.app.data.db.SyncOutboxEntity(entity = "file", entityId = bookId, op = "UPLOAD", createdAt = now))
        scheduler.processBook(bookId)
        return bookId
    }

    fun retry(bookId: String, ocr: Boolean = false) {
        scheduler.cancelBook(bookId)
        scheduler.processBook(bookId, ocr)
    }

    suspend fun opened(bookId: String) = db.books().touch(bookId, clock.now())

    suspend fun updateMetadata(bookId: String, title: String, author: String?) {
        val book = db.books().get(bookId) ?: return
        db.books().upsert(book.copy(title = title.trim().ifBlank { book.title }, author = author?.trim()?.ifBlank { null }, updatedAt = clock.now()))
        sync.upsert("books", bookId)
    }

    suspend fun markCompleted(bookId: String, completed: Boolean) {
        val book = db.books().get(bookId) ?: return
        db.books().upsert(book.copy(completedAt = if (completed) clock.now() else null, updatedAt = clock.now()))
        sync.upsert("books", bookId)
    }

    /**
     * Deletes the book, its files and all derived data. The book row stays as a tombstone (deletedAt) so the
     * deletion reaches the user's other devices; child rows are removed by foreign-key cascades.
     */
    suspend fun delete(bookId: String) {
        scheduler.cancelBook(bookId)
        val book = db.books().get(bookId) ?: return
        val now = clock.now()
        db.withTransaction {
            db.chapters().deleteForBook(bookId)
            db.openHelper.writableDatabase.execSQL("DELETE FROM learning_items WHERE bookId = ?", arrayOf(bookId))
            db.openHelper.writableDatabase.execSQL("DELETE FROM summaries WHERE bookId = ?", arrayOf(bookId))
            db.openHelper.writableDatabase.execSQL("DELETE FROM quizzes WHERE bookId = ?", arrayOf(bookId))
            db.openHelper.writableDatabase.execSQL("UPDATE user_notes SET deletedAt = ?, updatedAt = ? WHERE bookId = ?", arrayOf(now, now, bookId))
            db.books().upsert(book.copy(deletedAt = now, updatedAt = now, processingStatus = ProcessingStatus.READY))
        }
        withContext(Dispatchers.IO) { files.deleteBook(bookId) }
        sync.upsert("books", bookId)
    }
}
