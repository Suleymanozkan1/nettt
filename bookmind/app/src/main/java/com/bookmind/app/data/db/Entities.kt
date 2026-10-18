package com.bookmind.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bookmind.core.text.SourceType

/*
 * Room schema. Mirrors docs/DATA_MODEL.md and the Supabase tables in supabase/migrations.
 * Ids are UUID strings so rows created offline keep their identity when synced. JSON columns hold versioned
 * kotlinx.serialization documents (SourceRef lists, GenerationInfo, SummaryDocument, QuestionDraft).
 * `updatedAt` drives last-writer-wins sync; `deletedAt` is a tombstone so deletions also sync.
 */

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val language: String,
    val timezone: String,
    val dailyGoalMinutes: Int,
    val remoteUserId: String? = null,
    val email: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class ProcessingStatus { QUEUED, EXTRACTING, NEEDS_OCR, ANALYZING, PARTIAL, READY, FAILED }

@Entity(
    tableName = "books",
    indices = [Index("userId"), Index("userId", "updatedAt")],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val author: String? = null,
    val language: String? = null,
    /** BookGenre name (INFORMATIONAL, FICTION, …). */
    val category: String? = null,
    val description: String? = null,
    val sourceType: SourceType,
    val processingStatus: ProcessingStatus,
    val processingProgress: Int = 0,
    val processingMessage: String? = null,
    val chapterCount: Int = 0,
    val wordCount: Int = 0,
    /** BookAnalysis JSON (main topic, goals, prerequisites …). */
    val analysisJson: String? = null,
    val isDemo: Boolean = false,
    val lastOpenedAt: Long? = null,
    val completedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "book_sources",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", unique = true), Index("checksum")],
)
data class BookSourceEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    /** Path relative to the app's private files dir; null for manually entered text. */
    val fileReference: String?,
    val checksum: String,
    val sizeBytes: Long,
    val originalName: String?,
    val sourceMetadata: String? = null,
    val processingVersion: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "orderIndex"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val title: String,
    val orderIndex: Int,
    val startLocation: String? = null,
    val endLocation: String? = null,
    val pageStart: Int? = null,
    val pageEnd: Int? = null,
    val wordCount: Int,
    /** Chapter text lives in a file (chapters can exceed SQLite's cursor window). Relative to filesDir. */
    val textFile: String,
    val contentHash: String,
    /** ChapterAnalysis summary text. */
    val summary: String? = null,
    val analysisJson: String? = null,
    val processingStatus: ProcessingStatus,
    val processingVersion: Int = 0,
    val generatedBy: String? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "concepts",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ChapterEntity::class, ["id"], ["chapterId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId"), Index("chapterId"), Index(value = ["chapterId", "normalizedTitle"], unique = true)],
)
data class ConceptEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterId: String,
    val title: String,
    val normalizedTitle: String,
    val explanation: String,
    val importance: Int,
    val difficulty: Int,
    val isPrerequisite: Boolean = false,
    /** JSON list of SourceRef. */
    val sourceReferences: String,
    val isFavorite: Boolean = false,
    val userEdited: Boolean = false,
    /** GenerationInfo JSON — tells the UI whether this came from the book via AI, local engine or the user. */
    val generatedBy: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "summaries",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ChapterEntity::class, ["id"], ["chapterId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("chapterId"), Index(value = ["bookId", "summaryType", "length", "chapterId"])],
)
data class SummaryEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterId: String? = null,
    /** SummaryType name. */
    val summaryType: String,
    val length: String,
    /** SummaryDocument JSON. */
    val content: String,
    val sourceReferences: String,
    val modelMetadata: String,
    val warnings: String? = null,
    /** User's own edited version; shown instead of the generated text when present. */
    val userEditedText: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class LearningItemType { CONCEPT, KEY_IDEA, DATE_EVENT, FORMULA, CHARACTER_RELATION, PRINCIPLE, CASE, WRONG_ANSWER, USER_NOTE, QUESTION }

@Entity(
    tableName = "learning_items",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ConceptEntity::class, ["id"], ["conceptId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("userId"), Index("bookId"), Index("conceptId"), Index(value = ["userId", "dedupeKey"], unique = true)],
)
data class LearningItemEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val bookId: String,
    val conceptId: String? = null,
    val itemType: LearningItemType,
    val prompt: String,
    val expectedAnswer: String,
    val explanation: String? = null,
    val sourceReferences: String,
    val difficulty: Int,
    /** Optional QuestionDraft JSON so a review can be asked as a question instead of a flashcard. */
    val questionJson: String? = null,
    /** Stable key ("concept:<id>", "note:<id>", "wrong:<questionId>") preventing duplicate cards. */
    val dedupeKey: String,
    val suspended: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "review_states",
    foreignKeys = [ForeignKey(LearningItemEntity::class, ["id"], ["learningItemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("learningItemId", unique = true), Index("userId", "nextReviewAt")],
)
data class ReviewStateEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val learningItemId: String,
    val phase: String,
    val stability: Double,
    val difficulty: Double,
    val easeFactor: Double,
    val lastReviewAt: Long? = null,
    /** Null for new items that were never studied. */
    val nextReviewAt: Long? = null,
    val reviewCount: Int,
    val lapseCount: Int,
    val lastRating: Int? = null,
    val algorithmVersion: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "review_logs",
    foreignKeys = [ForeignKey(LearningItemEntity::class, ["id"], ["learningItemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("learningItemId", "reviewedAt"), Index("userId", "reviewedAt")],
)
data class ReviewLogEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val learningItemId: String,
    val reviewedAt: Long,
    val response: String? = null,
    /** Measured correctness 0..1 (null for pure self-graded flashcards). */
    val correctness: Double? = null,
    val responseTimeMs: Long? = null,
    /** User's self-assessment 1..4. */
    val confidenceRating: Int? = null,
    /** Final rating used by the scheduler 1..4. */
    val rating: Int,
    val scheduledIntervalMs: Long,
    val elapsedDays: Double? = null,
    val hintUsed: Boolean = false,
    /** RecallLevel name. */
    val recallLevel: String,
    val retrievabilityBefore: Double? = null,
    val source: String,
    val algorithmMetadata: String,
)

@Entity(
    tableName = "quizzes",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index("userId")],
)
data class QuizEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val bookId: String,
    val title: String,
    /** QuizConfig JSON. */
    val configuration: String,
    val generatedBy: String,
    val warnings: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "quiz_questions",
    foreignKeys = [
        ForeignKey(QuizEntity::class, ["id"], ["quizId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(LearningItemEntity::class, ["id"], ["learningItemId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("quizId", "orderIndex"), Index("learningItemId")],
)
data class QuizQuestionEntity(
    @PrimaryKey val id: String,
    val quizId: String,
    val learningItemId: String? = null,
    val orderIndex: Int,
    val questionType: String,
    val prompt: String,
    val options: String,
    val expectedAnswer: String,
    val explanation: String,
    val sourceReferences: String,
    val gradingRubric: String,
    /** Full QuestionDraft JSON. */
    val payload: String,
    val needsReview: Boolean,
)

@Entity(
    tableName = "quiz_attempts",
    foreignKeys = [ForeignKey(QuizEntity::class, ["id"], ["quizId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("quizId"), Index("userId", "startedAt")],
)
data class QuizAttemptEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val quizId: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val score: Double? = null,
    val resultMetadata: String? = null,
)

@Entity(
    tableName = "quiz_answers",
    foreignKeys = [
        ForeignKey(QuizAttemptEntity::class, ["id"], ["attemptId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(QuizQuestionEntity::class, ["id"], ["questionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["attemptId", "questionId"], unique = true), Index("questionId")],
)
data class QuizAnswerEntity(
    @PrimaryKey val id: String,
    val attemptId: String,
    val questionId: String,
    val userAnswer: String,
    val score: Double,
    val correct: Boolean,
    val feedback: String,
    /** Grader confidence 0..1 (1 for rule-graded objective questions). */
    val gradeConfidence: Double,
    val gradedBy: String,
    val responseTimeMs: Long,
    val answeredAt: Long,
)

enum class SessionType { TEACH, QUIZ, REVIEW, READ, PLAN_TASK, STORY }

@Entity(
    tableName = "study_sessions",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("userId", "startedAt"), Index("bookId")],
)
data class StudySessionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val bookId: String? = null,
    val sessionType: SessionType,
    val startedAt: Long,
    val completedAt: Long? = null,
    val sessionMetadata: String? = null,
)

@Entity(
    tableName = "teach_messages",
    foreignKeys = [ForeignKey(StudySessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId", "createdAt")],
)
data class TeachMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** "tutor" or "learner". */
    val role: String,
    val content: String,
    /** TeachTurn JSON for tutor turns (question, source, level). */
    val metadata: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "user_notes",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ChapterEntity::class, ["id"], ["chapterId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("userId"), Index("bookId"), Index("chapterId")],
)
data class UserNoteEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val bookId: String,
    val chapterId: String? = null,
    val content: String,
    /** Highlighted passage from the book, if the note was made on a selection. */
    val highlight: String? = null,
    val sourceLocation: String? = null,
    val isFavorite: Boolean = false,
    val inReviewQueue: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(tableName = "notification_preferences", indices = [Index("userId", unique = true)])
data class NotificationPreferenceEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val enabled: Boolean,
    /** "HH:mm" in the user's local time. */
    val localTime: String,
    /** Bit mask, Monday = bit 0 … Sunday = bit 6. */
    val selectedDays: Int,
    /** Comma separated: DUE_REVIEWS, OVERDUE, UPCOMING, DAILY_GOAL, CONTINUE_BOOK. */
    val notificationTypes: String,
    val intensity: Int,
    val pausedUntilEpochDay: Long? = null,
    /** Off by default: notifications never show note/book content unless the user allows it. */
    val showContent: Boolean = false,
    val updatedAt: Long,
)

@Entity(
    tableName = "stories",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ConceptEntity::class, ["id"], ["conceptId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId"), Index("conceptId")],
)
data class StoryEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val conceptId: String,
    val kind: String,
    val title: String,
    val body: String,
    val fromBook: Boolean,
    val sourceReference: String?,
    val questionsJson: String,
    val generatedBy: String,
    val createdAt: Long,
)

@Entity(tableName = "xp_events", indices = [Index("userId", "at")])
data class XpEventEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val kind: String,
    val xp: Int,
    val at: Long,
    val refId: String? = null,
)

@Entity(tableName = "ai_cache")
data class AiCacheEntity(
    @PrimaryKey val key: String,
    val task: String,
    val value: String,
    val createdAt: Long,
)

@Entity(tableName = "ai_usage", indices = [Index("at")])
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val task: String,
    val at: Long,
    val model: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val costMicros: Long?,
)

/** Local-first sync: every user-data write enqueues a row; SyncWorker pushes them in order. */
@Entity(tableName = "sync_outbox", indices = [Index("entity", "entityId")])
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entity: String,
    val entityId: String,
    /** UPSERT or DELETE. */
    val op: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

// ---- Added in schema version 2 (personalisation phase) ----

@Entity(
    tableName = "concept_links",
    foreignKeys = [
        ForeignKey(ConceptEntity::class, ["id"], ["fromConceptId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ConceptEntity::class, ["id"], ["toConceptId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["fromConceptId", "toConceptId", "relation"], unique = true), Index("toConceptId"), Index("userId")],
)
data class ConceptLinkEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val fromConceptId: String,
    val toConceptId: String,
    val relation: String,
    val explanation: String,
    val confidence: Double,
    /** True when an AI model proposed the link; false for the local name-matching heuristic or the user. */
    @ColumnInfo(defaultValue = "1") val aiDerived: Boolean,
    val engine: String,
    val createdAt: Long,
)

@Entity(
    tableName = "study_plans",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", unique = true), Index("userId")],
)
data class StudyPlanEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val bookId: String,
    val dailyMinutes: Int,
    val startEpochDay: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "plan_tasks",
    foreignKeys = [ForeignKey(StudyPlanEntity::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId", "epochDay")],
)
data class PlanTaskEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val epochDay: Long,
    val type: String,
    val chapterIndex: Int? = null,
    val minutes: Int,
    val title: String,
    val done: Boolean = false,
    val completedAt: Long? = null,
)
