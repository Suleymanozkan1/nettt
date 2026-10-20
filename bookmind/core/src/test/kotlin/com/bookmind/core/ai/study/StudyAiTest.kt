package com.bookmind.core.ai.study

import com.bookmind.core.TestFixtures
import com.bookmind.core.ai.AiResult
import com.bookmind.core.ai.AiTask
import com.bookmind.core.ai.JsonSanitizer
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.LocalHeuristicEngine
import com.bookmind.core.ai.LocalHeuristicEngine.ConceptInput
import com.bookmind.core.ai.MockAiProvider
import com.bookmind.core.ai.OnDevicePrompt
import com.bookmind.core.ai.OnDeviceProvider
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

    @Test fun `lesson text is packed into 60 percent of a small context`() = runTest {
        var seen: OnDevicePrompt? = null
        val small = OnDeviceProvider("tiny", 1_024) { seen = it; "{}" }
        val long = Lesson(0, "Uzun", book.chapters.joinToString(" ") { it.text }, bookConcepts)
        StudyAi(LearningAi({ small }, baseBackoffMs = 1)).smartNotes(long, "tr")
        val p = seen!!
        assertTrue(p.system.length + p.user.length <= 1_024 * 0.6 * OnDeviceProvider.CHARS_PER_TOKEN, "${p.system.length + p.user.length}")
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
