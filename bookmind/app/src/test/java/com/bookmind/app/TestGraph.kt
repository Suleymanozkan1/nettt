package com.bookmind.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.files.BookFileStore
import com.bookmind.app.data.remote.AuthRepository
import com.bookmind.app.data.remote.BackendConfig
import com.bookmind.app.data.remote.SessionStore
import com.bookmind.app.data.repo.AccountRepository
import com.bookmind.app.data.repo.GraphRepository
import com.bookmind.app.data.repo.InsightsRepository
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.repo.NoteRepository
import com.bookmind.app.data.repo.PlanRepository
import com.bookmind.app.data.repo.QuizRepository
import com.bookmind.app.data.repo.ReviewRepository
import com.bookmind.app.data.repo.SessionTracker
import com.bookmind.app.data.repo.StudyRepository
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.notifications.ReminderManager
import com.bookmind.app.processing.BookProcessor
import com.bookmind.app.processing.TextExtractor
import com.bookmind.app.sync.SyncEngine
import com.bookmind.app.util.AppClock
import com.bookmind.app.work.WorkScheduler
import com.bookmind.core.ai.AiProvider
import com.bookmind.core.ai.LearningAi
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneId

class FakeClock(var nowMs: Long = Instant.parse("2026-03-10T09:00:00Z").toEpochMilli(), var zoneId: ZoneId = ZoneId.of("Europe/Istanbul")) : AppClock {
    override fun now() = nowMs
    override fun zone(): ZoneId = zoneId
    fun advanceDays(days: Double) { nowMs += (days * 86_400_000).toLong() }
}

class MemorySessionStore : SessionStore {
    var value: String? = null
    override fun read() = value
    override fun write(value: String?) { this.value = value }
}

/** Hand-wired object graph over an in-memory database: real repositories, no Hilt, no network unless configured. */
class TestGraph(
    val provider: AiProvider? = null,
    backend: BackendConfig = BackendConfig("", ""),
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val clock = FakeClock()
    val db: BookMindDatabase = Room.inMemoryDatabaseBuilder(context, BookMindDatabase::class.java).allowMainThreadQueries()
        .addCallback(object : androidx.room.RoomDatabase.Callback() { override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) = com.bookmind.app.sync.SyncTriggers.install(db) })
        .build()
    val workManager: WorkManager = run {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        WorkManager.getInstance(context)
    }
    val settings = SettingsRepository(context)
    val files = BookFileStore(context)
    val users = UserRepository(db, clock)
    val scheduler = WorkScheduler(workManager)
    val ai = LearningAi({ provider }, clock = clock::now, baseBackoffMs = 1)
    val extractor = TextExtractor(context)
    val processor = BookProcessor(db, files, extractor, ai, users, clock)
    val library = LibraryRepository(context, db, files, users, scheduler, clock)
    val sessions = SessionTracker(db, users, clock)
    val reviews = ReviewRepository(db, users, settings, clock)
    val study = StudyRepository(db, files, ai, users, sessions, reviews, settings, clock)
    val quizzes = QuizRepository(db, study, reviews, sessions, users, ai, files, clock)
    val notes = NoteRepository(db, users, reviews, clock)
    val insights = InsightsRepository(db, users, clock)
    val graph = GraphRepository(db, ai, users, clock)
    val plans = PlanRepository(db, users, clock)
    val sessionStore = MemorySessionStore()
    val auth = AuthRepository(backend, sessionStore, clock, OkHttpClient())
    val account = AccountRepository(context, db, files, auth, settings, scheduler, clock)
    val reminders = ReminderManager(db, users, scheduler, clock)
    val syncEngine = SyncEngine(db, auth, users, settings, files, clock)

    /** Imports and fully processes the bundled demo book. */
    suspend fun demoBook(): String {
        val id = library.addDemoBook()
        processor.process(id)
        return id
    }

    fun close() {
        db.close()
        files.deleteAll()
    }
}
