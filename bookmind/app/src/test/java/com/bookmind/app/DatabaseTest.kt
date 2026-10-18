package com.bookmind.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookmind.app.data.db.BookEntity
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.ChapterEntity
import com.bookmind.app.data.db.ConceptEntity
import com.bookmind.app.data.db.LearningItemEntity
import com.bookmind.app.data.db.LearningItemType
import com.bookmind.app.data.db.ProcessingStatus
import com.bookmind.app.data.db.ReviewStateEntity
import com.bookmind.core.text.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BookMindDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private val graph = TestGraph()
    @After fun tearDown() = graph.close()

    @Test fun `migration 1 to 2 keeps data and adds the new tables`() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO users (id, language, timezone, dailyGoalMinutes, createdAt, updatedAt) VALUES ('u', 'tr', 'Europe/Istanbul', 10, 1, 1)")
            db.execSQL("INSERT INTO books (id, userId, title, sourceType, processingStatus, processingProgress, chapterCount, wordCount, isDemo, createdAt, updatedAt) VALUES ('b', 'u', 'Kitap', 'TXT', 'READY', 100, 0, 0, 0, 1, 1)")
        }
        helper.runMigrationsAndValidate(name, 2, true).use { db ->
            db.query("SELECT title FROM books WHERE id = 'b'").use { c -> c.moveToFirst(); assertEquals("Kitap", c.getString(0)) }
            db.execSQL("INSERT INTO study_plans (id, userId, bookId, dailyMinutes, startEpochDay, createdAt, updatedAt) VALUES ('p', 'u', 'b', 15, 0, 1, 1)")
        }
        // Room opens the migrated file and validates it against the current schema.
        val room = Room.databaseBuilder(graph.context, BookMindDatabase::class.java, name).allowMainThreadQueries().build()
        runTest { assertEquals("Kitap", room.books().get("b")!!.title) }
        room.close()
    }

    @Test fun `deleting a book cascades to its derived data, deleting a concept keeps the card`() = runTest {
        val db = graph.db
        db.books().insert(BookEntity("b", "u", "T", sourceType = SourceType.TXT, processingStatus = ProcessingStatus.READY, createdAt = 1, updatedAt = 1))
        db.chapters().insertAll(listOf(ChapterEntity("c", "b", "Ch", 0, wordCount = 1, textFile = "x", contentHash = "h", processingStatus = ProcessingStatus.READY, updatedAt = 1)))
        db.concepts().insertAll(listOf(ConceptEntity("k", "b", "c", "Kavram", "kavram", "açıklama", 3, 3, sourceReferences = "[]", createdAt = 1, updatedAt = 1)))
        db.learning().insertItem(LearningItemEntity("i", "u", "b", "k", LearningItemType.CONCEPT, "p", "e", null, "[]", 3, null, "concept:b:kavram", createdAt = 1, updatedAt = 1))
        db.learning().insertState(ReviewStateEntity("s", "u", "i", "NEW", 0.0, 0.0, 2.5, reviewCount = 0, lapseCount = 0, algorithmVersion = "", updatedAt = 1))

        db.openHelper.writableDatabase.execSQL("DELETE FROM concepts WHERE id = 'k'")
        assertNull(db.learning().item("i")!!.conceptId, "card survives concept re-analysis")

        db.books().delete("b")
        assertEquals(0, db.chapters().forBook("b").size)
        assertNull(db.learning().item("i"))
        assertNull(db.learning().state("i"))
    }

    @Test fun `dedupe key prevents duplicate cards`() = runTest {
        val db = graph.db
        db.books().insert(BookEntity("b", "u", "T", sourceType = SourceType.TXT, processingStatus = ProcessingStatus.READY, createdAt = 1, updatedAt = 1))
        val item = LearningItemEntity("i1", "u", "b", null, LearningItemType.CONCEPT, "p", "e", null, "[]", 3, null, "k1", createdAt = 1, updatedAt = 1)
        assertEquals(true, db.learning().insertItem(item) != -1L)
        assertEquals(-1L, db.learning().insertItem(item.copy(id = "i2")))
    }
}
