package com.bookmind.core.i18n

import com.bookmind.core.ai.LocalHeuristicEngine
import com.bookmind.core.ai.QuestionType
import com.bookmind.core.ai.SummaryLength
import com.bookmind.core.ai.SummaryType
import com.bookmind.core.learning.StudyPlanner
import com.bookmind.core.quiz.QuizScorer
import com.bookmind.core.text.MarkdownParser
import com.bookmind.core.text.PlainTextParser
import com.bookmind.core.text.SourceRef
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Everything the core produces for the reader follows the requested output language; nothing mixes. */
class OutputLanguageTest {
    private val engine = LocalHeuristicEngine()
    private val book = MarkdownParser.parse(javaClass.getResource("/science_of_learning.md")!!.readText(), "en")
    private val chapters = book.chapters.mapIndexed { i, c -> LocalHeuristicEngine.ChapterInput(i, c.title, c.text) }
    private val concepts = chapters.flatMap { ch ->
        engine.extractConcepts(ch.text, 5).map { LocalHeuristicEngine.ConceptInput(it.title, it.explanation, ch.index, it.quote) }
    }
    private val turkishLetters = Regex("[çğışöüÇĞİŞÖÜ]")

    @Test fun `english demo book yields english concepts`() {
        val titles = concepts.map { it.title.lowercase() }
        listOf("forgetting curve", "spaced repetition", "active recall", "cognitive load", "transfer").forEach { expected ->
            assertTrue(titles.any { it.contains(expected) }, "missing $expected in $titles")
        }
        assertEquals("en", book.language)
    }

    @Test fun `summaries, questions and tutor turns are english for an english reader`() {
        val doc = engine.summary(SummaryType.QUICK, "The Science of Learning", chapters, concepts, SummaryLength.MEDIUM, "en")
        assertEquals("The Science of Learning – Quick summary", doc.title)
        assertEquals("Key idea", doc.sections.first().heading)
        val text = doc.title + doc.sections.joinToString { it.heading + it.body + it.bullets.joinToString() } + doc.notes.joinToString()
        assertFalse(turkishLetters.containsMatchIn(text), text)

        val quiz = engine.quiz(concepts, chapters, 10, QuestionType.entries.toSet(), lang = "en")
        assertTrue(quiz.isNotEmpty())
        quiz.forEach { q -> assertFalse(turkishLetters.containsMatchIn(q.prompt + q.explanation), q.prompt) }

        val turn = engine.teach(0, concepts.first(), chapters.first(), null, null, "simple", "en")
        assertTrue(turn.message.startsWith("Let's start"))
    }

    @Test fun `turkish stays the default`() {
        val doc = engine.summary(SummaryType.QUICK, "Kitap", chapters, concepts, SummaryLength.SHORT)
        assertEquals("Temel fikir", doc.sections.first().heading)
        assertEquals("Doğru.", QuizScorer.score(com.bookmind.core.ai.QuestionDraft(QuestionType.TRUE_FALSE, "x", answer = "true"), "true").feedback)
    }

    @Test fun `grading, plans, parser fallbacks and source labels follow the language`() {
        val q = com.bookmind.core.ai.QuestionDraft(QuestionType.TRUE_FALSE, "x", answer = "true")
        assertEquals("Correct answer: True", QuizScorer.score(q, "false", "en").feedback)
        assertEquals("Correct.", engine.grade("a b c", emptyList(), "zzz", listOf("zzz"), "en").feedback)
        val plan = StudyPlanner.build(listOf(StudyPlanner.ChapterInfo(0, "One", 600, 4)), 20, LocalDate.of(2026, 1, 1), lang = "en")
        assertTrue(plan.any { it.title == "Learn: One" })
        assertTrue(plan.any { it.title == "Real-life practice task" })
        val parsed = PlainTextParser.parse("word ".repeat(400), "en")
        assertEquals("Part 1", parsed.chapters.first().title)
        assertEquals("Chapter 2 – Spaced Repetition, p. 4", SourceRef(1, "Spaced Repetition", pageStart = 4).label("en"))
        assertEquals("Bölüm 2 – Aralıklı Tekrar, s. 4", SourceRef(1, "Aralıklı Tekrar", pageStart = 4).label())
    }
}
