package com.bookmind.app.data.repo

import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.LearningItemType
import com.bookmind.app.data.db.UserNoteEntity
import com.bookmind.app.data.db.XpEventEntity
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.newId
import com.bookmind.app.util.toJson
import com.bookmind.core.learning.Gamification
import com.bookmind.core.text.SourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class NoteRepository @Inject constructor(
    private val db: BookMindDatabase,
    private val users: UserRepository,
    private val reviews: ReviewRepository,
    private val clock: AppClock,
) {
    fun notes(bookId: String? = null, query: String = ""): Flow<List<UserNoteEntity>> =
        flow { emit(users.userId()) }.flatMapLatest { db.notes().observe(it, bookId, query.trim()) }

    suspend fun get(id: String) = db.notes().get(id)

    suspend fun save(
        id: String?,
        bookId: String,
        chapterId: String?,
        content: String,
        highlight: String?,
        sourceLocation: String?,
        addToReview: Boolean,
    ): String {
        require(content.isNotBlank() || !highlight.isNullOrBlank()) { "empty note" }
        val now = clock.now()
        val userId = users.userId()
        val existing = id?.let { db.notes().get(it) }
        val note = (existing ?: UserNoteEntity(newId(), userId, bookId, chapterId, "", createdAt = now, updatedAt = now)).copy(
            content = content.trim(), highlight = highlight?.trim()?.ifBlank { null }?.take(2_000), sourceLocation = sourceLocation,
            chapterId = chapterId ?: existing?.chapterId, inReviewQueue = addToReview || existing?.inReviewQueue == true, updatedAt = now,
        )
        db.notes().upsert(note)
        if (existing == null) db.xp().insert(XpEventEntity(newId(), userId, Gamification.Kind.NOTE_CREATED.name, Gamification.xp(Gamification.XpEvent(Gamification.Kind.NOTE_CREATED)), now, note.id))
        if (note.inReviewQueue) addToReview(note)
        return note.id
    }

    /** Turns a note into a review card: the highlight (or the first line) becomes the prompt, the note the answer. */
    suspend fun addToReview(note: UserNoteEntity) {
        val chapter = note.chapterId?.let { db.chapters().get(it) }
        val prompt = note.highlight?.let { AppLanguage.text.rememberHighlight(it.take(300)) } ?: note.content.lineSequence().first().take(200).let { AppLanguage.text.yourNote(it) }
        reviews.ensureItem(
            bookId = note.bookId, type = LearningItemType.USER_NOTE, dedupeKey = "note:${note.id}", prompt = prompt, expected = note.content,
            explanation = null, sourceRefs = listOfNotNull(chapter?.let { SourceRef(it.orderIndex, it.title, it.pageStart) }).toJson(),
        )
        if (!note.inReviewQueue) { db.notes().upsert(note.copy(inReviewQueue = true, updatedAt = clock.now())) }
    }

    suspend fun toggleFavorite(note: UserNoteEntity) {
        db.notes().upsert(note.copy(isFavorite = !note.isFavorite, updatedAt = clock.now()))
    }

    suspend fun delete(note: UserNoteEntity) {
        val now = clock.now()
        db.notes().upsert(note.copy(deletedAt = now, updatedAt = now))
        db.learning().byKey(note.userId, "note:${note.id}")?.let { reviews.suspend(it.id, true) }
    }
}
