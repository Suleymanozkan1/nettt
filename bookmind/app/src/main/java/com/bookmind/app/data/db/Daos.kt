package com.bookmind.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users LIMIT 1") suspend fun current(): UserEntity?
    @Query("SELECT * FROM users LIMIT 1") fun observe(): Flow<UserEntity?>
    @Upsert suspend fun upsert(user: UserEntity)
}

data class BookWithSource(val book: BookEntity, val checksum: String?)

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE userId = :userId AND deletedAt IS NULL ORDER BY COALESCE(lastOpenedAt, createdAt) DESC")
    fun observeAll(userId: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE userId = :userId AND deletedAt IS NULL AND (title LIKE '%' || :q || '%' OR author LIKE '%' || :q || '%') ORDER BY title")
    fun search(userId: String, q: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id") suspend fun get(id: String): BookEntity?
    @Query("SELECT * FROM books WHERE id = :id") fun observe(id: String): Flow<BookEntity?>
    @Query("SELECT * FROM books WHERE userId = :userId AND deletedAt IS NULL") suspend fun all(userId: String): List<BookEntity>
    @Insert suspend fun insert(book: BookEntity)
    @Upsert suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET processingStatus = :status, processingProgress = :progress, processingMessage = :message, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: ProcessingStatus, progress: Int, message: String?, now: Long)

    @Query("UPDATE books SET lastOpenedAt = :now WHERE id = :id") suspend fun touch(id: String, now: Long)
    @Query("DELETE FROM books WHERE id = :id") suspend fun delete(id: String)

    @Query(
        """SELECT b.* FROM books b JOIN book_sources s ON s.bookId = b.id
           WHERE b.userId = :userId AND s.checksum = :checksum AND b.deletedAt IS NULL LIMIT 1""",
    )
    suspend fun findByChecksum(userId: String, checksum: String): BookEntity?

    @Query("SELECT * FROM books WHERE processingStatus IN ('QUEUED','EXTRACTING','ANALYZING') AND deletedAt IS NULL")
    suspend fun unfinished(): List<BookEntity>
}

@Dao
interface BookSourceDao {
    @Insert suspend fun insert(source: BookSourceEntity)
    @Query("SELECT * FROM book_sources WHERE bookId = :bookId") suspend fun forBook(bookId: String): BookSourceEntity?
    @Query("UPDATE book_sources SET processingVersion = :version WHERE bookId = :bookId") suspend fun setVersion(bookId: String, version: Int)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex") fun observe(bookId: String): Flow<List<ChapterEntity>>
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex") suspend fun forBook(bookId: String): List<ChapterEntity>
    @Query("SELECT * FROM chapters WHERE id = :id") suspend fun get(id: String): ChapterEntity?
    @Insert suspend fun insertAll(chapters: List<ChapterEntity>)
    @Upsert suspend fun upsert(chapter: ChapterEntity)
    @Query("DELETE FROM chapters WHERE bookId = :bookId") suspend fun deleteForBook(bookId: String)
    @Query("DELETE FROM chapters WHERE id = :id") suspend fun deleteById(id: String)
}

@Dao
interface ConceptDao {
    @Query("SELECT * FROM concepts WHERE bookId = :bookId ORDER BY importance DESC, title") fun observeForBook(bookId: String): Flow<List<ConceptEntity>>
    @Query("SELECT * FROM concepts WHERE bookId = :bookId ORDER BY importance DESC, title") suspend fun forBook(bookId: String): List<ConceptEntity>
    @Query("SELECT * FROM concepts WHERE chapterId = :chapterId ORDER BY importance DESC") suspend fun forChapter(chapterId: String): List<ConceptEntity>
    @Query("SELECT * FROM concepts WHERE id = :id") suspend fun get(id: String): ConceptEntity?
    @Query("SELECT c.* FROM concepts c JOIN books b ON b.id = c.bookId WHERE b.userId = :userId AND b.deletedAt IS NULL")
    suspend fun allForUser(userId: String): List<ConceptEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(concepts: List<ConceptEntity>): List<Long>
    @Upsert suspend fun upsert(concept: ConceptEntity)
    @Query("DELETE FROM concepts WHERE chapterId = :chapterId AND userEdited = 0") suspend fun deleteGeneratedForChapter(chapterId: String)
    @Query("UPDATE concepts SET isFavorite = :fav, updatedAt = :now WHERE id = :id") suspend fun setFavorite(id: String, fav: Boolean, now: Long)
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM summaries WHERE bookId = :bookId AND summaryType = :type AND length = :length AND ((:chapterId IS NULL AND chapterId IS NULL) OR chapterId = :chapterId) ORDER BY createdAt DESC LIMIT 1")
    fun observe(bookId: String, type: String, length: String, chapterId: String?): Flow<SummaryEntity?>
    @Query("SELECT * FROM summaries WHERE bookId = :bookId ORDER BY createdAt DESC") suspend fun forBook(bookId: String): List<SummaryEntity>
    @Upsert suspend fun upsert(summary: SummaryEntity)
    @Query("UPDATE summaries SET userEditedText = :text, updatedAt = :now WHERE id = :id") suspend fun setUserText(id: String, text: String?, now: Long)
    @Query("DELETE FROM summaries WHERE id = :id") suspend fun delete(id: String)
}

data class DueItem(
    val itemId: String,
    val bookId: String,
    val bookTitle: String,
    val conceptId: String?,
    val itemType: LearningItemType,
    val prompt: String,
    val expectedAnswer: String,
    val explanation: String?,
    val sourceReferences: String,
    val questionJson: String?,
    val phase: String,
    val stability: Double,
    val difficulty: Double,
    val easeFactor: Double,
    val lastReviewAt: Long?,
    val nextReviewAt: Long?,
    val reviewCount: Int,
    val lapseCount: Int,
    val lastRating: Int?,
    val algorithmVersion: String,
)

data class BookRecall(val bookId: String, val title: String, val reviews: Int, val correct: Int)

@Dao
interface LearningDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertItem(item: LearningItemEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertState(state: ReviewStateEntity): Long
    @Upsert suspend fun upsertState(state: ReviewStateEntity)
    @Insert suspend fun insertLog(log: ReviewLogEntity)
    @Query("SELECT * FROM learning_items WHERE userId = :userId AND dedupeKey = :key") suspend fun byKey(userId: String, key: String): LearningItemEntity?
    @Query("SELECT * FROM learning_items WHERE id = :id") suspend fun item(id: String): LearningItemEntity?
    @Query("SELECT * FROM review_states WHERE learningItemId = :itemId") suspend fun state(itemId: String): ReviewStateEntity?
    @Query("UPDATE learning_items SET conceptId = :conceptId, expectedAnswer = :expected, updatedAt = :now WHERE userId = :userId AND dedupeKey = :key")
    suspend fun relinkConcept(userId: String, key: String, conceptId: String, expected: String, now: Long)
    @Query("UPDATE learning_items SET suspended = :suspended, updatedAt = :now WHERE id = :id") suspend fun setSuspended(id: String, suspended: Boolean, now: Long)

    @Query(DUE_SELECT + " AND s.nextReviewAt IS NOT NULL AND s.nextReviewAt <= :until ORDER BY s.nextReviewAt LIMIT :limit")
    suspend fun due(userId: String, until: Long, limit: Int): List<DueItem>

    @Query(DUE_SELECT + " AND s.nextReviewAt IS NULL ORDER BY i.difficulty, i.createdAt LIMIT :limit")
    suspend fun newItems(userId: String, limit: Int): List<DueItem>

    @Query(DUE_SELECT + " AND i.bookId = :bookId ORDER BY s.nextReviewAt")
    suspend fun forBook(userId: String, bookId: String): List<DueItem>

    @Query("SELECT COUNT(*) FROM review_states s JOIN learning_items i ON i.id = s.learningItemId JOIN books b ON b.id = i.bookId WHERE s.userId = :userId AND i.suspended = 0 AND b.deletedAt IS NULL AND s.nextReviewAt IS NOT NULL AND s.nextReviewAt <= :until")
    fun observeDueCount(userId: String, until: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_states s JOIN learning_items i ON i.id = s.learningItemId JOIN books b ON b.id = i.bookId WHERE s.userId = :userId AND i.suspended = 0 AND b.deletedAt IS NULL AND s.nextReviewAt IS NOT NULL AND s.nextReviewAt <= :until")
    suspend fun dueCount(userId: String, until: Long): Int

    @Query("SELECT COUNT(*) FROM review_states s JOIN learning_items i ON i.id = s.learningItemId WHERE s.userId = :userId AND i.suspended = 0 AND s.nextReviewAt IS NULL")
    suspend fun newCount(userId: String): Int

    @Query("SELECT nextReviewAt FROM review_states s JOIN learning_items i ON i.id = s.learningItemId WHERE s.userId = :userId AND i.suspended = 0 AND s.nextReviewAt IS NOT NULL AND s.nextReviewAt BETWEEN :from AND :to")
    suspend fun upcoming(userId: String, from: Long, to: Long): List<Long>

    @Query("SELECT * FROM review_logs WHERE userId = :userId AND reviewedAt >= :since ORDER BY reviewedAt")
    suspend fun logsSince(userId: String, since: Long): List<ReviewLogEntity>

    @Query("SELECT * FROM review_logs WHERE learningItemId = :itemId ORDER BY reviewedAt") suspend fun logsForItem(itemId: String): List<ReviewLogEntity>
    @Query("SELECT COUNT(*) FROM review_logs WHERE userId = :userId") fun observeReviewCount(userId: String): Flow<Int>

    @Query(
        """SELECT b.id AS bookId, b.title AS title, COUNT(l.id) AS reviews, SUM(CASE WHEN l.rating > 1 THEN 1 ELSE 0 END) AS correct
           FROM review_logs l JOIN learning_items i ON i.id = l.learningItemId JOIN books b ON b.id = i.bookId
           WHERE l.userId = :userId AND b.deletedAt IS NULL GROUP BY b.id ORDER BY b.title""",
    )
    suspend fun recallByBook(userId: String): List<BookRecall>

    @Query("SELECT * FROM review_states WHERE userId = :userId") suspend fun allStates(userId: String): List<ReviewStateEntity>
    @Query("SELECT * FROM learning_items WHERE userId = :userId") suspend fun allItems(userId: String): List<LearningItemEntity>

    companion object {
        const val DUE_SELECT = """SELECT i.id AS itemId, i.bookId AS bookId, b.title AS bookTitle, i.conceptId AS conceptId, i.itemType AS itemType,
            i.prompt AS prompt, i.expectedAnswer AS expectedAnswer, i.explanation AS explanation, i.sourceReferences AS sourceReferences,
            i.questionJson AS questionJson, s.phase AS phase, s.stability AS stability, s.difficulty AS difficulty, s.easeFactor AS easeFactor,
            s.lastReviewAt AS lastReviewAt, s.nextReviewAt AS nextReviewAt, s.reviewCount AS reviewCount, s.lapseCount AS lapseCount,
            s.lastRating AS lastRating, s.algorithmVersion AS algorithmVersion
            FROM learning_items i JOIN review_states s ON s.learningItemId = i.id JOIN books b ON b.id = i.bookId
            WHERE i.userId = :userId AND i.suspended = 0 AND b.deletedAt IS NULL"""
    }
}

data class AttemptSummary(val attemptId: String, val quizId: String, val title: String, val bookId: String, val completedAt: Long, val score: Double)

@Dao
interface QuizDao {
    @Insert suspend fun insertQuiz(quiz: QuizEntity)
    @Insert suspend fun insertQuestions(questions: List<QuizQuestionEntity>)
    @Insert suspend fun insertAttempt(attempt: QuizAttemptEntity)
    @Upsert suspend fun upsertAttempt(attempt: QuizAttemptEntity)
    @Upsert suspend fun upsertAnswer(answer: QuizAnswerEntity)
    @Query("SELECT * FROM quizzes WHERE id = :id") suspend fun quiz(id: String): QuizEntity?
    @Query("SELECT * FROM quiz_questions WHERE quizId = :quizId ORDER BY orderIndex") suspend fun questions(quizId: String): List<QuizQuestionEntity>
    @Query("SELECT * FROM quiz_attempts WHERE id = :id") suspend fun attempt(id: String): QuizAttemptEntity?
    @Query("SELECT * FROM quiz_answers WHERE attemptId = :attemptId") suspend fun answers(attemptId: String): List<QuizAnswerEntity>
    @Query("SELECT * FROM quiz_answers WHERE attemptId = :attemptId") fun observeAnswers(attemptId: String): Flow<List<QuizAnswerEntity>>

    @Query(
        """SELECT a.id AS attemptId, q.id AS quizId, q.title AS title, q.bookId AS bookId, a.completedAt AS completedAt, a.score AS score
           FROM quiz_attempts a JOIN quizzes q ON q.id = a.quizId JOIN books b ON b.id = q.bookId
           WHERE a.userId = :userId AND a.completedAt IS NOT NULL AND b.deletedAt IS NULL ORDER BY a.completedAt DESC LIMIT :limit""",
    )
    fun observeRecent(userId: String, limit: Int): Flow<List<AttemptSummary>>

    /** Questions the user answered wrong in any attempt for a book, latest first. */
    @Query(
        """SELECT qq.* FROM quiz_questions qq JOIN quiz_answers qa ON qa.questionId = qq.id JOIN quizzes q ON q.id = qq.quizId
           WHERE q.bookId = :bookId AND qa.correct = 0 GROUP BY qq.prompt ORDER BY MAX(qa.answeredAt) DESC""",
    )
    suspend fun wrongQuestions(bookId: String): List<QuizQuestionEntity>

    @Query(
        """SELECT qq.payload FROM quiz_answers qa JOIN quiz_questions qq ON qq.id = qa.questionId JOIN quiz_attempts a ON a.id = qa.attemptId
           WHERE a.userId = :userId"""
    )
    suspend fun allAnsweredPayloads(userId: String): List<String>

    @Query("SELECT qa.* FROM quiz_answers qa JOIN quiz_attempts a ON a.id = qa.attemptId WHERE a.userId = :userId")
    suspend fun allAnswers(userId: String): List<QuizAnswerEntity>
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: StudySessionEntity)
    @Upsert suspend fun upsert(session: StudySessionEntity)
    @Query("SELECT * FROM study_sessions WHERE id = :id") suspend fun get(id: String): StudySessionEntity?
    @Query("SELECT * FROM study_sessions WHERE userId = :userId AND startedAt >= :since ORDER BY startedAt") suspend fun since(userId: String, since: Long): List<StudySessionEntity>
    @Query("SELECT * FROM study_sessions WHERE userId = :userId AND startedAt >= :since ORDER BY startedAt") fun observeSince(userId: String, since: Long): Flow<List<StudySessionEntity>>
    @Insert suspend fun insertMessage(message: TeachMessageEntity)
    @Query("SELECT * FROM teach_messages WHERE sessionId = :sessionId ORDER BY createdAt") fun observeMessages(sessionId: String): Flow<List<TeachMessageEntity>>
    @Query("SELECT * FROM teach_messages WHERE sessionId = :sessionId ORDER BY createdAt") suspend fun messages(sessionId: String): List<TeachMessageEntity>
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM user_notes WHERE userId = :userId AND deletedAt IS NULL AND (:bookId IS NULL OR bookId = :bookId) AND (content LIKE '%' || :q || '%' OR highlight LIKE '%' || :q || '%') ORDER BY isFavorite DESC, updatedAt DESC")
    fun observe(userId: String, bookId: String?, q: String): Flow<List<UserNoteEntity>>
    @Query("SELECT * FROM user_notes WHERE id = :id") suspend fun get(id: String): UserNoteEntity?
    @Query("SELECT * FROM user_notes WHERE userId = :userId AND deletedAt IS NULL") suspend fun all(userId: String): List<UserNoteEntity>
    @Upsert suspend fun upsert(note: UserNoteEntity)
}

@Dao
interface NotificationPrefDao {
    @Query("SELECT * FROM notification_preferences WHERE userId = :userId") suspend fun get(userId: String): NotificationPreferenceEntity?
    @Query("SELECT * FROM notification_preferences WHERE userId = :userId") fun observe(userId: String): Flow<NotificationPreferenceEntity?>
    @Upsert suspend fun upsert(pref: NotificationPreferenceEntity)
}

@Dao
interface StoryDao {
    @Insert suspend fun insert(story: StoryEntity)
    @Query("SELECT * FROM stories WHERE conceptId = :conceptId ORDER BY createdAt DESC") fun observeForConcept(conceptId: String): Flow<List<StoryEntity>>
}

@Dao
interface XpDao {
    @Insert suspend fun insert(event: XpEventEntity)
    @Query("SELECT COALESCE(SUM(xp), 0) FROM xp_events WHERE userId = :userId") fun observeTotal(userId: String): Flow<Int>
    @Query("SELECT COALESCE(SUM(xp), 0) FROM xp_events WHERE userId = :userId AND at >= :since") suspend fun totalSince(userId: String, since: Long): Int
    @Query("SELECT COUNT(*) FROM xp_events WHERE userId = :userId AND kind = :kind") suspend fun count(userId: String, kind: String): Int
    @Query("SELECT COUNT(*) FROM xp_events WHERE userId = :userId AND kind = :kind AND refId = :refId") suspend fun countRef(userId: String, kind: String, refId: String): Int
}

@Dao
interface AiDao {
    @Query("SELECT value FROM ai_cache WHERE `key` = :key") suspend fun cached(key: String): String?
    @Upsert suspend fun putCache(entry: AiCacheEntity)
    @Query("DELETE FROM ai_cache WHERE createdAt < :before") suspend fun pruneCache(before: Long)
    @Insert suspend fun insertUsage(usage: AiUsageEntity)
    @Query("SELECT COUNT(*) FROM ai_usage WHERE at >= :since") suspend fun requestsSince(since: Long): Int
    @Query("SELECT * FROM ai_usage WHERE at >= :since ORDER BY at DESC") fun observeUsageSince(since: Long): Flow<List<AiUsageEntity>>
}

@Dao
interface SyncDao {
    @Insert suspend fun enqueue(entry: SyncOutboxEntity)
    @Query("SELECT * FROM sync_outbox ORDER BY id LIMIT :limit") suspend fun pending(limit: Int): List<SyncOutboxEntity>
    @Query("DELETE FROM sync_outbox WHERE id IN (:ids)") suspend fun remove(ids: List<Long>)
    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id") suspend fun fail(id: Long, error: String)
    @Query("SELECT COUNT(*) FROM sync_outbox") fun observeCount(): Flow<Int>
}

data class LinkRow(
    val id: String,
    val relation: String,
    val explanation: String,
    val confidence: Double,
    val aiDerived: Boolean,
    val fromConceptId: String,
    val fromTitle: String,
    val fromExplanation: String,
    val fromBookId: String,
    val fromBookTitle: String,
    val toConceptId: String,
    val toTitle: String,
    val toExplanation: String,
    val toBookId: String,
    val toBookTitle: String,
)

@Dao
interface GraphDao {
    @Upsert suspend fun upsert(links: List<ConceptLinkEntity>)
    @Query("DELETE FROM concept_links WHERE userId = :userId AND engine != 'user'") suspend fun clearGenerated(userId: String)

    @Query(
        """SELECT l.id, l.relation, l.explanation, l.confidence, l.aiDerived,
           a.id AS fromConceptId, a.title AS fromTitle, a.explanation AS fromExplanation, ba.id AS fromBookId, ba.title AS fromBookTitle,
           c.id AS toConceptId, c.title AS toTitle, c.explanation AS toExplanation, bc.id AS toBookId, bc.title AS toBookTitle
           FROM concept_links l
           JOIN concepts a ON a.id = l.fromConceptId JOIN books ba ON ba.id = a.bookId
           JOIN concepts c ON c.id = l.toConceptId JOIN books bc ON bc.id = c.bookId
           WHERE l.userId = :userId AND ba.deletedAt IS NULL AND bc.deletedAt IS NULL
           ORDER BY l.confidence DESC""",
    )
    fun observe(userId: String): Flow<List<LinkRow>>
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM study_plans WHERE bookId = :bookId") suspend fun forBook(bookId: String): StudyPlanEntity?
    @Query("SELECT * FROM study_plans WHERE bookId = :bookId") fun observeForBook(bookId: String): Flow<StudyPlanEntity?>
    @Upsert suspend fun upsertPlan(plan: StudyPlanEntity)
    @Query("DELETE FROM plan_tasks WHERE planId = :planId") suspend fun clearTasks(planId: String)
    @Insert suspend fun insertTasks(tasks: List<PlanTaskEntity>)
    @Query("SELECT * FROM plan_tasks WHERE planId = :planId ORDER BY epochDay, rowid") suspend fun tasks(planId: String): List<PlanTaskEntity>
    @Query("SELECT * FROM plan_tasks WHERE planId = :planId ORDER BY epochDay, rowid") fun observeTasks(planId: String): Flow<List<PlanTaskEntity>>
    @Query("UPDATE plan_tasks SET done = :done, completedAt = :at WHERE id = :id") suspend fun setDone(id: String, done: Boolean, at: Long?)

    @Query(
        """SELECT t.* FROM plan_tasks t JOIN study_plans p ON p.id = t.planId JOIN books b ON b.id = p.bookId
           WHERE p.userId = :userId AND t.epochDay = :epochDay AND b.deletedAt IS NULL ORDER BY t.rowid""",
    )
    fun observeToday(userId: String, epochDay: Long): Flow<List<PlanTaskEntity>>

    @Transaction
    suspend fun replaceTasks(planId: String, tasks: List<PlanTaskEntity>) {
        clearTasks(planId)
        insertTasks(tasks)
    }
}
