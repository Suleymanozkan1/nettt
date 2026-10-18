package com.bookmind.app.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.files.BookFileStore
import com.bookmind.app.data.remote.AuthRepository
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.util.AppClock
import com.bookmind.core.sync.SupabaseRest
import com.bookmind.core.sync.SyncMerge
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-first synchronisation with Supabase.
 *
 * Push: the outbox (written in the same code paths as local changes) is replayed in order. Rows are read
 * generically from SQLite and converted to snake_case JSON; `user_id` is never sent — the server fills it
 * from the JWT and RLS rejects anything else.
 * Pull: rows changed since the last pull (by the server-maintained `server_updated_at`) are merged in
 * foreign-key order. Mutable rows use last-writer-wins, review states prefer the side with more reviews,
 * append-only history (review logs, answers, XP) is unioned. Book files go to a private Storage bucket.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val db: BookMindDatabase,
    private val auth: AuthRepository,
    private val users: UserRepository,
    private val settings: SettingsRepository,
    private val files: BookFileStore,
    private val clock: AppClock,
) {
    data class Table(val name: String, val mutable: Boolean, val hasUserId: Boolean)

    /** Foreign-key order. Teach transcripts, AI cache and usage stay on the device. */
    val tables = listOf(
        Table("books", true, true),
        Table("book_sources", false, false),
        Table("chapters", true, false),
        Table("concepts", true, false),
        Table("summaries", true, false),
        Table("learning_items", true, true),
        Table("review_states", true, true),
        Table("review_logs", false, true),
        Table("quizzes", false, true),
        Table("quiz_questions", false, false),
        Table("quiz_attempts", false, true),
        Table("quiz_answers", false, false),
        Table("study_sessions", false, true),
        Table("user_notes", true, true),
        Table("notification_preferences", true, true),
        Table("xp_events", false, true),
        Table("study_plans", true, true),
        Table("plan_tasks", false, false),
        Table("concept_links", false, true),
    )
    private val byName = tables.associateBy { it.name }

    /** SQLite stores booleans as integers; PostgreSQL needs real booleans. */
    private val booleanColumns = setOf(
        "isDemo", "isPrerequisite", "isFavorite", "userEdited", "suspended", "hintUsed", "needsReview", "correct",
        "inReviewQueue", "enabled", "showContent", "fromBook", "aiDerived", "done",
    )

    /** Device-specific columns that must not overwrite the other device's values. */
    private val localOnlyColumns = mapOf("chapters" to setOf("textFile"))

    data class Report(val pushed: Int, val pulled: Int, val failed: Int)

    suspend fun sync(): Report {
        val rest = auth.client() ?: return Report(0, 0, 0)
        val token = auth.accessToken() ?: return Report(0, 0, 0)
        val (pushed, failed) = push(rest, token)
        val pulled = pull(rest, token)
        return Report(pushed, pulled, failed)
    }

    private val sql get() = db.openHelper.writableDatabase

    private fun snake(s: String) = s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    private fun camel(s: String) = s.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }

    internal fun rowJson(table: String, id: String): JsonObject? {
        sql.query("SELECT * FROM `$table` WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            return cursorToJson(table, c)
        }
    }

    private fun cursorToJson(table: String, c: Cursor): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        val skip = localOnlyColumns[table].orEmpty() + "userId"
        for (i in 0 until c.columnCount) {
            val col = c.getColumnName(i)
            if (col in skip) continue
            map[snake(col)] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER -> if (col in booleanColumns) JsonPrimitive(c.getLong(i) != 0L) else JsonPrimitive(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                else -> JsonPrimitive(c.getString(i))
            }
        }
        return JsonObject(map)
    }

    internal fun jsonToValues(table: String, row: JsonObject, localUserId: String): ContentValues {
        val values = ContentValues()
        val columns = columnsOf(table)
        for ((key, value) in row) {
            val col = camel(key)
            if (col !in columns || col in localOnlyColumns[table].orEmpty()) continue
            val p = value as? JsonPrimitive
            when {
                value is JsonNull || p == null -> values.putNull(col)
                p.isString -> values.put(col, p.content)
                p.booleanOrNull != null -> values.put(col, if (p.booleanOrNull == true) 1 else 0)
                p.longOrNull != null -> values.put(col, p.longOrNull)
                p.doubleOrNull != null -> values.put(col, p.doubleOrNull)
                else -> values.put(col, p.content)
            }
        }
        if ("userId" in columns) values.put("userId", localUserId)
        if (table == "chapters" && !values.containsKey("textFile")) {
            // Text is re-extracted from the synced source file on this device.
            values.put("textFile", "books/${values.getAsString("bookId")}/chapters/${values.getAsInteger("orderIndex")}.txt")
        }
        return values
    }

    private val columnCache = HashMap<String, Set<String>>()
    private fun columnsOf(table: String): Set<String> = columnCache.getOrPut(table) {
        sql.query("PRAGMA table_info(`$table`)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
    }

    private suspend fun push(rest: SupabaseRest, token: String): Pair<Int, Int> {
        var pushed = 0
        var failed = 0
        while (true) {
            val batch = db.sync().pending(200)
            if (batch.isEmpty()) break
            val done = mutableListOf<Long>()
            // Group consecutive upserts per table, keeping table order so parents go before children.
            val upserts = batch.filter { it.op == "UPSERT" && it.entity in byName }.groupBy { it.entity }
            for (table in tables.map { it.name }.filter { it in upserts }) {
                val entries = upserts.getValue(table)
                val rows = entries.map { it.entityId }.distinct().mapNotNull { rowJson(table, it) }
                try {
                    rest.upsert(table, rows, token)
                    done += entries.map { it.id }
                    pushed += rows.size
                } catch (e: Exception) {
                    entries.forEach { db.sync().fail(it.id, e.message ?: "error") }
                    failed += entries.size
                }
            }
            for (e in batch.filter { it.op == "DELETE" }) {
                try { rest.delete(e.entity, e.entityId, token); done += e.id } catch (ex: Exception) { db.sync().fail(e.id, ex.message ?: "error"); failed++ }
            }
            for (e in batch.filter { it.op == "UPLOAD" }) {
                try { uploadSource(rest, token, e.entityId); done += e.id } catch (ex: Exception) { db.sync().fail(e.id, ex.message ?: "error"); failed++ }
            }
            // "users" changes go to the profile row.
            for (e in batch.filter { it.entity == "users" }) {
                runCatching {
                    db.users().current()?.let { u ->
                        rest.upsert("profiles", listOf(JsonObject(mapOf("language" to JsonPrimitive(u.language), "timezone" to JsonPrimitive(u.timezone), "daily_goal_minutes" to JsonPrimitive(u.dailyGoalMinutes)))), token)
                    }
                }.onSuccess { done += e.id }
            }
            // Entries for unknown entities are dropped so they never block the queue.
            done += batch.filter { it.entity !in byName && it.entity != "users" && it.op != "UPLOAD" }.map { it.id }
            db.sync().remove(done)
            if (done.isEmpty()) break // everything in this batch failed; retry on the next run
            // Give up on poison entries after many attempts so the rest can flow.
            if (batch.all { it.attempts >= 20 }) { db.sync().remove(batch.map { it.id }); break }
        }
        return pushed to failed
    }

    private suspend fun uploadSource(rest: SupabaseRest, token: String, bookId: String) {
        val source = db.sources().forBook(bookId) ?: return
        val file = source.fileReference?.let(files::resolve)?.takeIf(File::exists) ?: return
        val userId = auth.account.value?.userId ?: return
        rest.uploadFile(BUCKET, "$userId/$bookId/${file.name}", file, token, "application/octet-stream")
    }

    private suspend fun pull(rest: SupabaseRest, token: String): Int {
        val since = settings.current().lastSyncAt
        val sinceIso = Instant.ofEpochMilli(since).toString()
        val started = clock.now()
        val localUser = users.userId()
        var count = 0
        for (table in tables) {
            var offset = 0
            while (true) {
                val rows = rest.changedSince(table.name, "server_updated_at", sinceIso, token, limit = 500, offset = offset)
                if (rows.isEmpty()) break
                db.withTransaction { rows.forEach { if (apply(table, it, localUser)) count++ } }
                if (rows.size < 500) break
                offset += rows.size
            }
        }
        afterPull(rest, token)
        settings.update { it.copy(lastSyncAt = started) }
        return count
    }

    /** @return true when the local database changed. */
    internal fun apply(table: Table, row: JsonObject, localUser: String): Boolean {
        val id = (row["id"] as? JsonPrimitive)?.content ?: return false
        val values = jsonToValues(table.name, row, localUser)
        val existing = rowJson(table.name, id)
        if (existing != null) {
            if (!table.mutable) return false // append-only: already have it
            val decision = if (table.name == "review_states") {
                SyncMerge.resolveReviewState(existing.long("review_count")?.toInt() ?: 0, existing.long("last_review_at"), values.getAsInteger("reviewCount") ?: 0, values.getAsLong("lastReviewAt"))
            } else {
                SyncMerge.resolve(existing.long("updated_at"), existing.long("deleted_at"), values.getAsLong("updatedAt") ?: 0, values.getAsLong("deletedAt"))
            }
            if (decision == SyncMerge.Decision.KEEP_LOCAL) return false
            values.remove("id")
            return sql.update(table.name, SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf(id)) > 0
        }
        // Parent rows may be missing (e.g. a tombstoned book); skip instead of failing the whole pull.
        return runCatching { sql.insert(table.name, SQLiteDatabase.CONFLICT_IGNORE, values) >= 0 }.getOrDefault(false)
    }

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

    /** Purges tombstoned books and fetches source files for books that came from another device. */
    private suspend fun afterPull(rest: SupabaseRest, token: String) {
        val userId = users.userId()
        sql.query("SELECT id FROM books WHERE deletedAt IS NOT NULL").use { c ->
            val ids = buildList { while (c.moveToNext()) add(c.getString(0)) }
            ids.forEach { id ->
                files.deleteBook(id)
                sql.delete("chapters", "bookId = ?", arrayOf(id))
                sql.delete("learning_items", "bookId = ?", arrayOf(id))
            }
        }
        val account = auth.account.value ?: return
        for (book in db.books().all(userId)) {
            val source = db.sources().forBook(book.id) ?: continue
            val ref = source.fileReference ?: continue
            val target = files.resolve(ref)
            if (target.exists()) continue
            runCatching {
                target.parentFile?.mkdirs()
                rest.downloadFile(BUCKET, "${account.userId}/${book.id}/${target.name}", target, token)
            }
        }
    }

    companion object { const val BUCKET = "book-files" }
}
