package com.bookmind.app.data.repo

import android.content.Context
import android.net.Uri
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.files.BookFileStore
import com.bookmind.app.data.remote.AuthRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.util.AppClock
import com.bookmind.app.work.WorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Data export (portable ZIP with JSON + original book files) and account/data deletion. */
@Singleton
class AccountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BookMindDatabase,
    private val files: BookFileStore,
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val scheduler: WorkScheduler,
    private val clock: AppClock,
    private val summaries: com.bookmind.app.data.summary.BookSummaryRepository,
) {
    private val exportTables = listOf(
        "users", "books", "book_sources", "chapters", "concepts", "summaries", "learning_items", "review_states", "review_logs",
        "quizzes", "quiz_questions", "quiz_attempts", "quiz_answers", "study_sessions", "teach_messages", "user_notes",
        "notification_preferences", "stories", "xp_events", "concept_links", "study_plans", "plan_tasks",
    )

    /** Writes bookmind-export.zip to [target]: one JSON file per table plus the imported book files. */
    suspend fun export(target: Uri, includeBookFiles: Boolean = true) = withContext(Dispatchers.IO) {
        val sql = db.openHelper.readableDatabase
        context.contentResolver.openOutputStream(target)?.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("BookMind AI veri dışa aktarımı / data export\nexportedAt=${clock.now()}\nformat=1\n".toByteArray())
                zip.closeEntry()
                for (table in exportTables) {
                    val rows = sql.query("SELECT * FROM `$table`").use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                add(JsonObject((0 until c.columnCount).associate { i ->
                                    c.getColumnName(i) to when (c.getType(i)) {
                                        android.database.Cursor.FIELD_TYPE_NULL -> JsonNull
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                                        else -> JsonPrimitive(c.getString(i))
                                    }
                                }))
                            }
                        }
                    }
                    zip.putNextEntry(ZipEntry("data/$table.json"))
                    zip.write(JsonArray(rows).toString().toByteArray())
                    zip.closeEntry()
                }
                if (includeBookFiles) {
                    val root = File(context.filesDir, "books")
                    root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                        zip.putNextEntry(ZipEntry("files/" + f.relativeTo(root).path))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        } ?: error("cannot open export target")
    }

    /**
     * Deletes everything: the cloud account and its data (via the delete-account Edge Function, which removes
     * rows and storage objects server-side) and all local data, files and preferences.
     */
    suspend fun deleteEverything() {
        auth.client()?.let { rest -> auth.accessToken()?.let { token -> rest.invoke("delete-account", token) } }
        scheduler.cancelSync()
        scheduler.cancelReminders()
        auth.signOut()
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            // Triggers fire while tables are cleared; nothing from the deleted data may be uploaded later.
            db.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            files.deleteAll()
            summaries.deleteAll()
        }
        settings.clear()
    }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) { files.totalBytes() }
}
