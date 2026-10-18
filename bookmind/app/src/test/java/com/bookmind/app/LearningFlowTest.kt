package com.bookmind.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookmind.app.data.db.LearningItemType
import com.bookmind.app.data.repo.QuizConfig
import com.bookmind.app.data.repo.QuizMode
import com.bookmind.core.ai.QuestionType
import com.bookmind.core.ai.SummaryDocument
import com.bookmind.core.ai.SummaryLength
import com.bookmind.core.ai.SummaryType
import com.bookmind.core.ai.TeachingLevel
import com.bookmind.core.srs.Rating
import com.bookmind.app.util.AppJson
import com.bookmind.app.util.parseRefs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LearningFlowTest {
    private val graph = TestGraph()
    @After fun tearDown() = graph.close()

    @Test fun `review queue introduces new cards, schedules them and records history`() = runTest {
        graph.demoBook()
        graph.settings.update { it.copy(newItemsPerDay = 5) }
        val queue = graph.reviews.todayQueue()
        assertEquals(5, queue.size)
        val item = queue.first()
        val outcome = graph.reviews.submit(item, Rating.GOOD, correct = true, response = "cevap", responseTimeMs = 4_000)
        assertEquals(Rating.GOOD, outcome.rating)
        assertEquals(4 * 86_400_000L, outcome.intervalMs)
        val state = graph.db.learning().state(item.itemId)!!
        assertEquals(1, state.reviewCount)
        assertEquals(graph.clock.now() + 4 * 86_400_000L, state.nextReviewAt)
        assertEquals(1, graph.db.learning().logsForItem(item.itemId).size)
        assertTrue(outcome.xp > 0)
        // The daily new-card budget is shared across sessions of the same day.
        assertEquals(4, graph.reviews.todayQueue().size)
        // Four days later the card is due again.
        graph.clock.advanceDays(4.0)
        assertTrue(graph.reviews.todayQueue().any { it.itemId == item.itemId && it.reviewCount == 1 })
    }

    @Test fun `wrong answers lapse and come back the same day`() = runTest {
        graph.demoBook()
        val item = graph.reviews.todayQueue().first()
        val first = graph.reviews.submit(item, Rating.GOOD, correct = true)
        graph.clock.advanceDays(4.0)
        val due = graph.reviews.todayQueue().first { it.itemId == item.itemId }
        val lapse = graph.reviews.submit(due, Rating.EASY, correct = false)
        assertEquals(Rating.AGAIN, lapse.rating, "objective evidence beats self-rating")
        assertTrue(lapse.intervalMs < first.intervalMs)
        assertEquals(1, graph.db.learning().state(item.itemId)!!.lapseCount)
    }

    @Test fun `summaries are generated, stored with sources and editable`() = runTest {
        val id = graph.demoBook()
        val s = graph.study.generateSummary(id, SummaryType.QUICK, SummaryLength.SHORT)
        val doc = AppJson.decodeFromString(SummaryDocument.serializer(), s.content)
        assertTrue(doc.sections.isNotEmpty())
        assertTrue(parseRefs(s.sourceReferences).all { it.verified })
        graph.study.editSummary(s.id, "Benim özetim")
        assertEquals("Benim özetim", graph.db.summaries().forBook(id).first().userEditedText)
        SummaryType.entries.filter { it != SummaryType.CHAPTER }.forEach { t -> graph.study.generateSummary(id, t, SummaryLength.MEDIUM) }
        val chapterId = graph.db.chapters().forBook(id).first().id
        assertNotNull(graph.study.generateSummary(id, SummaryType.CHAPTER, SummaryLength.MEDIUM, chapterId).chapterId)
    }

    @Test fun `quiz is created, graded and wrong answers enter the review queue`() = runTest {
        val id = graph.demoBook()
        val created = graph.quizzes.create(id, QuizConfig(count = 6, types = listOf(QuestionType.MULTIPLE_CHOICE, QuestionType.FILL_BLANK, QuestionType.TRUE_FALSE, QuestionType.EXPLAIN_OWN_WORDS)))
        val questions = graph.quizzes.questions(created.quizId)
        assertEquals(6, questions.size)
        val attempt = graph.quizzes.startAttempt(created.quizId)
        // Answer the first question correctly with its own key and the second wrongly.
        val q0 = graph.quizzes.decode(questions[0].payload)!!
        val right = graph.quizzes.answer(attempt.attemptId, questions[0], if (q0.type == QuestionType.EXPLAIN_OWN_WORDS) q0.answer else q0.answer, 3_000)
        assertTrue(right.correct, "own key must be accepted: $q0")
        val wrong = graph.quizzes.answer(attempt.attemptId, questions[1], "tamamen alakasız bir cevap xyz", 3_000)
        assertFalse(wrong.correct)
        val score = graph.quizzes.finish(attempt)
        assertTrue(score in 0.1..0.5)
        val wrongCard = graph.db.learning().allItems(graph.users.userId()).single { it.itemType == LearningItemType.WRONG_ANSWER }
        assertTrue(graph.reviews.todayQueue().any { it.itemId == wrongCard.id }, "wrong answer is due immediately")

        val retry = graph.quizzes.create(id, QuizConfig(mode = QuizMode.WRONG_ONLY))
        assertEquals(1, graph.quizzes.questions(retry.quizId).size)
        val (a, rows) = graph.quizzes.results(attempt.attemptId)
        assertNotNull(a!!.completedAt)
        assertEquals(2, rows.count { it.answer != null })
    }

    @Test fun `tutor session runs to completion and adds the concept to reviews`() = runTest {
        val id = graph.demoBook()
        val concept = graph.db.concepts().forBook(id).first { it.title.contains("Aktif", ignoreCase = true) }
        val session = graph.study.startTeaching(id, concept.id, TeachingLevel.BASIC, "simple")
        val answers = listOf("bilmiyorum", "hatırlama", "Sınava çalışırken kendimi test ederim.", "Okumak yerine hafızadan geri çağırmaya çalışmak.")
        var last = graph.study.reply(id, concept.id, session, answers[0], TeachingLevel.BASIC, "simple")
        answers.drop(1).forEach { last = graph.study.reply(id, concept.id, session, it, TeachingLevel.BASIC, "simple") }
        assertFalse(last.expectsAnswer)
        assertNotNull(graph.db.sessions().get(session)!!.completedAt)
        assertEquals(9, graph.db.sessions().messages(session).size) // opening turn + 4 × (answer + reply)
    }

    @Test fun `stories from the local engine are labelled as book examples`() = runTest {
        val id = graph.demoBook()
        val concept = graph.db.concepts().forBook(id).first()
        val story = graph.study.generateStory(id, concept.id, "short story")
        assertTrue(story.fromBook)
        assertTrue(story.body.isNotBlank())
    }

    @Test fun `notes become review cards`() = runTest {
        val id = graph.demoBook()
        val chapter = graph.db.chapters().forBook(id).first()
        val noteId = graph.notes.save(null, id, chapter.id, "Ebbinghaus kendi hafızasını ölçtü.", "Hermann Ebbinghaus on dokuzuncu yüzyılın sonunda…", null, addToReview = true)
        val item = graph.db.learning().allItems(graph.users.userId()).single { it.itemType == LearningItemType.USER_NOTE }
        assertEquals("note:$noteId", item.dedupeKey)
        graph.notes.delete(graph.notes.get(noteId)!!)
        assertTrue(graph.db.learning().item(item.id)!!.suspended)
    }

    @Test fun `study plan respects the daily budget and reschedules missed days`() = runTest {
        val id = graph.demoBook()
        graph.plans.create(id, 10)
        val plan = graph.db.plans().forBook(id)!!
        val tasks = graph.db.plans().tasks(plan.id)
        assertTrue(tasks.isNotEmpty())
        tasks.groupBy { it.epochDay }.values.forEach { assertTrue(it.sumOf { t -> t.minutes } <= 10) }
        graph.clock.advanceDays(3.0)
        graph.plans.refresh(id)
        val today = graph.clock.today().toEpochDay()
        assertTrue(graph.db.plans().tasks(plan.id).filter { !it.done }.all { it.epochDay >= today })
    }

    @Test fun `cross-book links connect shared concepts`() = runTest {
        graph.demoBook()
        val other = File(graph.context.cacheDir, "ikinci.md").apply {
            writeText("# Çalışma Teknikleri\n\n## Tekrar\n\n" + "Aralıklı tekrar, bir bilgiyi giderek uzayan aralıklarla yeniden çalışmaya dayanan bir öğrenme yöntemidir. ".repeat(6) +
                "\n\n## Not alma\n\n" + "Not almak bilgiyi düzenlemenin bir yoludur ve aralıklı tekrar ile birlikte kullanılabilir. ".repeat(6))
        }
        graph.processor.process(graph.library.importFile(Uri.fromFile(other)))
        val r = graph.graph.rebuild()
        assertTrue(r.links > 0)
        assertFalse(r.aiGenerated)
    }

    @Test fun `analytics and home separate measured from predicted values`() = runTest {
        graph.demoBook()
        graph.reviews.todayQueue().take(3).forEach { graph.reviews.submit(it, Rating.GOOD, correct = true) }
        val a = graph.insights.analytics()
        assertEquals(3, a.report.overall.total)
        assertFalse(a.report.overall.enoughData)
        assertNotNull(a.predictedRetention)
        assertEquals(14, a.forecast.size)
        val home = graph.insights.home()
        assertEquals(7, home.weekMinutes.size)
        assertEquals(1, home.continueBooks.size)
    }

    @Test fun `export contains every table and book files, delete wipes everything`() = runTest {
        graph.demoBook()
        val out = File(graph.context.cacheDir, "export.zip")
        graph.account.export(Uri.fromFile(out))
        ZipFile(out).use { zip ->
            assertNotNull(zip.getEntry("data/books.json"))
            assertNotNull(zip.getEntry("data/review_states.json"))
            assertTrue(zip.entries().toList().any { it.name.startsWith("files/") && it.name.endsWith("source.md") })
        }
        graph.account.deleteEverything()
        assertTrue(graph.db.books().all(graph.users.userId()).isEmpty())
        assertFalse(File(graph.context.filesDir, "books").exists())
    }

    @Test fun `reminders are scheduled in local time and cancelled when disabled`() = runTest {
        graph.users.userId()
        val prefs = graph.reminders.current()!!
        graph.reminders.save(prefs.copy(enabled = true, time = java.time.LocalTime.of(19, 0)))
        val infos = graph.workManager.getWorkInfosForUniqueWork(com.bookmind.app.work.WorkScheduler.REMINDER).get()
        assertEquals(1, infos.size)
        // 09:00 UTC = 12:00 in Istanbul → next 19:00 local is 7 hours away.
        assertEquals(7 * 3_600_000L, infos.single().initialDelayMillis)
        graph.reminders.save(prefs.copy(enabled = false))
        assertTrue(graph.workManager.getWorkInfosForUniqueWork(com.bookmind.app.work.WorkScheduler.REMINDER).get().all { it.state.isFinished })
    }
}
