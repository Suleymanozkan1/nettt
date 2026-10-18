package com.bookmind.app.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    version = BookMindDatabase.VERSION,
    exportSchema = true,
    entities = [
        UserEntity::class, BookEntity::class, BookSourceEntity::class, ChapterEntity::class, ConceptEntity::class,
        SummaryEntity::class, LearningItemEntity::class, ReviewStateEntity::class, ReviewLogEntity::class,
        QuizEntity::class, QuizQuestionEntity::class, QuizAttemptEntity::class, QuizAnswerEntity::class,
        StudySessionEntity::class, TeachMessageEntity::class, UserNoteEntity::class, NotificationPreferenceEntity::class,
        StoryEntity::class, XpEventEntity::class, AiCacheEntity::class, AiUsageEntity::class, SyncOutboxEntity::class,
        // v2: cross-book knowledge graph and personal study plans
        ConceptLinkEntity::class, StudyPlanEntity::class, PlanTaskEntity::class,
    ],
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class BookMindDatabase : RoomDatabase() {
    abstract fun users(): UserDao
    abstract fun books(): BookDao
    abstract fun sources(): BookSourceDao
    abstract fun chapters(): ChapterDao
    abstract fun concepts(): ConceptDao
    abstract fun summaries(): SummaryDao
    abstract fun learning(): LearningDao
    abstract fun quizzes(): QuizDao
    abstract fun sessions(): SessionDao
    abstract fun notes(): NoteDao
    abstract fun notificationPrefs(): NotificationPrefDao
    abstract fun stories(): StoryDao
    abstract fun xp(): XpDao
    abstract fun ai(): AiDao
    abstract fun sync(): SyncDao
    abstract fun graph(): GraphDao
    abstract fun plans(): PlanDao

    companion object {
        const val VERSION = 2
        const val NAME = "bookmind.db"
    }
}
