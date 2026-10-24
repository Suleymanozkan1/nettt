package com.bookmind.core.ai.study

import com.bookmind.core.TestFixtures
import com.bookmind.core.ai.AiResult
import com.bookmind.core.ai.AiTask
import com.bookmind.core.ai.JsonSanitizer
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.LocalHeuristicEngine
import com.bookmind.core.ai.LocalHeuristicEngine.ConceptInput
import com.bookmind.core.ai.MockAiProvider
import com.bookmind.core.ai.AiProvider
import com.bookmind.core.ai.AiRequest
import com.bookmind.core.ai.AiResponse
import com.bookmind.core.text.MarkdownParser
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The "Next lesson" modules: AI-free fallbacks, messy model output and the prompt budget. */
class StudyAiTest {
    private val engine = LocalHeuristicEngine()
    private val book = MarkdownParser.parse(TestFixtures.demoBookMarkdown)
    private val bookConcepts = book.chapters.flatMapIndexed { i, c ->
        engine.extractConcepts(c.text, 6).map { ConceptInput(it.title, it.explanation, i, it.quote) }
    }

    private fun lesson(i: Int) = Lesson(i, book.chapters[i].title, book.chapters[i].text, bookConcepts.filter { it.chapterIndex == i }, bookConcepts)

    private fun <T> AiResult<T>.value(): T = (this as AiResult.Ok).value

    @Test fun `every module works without a model, from the book's own sentences`() = runTest {
        val study = StudyAi(LearningAi({ null }))
        val l = lesson(1)

        val notes = study.smartNotes(l, "tr").value()
        assertTrue(notes.mainIdea.isNotBlank() && notes.keyPoints.isNotEmpty() && notes.concepts.isNotEmpty(), "$notes")
        notes.quotes.forEach { assertTrue(it in l.text, it) }

        val podcast = study.podcast(l, "tr").value()
        assertTrue(podcast.lines.size >= 6, "${podcast.lines}")
        assertEquals("A", podcast.lines.first().speaker)
        podcast.lines.zipWithNext().forEach { (a, b) -> assertTrue(a.speaker != b.speaker, "$a / $b") }

        val cards = study.flashcards(l, 6, "tr").value().cards
        assertTrue(cards.size in 3..6 && cards.all { it.front.isNotBlank() && it.back.isNotBlank() }, "$cards")

        val gaps = study.prerequisiteGaps(l, "tr").value().gaps
        assertTrue(gaps.isNotEmpty(), "$gaps")

        val ex = study.exercises(l, "tr").value()
        assertTrue(ex.cloze.isNotEmpty(), "$ex")
        ex.cloze.forEach { assertTrue("_____" in it.sentence && it.answer in it.options, "$it") }

        val mcq = study.multipleChoice(l, 4, "tr").value().questions
        assertTrue(mcq.isNotEmpty())
        mcq.forEach { assertTrue(it.answer in it.options.indices && it.options.size >= 2, "$it") }

        assertTrue(study.trueFalse(l, 4, "tr").value().items.isNotEmpty())

        val exam = study.writtenExam(l, 3, 3, "tr").value().questions
        assertEquals(3, exam.size, "$exam")
        val q = exam.first()
        val good = study.gradeWritten(q, q.idealAnswer, l, "tr").value()
        val bad = study.gradeWritten(q, "Bilmiyorum, hava güzel.", l, "tr").value()
        assertTrue(good.score > bad.score, "${good.score} vs ${bad.score}")
        assertEquals(0.0, study.gradeWritten(q, "  ", l, "tr").value().score)

        val first = study.oralTurn(q, "Bilmiyorum.", alreadyFollowedUp = false, "tr").value()
        val second = study.oralTurn(q, "Bilmiyorum.", alreadyFollowedUp = true, "tr").value()
        assertTrue(first.feedback.isNotBlank() && first.score < 70)
        assertNull(second.followUp)
    }

    @Test fun `gap cards explain the learner's mistakes`() = runTest {
        val study = StudyAi(LearningAi({ null }))
        val gaps = study.gapsFromMistakes(lesson(1), listOf(Mistake("Kararlılık nedir?", "Bir anının ne kadar kalıcı olduğudur.", "Bilmiyorum")), "tr").value().gaps
        assertEquals(1, gaps.size)
        assertTrue(gaps.single().explanation.isNotBlank())
    }

    @Test fun `messy model output is cleaned instead of thrown away`() = runTest {
        val provider = MockAiProvider(script = { r ->
            when (r.promptVersion) {
                LearningPrompts.Versions.PODCAST -> """
                    Elbette! İşte diyalog:
                    ```json
                    [{"speaker":"Sunucu","text":"Merhaba, bugün aralıklı tekrar."},{"speaker":"Uzman","text":"Tekrarları zamana yaymaktır."},
                     {"speaker":"Sunucu","text":"Neden işe yarar?"},{"speaker":"Uzman","text":"Unutmaya yakın hatırlamak izi güçlendirir."},]
                    ```
                """.trimIndent()
                LearningPrompts.Versions.MCQ -> """{"questions":[
                    {"question":"S1?","options":["a","b","c","d"],"answer":"C","explanation":"..."},
                    {"question":"S2?","options":["x","y","z","w"],"answer":"y","explanation":"..."}]}"""
                LearningPrompts.Versions.TRUE_FALSE -> """{"items":[{"statement":"A","isTrue":"Doğru","explanation":"."},{"statement":"B","isTrue":"Yanlış","explanation":"."}]}"""
                else -> r.payload["fallback"] ?: "{}"
            }
        })
        val study = StudyAi(LearningAi({ provider }, baseBackoffMs = 1))
        val l = lesson(1)
        val podcast = study.podcast(l, "tr")
        podcast as AiResult.Ok
        assertTrue(podcast.info.aiGenerated)
        assertEquals(listOf("A", "B", "A", "B"), podcast.value.lines.map { it.speaker })
        assertEquals(listOf(2, 1), study.multipleChoice(l, 4, "tr").value().questions.map { it.answer })
        assertEquals(listOf(true, false), study.trueFalse(l, 4, "tr").value().items.map { it.isTrue })
        assertTrue(provider.requests.all { it.task == AiTask.STUDY })
    }

    @Test fun `broken output falls back to the classic method with a warning`() = runTest {
        val provider = MockAiProvider(script = { "Üzgünüm, yardımcı olamam." })
        val r = StudyAi(LearningAi({ provider }, baseBackoffMs = 1)).flashcards(lesson(1), 5, "tr")
        r as AiResult.Ok
        assertFalse(r.info.aiGenerated)
        assertTrue(r.value.cards.isNotEmpty())
        assertTrue(r.warnings.any { "klasik yöntem" in it }, "${r.warnings}")
    }

    @Test fun `items that repeat the instructions are dropped`() = runTest {
        val provider = MockAiProvider(script = { r ->
            if (r.promptVersion == LearningPrompts.Versions.FLASHCARDS) """{"cards":[
                {"front":"Create 5 flashcards for the most important concepts and facts of this lesson.","back":"…"},
                {"front":"Kararlılık nedir?","back":"Bir anının ne kadar kalıcı olduğudur."},
                {"front":"Unutma eğrisi nedir?","back":"Bilginin zamanla nasıl kaybolduğunu gösterir."},
                {"front":"Aralıklı tekrar neden işe yarar?","back":"Unutmaya yakın hatırlamak izi güçlendirir."}]}"""
            else r.payload["fallback"] ?: "{}"
        })
        val cards = StudyAi(LearningAi({ provider }, baseBackoffMs = 1)).flashcards(lesson(1), 5, "tr")
        cards as AiResult.Ok
        assertTrue(cards.info.aiGenerated)
        assertEquals(3, cards.value.cards.size, "${cards.value.cards}")
        assertTrue(cards.value.cards.none { "flashcards" in it.front })
        // Feedback may quote the model answer; only the template counts as an echo.
        val q = ExamQuestion("Kararlılık nedir?", "Kararlılık bir anının ne kadar kalıcı olduğunu gösteren ölçüdür.", listOf("kalıcılık"))
        val grader = MockAiProvider(script = { """{"score":80,"missing":[],"feedback":"İyi: kararlılık bir anının ne kadar kalıcı olduğunu gösteren ölçüdür.","idealAnswer":""}""" })
        val g = StudyAi(LearningAi({ grader }, baseBackoffMs = 1)).gradeWritten(q, "Anının kalıcılığı.", lesson(1), "tr")
        g as AiResult.Ok
        assertTrue(g.info.aiGenerated && g.value.score == 80.0)
    }

    @Test fun `lesson text is packed into the provider's prompt budget`() = runTest {
        var seen: AiRequest? = null
        val small = object : AiProvider {
            override val id = "small"
            override fun inputBudgetFor(request: AiRequest) = 1_600
            override suspend fun complete(request: AiRequest): AiResponse { seen = request; return AiResponse("{}", "small") }
        }
        val long = Lesson(0, "Uzun", book.chapters.joinToString(" ") { it.text }, bookConcepts)
        StudyAi(LearningAi({ small }, baseBackoffMs = 1)).smartNotes(long, "tr")
        val p = seen!!
        assertTrue(p.system.length + p.user.length <= 1_600, "${p.system.length + p.user.length}")
        val packed = ContextPacker.pack(long.text, 1_200, engine)
        assertTrue(packed.length <= 1_200 && packed.isNotBlank())
        assertEquals("kısa", ContextPacker.pack("kısa", 1_200, engine))
    }

    @Test fun `json sanitizer repairs what small models write`() {
        assertEquals("{\"a\":[1,2]}", JsonSanitizer.sanitize("﻿```json\n{\"a\":[1,2,],}\n```"))
        assertEquals("{\"cards\":[{\"front\":\"x\"}]}", JsonSanitizer.sanitize("Here: [{\"front\":\"x\"}]", arrayKey = "cards"))
        assertEquals("{\"t\":\"a, ]b,}\"}", JsonSanitizer.sanitize("{\"t\":\"a, ]b,}\"}"))
        val cut = JsonSanitizer.sanitize("{\"cards\":[{\"front\":\"x\",\"back\":\"y\"},{\"front\":\"z\",\"ba")
        assertTrue(cut != null && cut.startsWith("{\"cards\":[{\"front\":\"x\""), "$cut")
        assertNull(JsonSanitizer.sanitize("hiç json yok"))
    }
}
