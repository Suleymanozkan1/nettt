package com.bookmind.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.files.BookFileStore
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.sync.SyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
    private val db: BookMindDatabase,
    private val files: BookFileStore,
    private val users: UserRepository,
    private val scheduler: WorkScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val report = engine.sync()
        // Books synced from another device have chapter rows but no local text yet: re-extract from the source file.
        for (book in db.books().all(users.userId())) {
            val chapters = db.chapters().forBook(book.id)
            val sourceFile = db.sources().forBook(book.id)?.fileReference?.let(files::resolve)
            if (chapters.any { !files.resolve(it.textFile).exists() } && sourceFile?.exists() == true) scheduler.processBook(book.id)
        }
        if (report.failed > 0 && runAttemptCount < 5) Result.retry() else Result.success()
    } catch (e: java.io.IOException) {
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
