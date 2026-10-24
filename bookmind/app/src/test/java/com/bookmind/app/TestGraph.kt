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
import com.bookmind.app.ai.GeminiConfig
import com.bookmind.app.data.summary.BookSummaryRepository
import com.bookmind.core.ai.AiProvider
import com.bookmind.core.ai.gemini.TextModel
import com.bookmind.core.ai.summary.SummaryConfig
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
    /** The model the book summaries use; [FakeGemini] answers like a well-behaved model. */
    val summaryModel: TextModel = FakeGemini(),
    val gemini: GeminiConfig = GeminiConfig("test-key", "fake-gemini", "low", 1, SummaryConfig(chunkMinWords = 500, chunkMaxWords = 2_000)),
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
    val summaries = BookSummaryRepository(context, db, files, settings, gemini, summaryModel, scheduler, clock)
    val library = LibraryRepository(context, db, files, users, scheduler, clock, summaries)
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
    val account = AccountRepository(context, db, files, auth, settings, scheduler, clock, summaries)
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
        summaries.deleteAll()
    }
}

/**
 * A stand-in for Gemini that follows the summary prompts: the asked number of words per part (JSON) or for the
 * whole book (Markdown). [failWith] makes the next calls fail; [calls] counts requests.
 */
class FakeGemini : TextModel {
    override val model = "fake-gemini"
    var calls = 0
    val prompts = mutableListOf<String>()
    var failWith: ((Int) -> Exception?)? = null

    override suspend fun generate(system: String, user: String, maxOutputTokens: Int, json: Boolean): com.bookmind.core.ai.gemini.ModelReply {
        calls++
        prompts += user
        failWith?.invoke(calls)?.let { throw it }
        fun words(n: Int, tag: String) = (1..n).joinToString(" ") { "$tag$it" }
        if (json) {
            val parts = Regex("<part id=\"(\\d+)\"[^>]*chapter=\"([^\"]*)\"[^>]*target_words=\"(\\d+)\">").findAll(user).toList()
            val body = parts.joinToString(",") { m -> "{\"id\":${m.groupValues[1]},\"summary\":\"**${m.groupValues[2]}** özeti. ${words(m.groupValues[3].toInt(), "k")}\"}" }
            return com.bookmind.core.ai.gemini.ModelReply("{\"parts\":[$body]}", "STOP", 500, 100)
        }
        val max = Regex("between (\\d+) and (\\d+) words").find(user)!!.groupValues[2].toInt()
        return com.bookmind.core.ai.gemini.ModelReply("## Kitabın ana fikri\n\nBu kitap **öğrenmeyi** anlatır. ${words(max - 20, "g")}\n\n## Akılda kalanlar\n\n- Aralıklı tekrar\n- Aktif hatırlama", "STOP", 800, 300)
    }
}
