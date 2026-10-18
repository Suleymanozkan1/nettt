package com.bookmind.app.sync

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Change capture for sync. Every insert/update/delete on a synced table enqueues an outbox row from inside
 * SQLite, so no code path (including foreign-key cascades) can forget to record a change. Triggers are not
 * part of Room's schema validation; they are (re)created on every open, which also covers migrated databases.
 */
object SyncTriggers {
    /** Tables replicated to Supabase, in foreign-key order (parents first). */
    val TABLES = listOf(
        "books", "book_sources", "chapters", "concepts", "summaries", "learning_items", "review_states", "review_logs",
        "quizzes", "quiz_questions", "quiz_attempts", "quiz_answers", "study_sessions", "user_notes",
        "notification_preferences", "xp_events", "study_plans", "plan_tasks", "concept_links",
    )

    private const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

    fun install(db: SupportSQLiteDatabase) {
        for (t in TABLES + "users") {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_${t}_ins AFTER INSERT ON `$t` BEGIN INSERT INTO sync_outbox (entity, entityId, op, createdAt, attempts) VALUES ('$t', NEW.id, 'UPSERT', $NOW, 0); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_${t}_upd AFTER UPDATE ON `$t` BEGIN INSERT INTO sync_outbox (entity, entityId, op, createdAt, attempts) VALUES ('$t', NEW.id, 'UPSERT', $NOW, 0); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_${t}_del AFTER DELETE ON `$t` BEGIN INSERT INTO sync_outbox (entity, entityId, op, createdAt, attempts) VALUES ('$t', OLD.id, 'DELETE', $NOW, 0); END")
        }
    }
}
