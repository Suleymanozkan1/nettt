package com.bookmind.core.ai

import com.bookmind.core.TestFixtures
import com.bookmind.core.quiz.QuizScorer
import com.bookmind.core.text.MarkdownParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalEngineTest {
    private val engine = LocalHeuristicEngine()
    private val book = MarkdownParser.parse(TestFixtures.demoBookMarkdown)
    private val chapters = book.chapters.mapIndexed { i, c -> LocalHeuristicEngine.ChapterInput(i, c.title, c.text) }
    private val concepts = chapters.flatMap { ch ->
        engine.extractConcepts(ch.text, 5).map { LocalHeuristicEngine.ConceptInput(it.title, it.explanation, ch.index, it.quote) }
    }

    @Test fun `definition patterns produce the expected concepts with explanations from the text`() {
        val titles = concepts.map { it.title.lowercase() }
        listOf("unutma eğrisi", "aralıklı tekrar", "aktif hatırlama", "bilişsel yük", "transfer").forEach { expected ->
            assertTrue(titles.any { it.contains(expected) }, "missing $expected in $titles")
        }
        val c = concepts.first { it.title.lowercase().contains("aralıklı tekrar") }
        assertTrue(c.explanation.startsWith("Aralıklı tekrar, bir bilgiyi"))
        assertNotNull(c.quote)
        assertTrue(OutputValidator.quoteOccursIn(c.quote!!, chapters[c.chapterIndex].text))
    }

    @Test fun `denir pattern is recognised`() {
        val found = engine.extractConcepts("Sınavdan önceki gece yapılan yoğun çalışmaya toplu çalışma denir. Bu yöntem kısa vadede işe yarar gibi görünür ama uzun vadede bilgi kaybolur.")
        assertTrue(found.any { it.title.equals("Toplu çalışma", ignoreCase = true) }, found.map { it.title }.toString())
    }

    @Test fun `key sentences are real sentences in book order`() {
        val ks = engine.keySentences(chapters[0].text, 3)
        assertEquals(3, ks.size)
        assertTrue(ks.zipWithNext().all { (a, b) -> a.start < b.start })
        ks.forEach { assertTrue(chapters[0].text.contains(it.text.take(30))) }
    }

    @Test fun `generated quiz questions are structurally valid and answerable`() {
        val qs = engine.quiz(concepts, chapters, 12, QuestionType.entries.toSet(), seed = 7)
        assertEquals(12, qs.size)
        qs.forEach { q -> assertNull(OutputValidator.questionIssue(q), "invalid: $q") }
        assertTrue(qs.map { it.type }.toSet().size >= 4)
        // Each objective question is answered correctly by its own answer key.
        qs.filter { QuizScorer.isObjective(it) && it.type != QuestionType.MATCHING }.forEach { q ->
            assertTrue(QuizScorer.score(q, q.answer).correct, "own key fails: $q")
        }
        val mcq = qs.first { it.type == QuestionType.MULTIPLE_CHOICE }
        assertEquals(1, mcq.options.count { it == mcq.answer })
    }

    @Test fun `quiz generation is deterministic for a seed`() {
        assertEquals(engine.quiz(concepts, chapters, 6, QuestionType.entries.toSet(), 3), engine.quiz(concepts, chapters, 6, QuestionType.entries.toSet(), 3))
    }

    @Test fun `heuristic grading rewards coverage and stays low-confidence`() {
        val model = "Aktif hatırlama, bilgiyi yeniden okumak yerine hafızadan geri çağırmaya çalışmaktır."
        val rubric = engine.keyPoints(model, "aktif hatırlama")
        val good = engine.grade(model, rubric, "Bilgiyi tekrar okumak yerine hafızadan geri çağırmaya çalışmak, yani kendimi test etmek.")
        val bad = engine.grade(model, rubric, "Kitabı hızlıca okumak.")
        val none = engine.grade(model, rubric, "bilmiyorum")
        assertTrue(good.score > bad.score)
        assertTrue(good.correct)
        assertTrue(!bad.correct)
        assertTrue(good.confidence < GradeResult.LOW_CONFIDENCE)
        assertEquals(0.0, none.score)
    }

    @Test fun `local summaries cite verifiable sources and are labelled`() {
        val doc = engine.summary(SummaryType.QUICK, "Öğrenmenin Bilimi", chapters, concepts, SummaryLength.SHORT)
        assertTrue(doc.sections.isNotEmpty())
        assertTrue(doc.notes.any { it.contains("yapay zekâ kullanılmadı") })
        doc.sections.flatMap { it.sources }.forEach { ref -> assertTrue(OutputValidator.quoteOccursIn(ref.quote!!, chapters[ref.chapterIndex].text)) }
        val map = engine.summary(SummaryType.CONCEPT_MAP, "B", chapters, concepts, SummaryLength.SHORT)
        assertTrue(map.conceptMap!!.nodes.size >= concepts.size)
        assertTrue(map.conceptMap!!.edges.none { it.causal })
        val detailed = engine.summary(SummaryType.DETAILED, "B", chapters, concepts, SummaryLength.MEDIUM)
        assertEquals(chapters.size, detailed.sections.size)
    }

    @Test fun `local summary is short relative to the book`() {
        val doc = engine.summary(SummaryType.DETAILED, "B", chapters, concepts, SummaryLength.LONG)
        val bookChars = chapters.sumOf { it.text.length }
        assertTrue(doc.plainText().length < bookChars * 0.7, "extractive summary must stay well below the source size")
    }

    @Test fun `tutor walks through diagnose, explain, check, transfer and wrap-up`() {
        val c = concepts.first { it.title.lowercase().contains("aktif hatırlama") }
        val ch = chapters[c.chapterIndex]
        val t0 = engine.teach(0, c, ch, null, null, "simple")
        assertNotNull(t0.question)
        val t1 = engine.teach(1, c, ch, "bilmiyorum", null, "simple")
        assertEquals(TeachingLevel.BEGINNER, t1.suggestedLevel)
        assertTrue(t1.message.contains(c.explanation))
        val t4 = engine.teach(4, c, ch, "Bilgiyi okumak yerine hafızadan geri çağırmaya çalışmaktır.", null, "simple")
        assertEquals(false, t4.expectsAnswer)
        assertNotNull(t4.understood)
    }

    @Test fun `genre guess`() {
        assertEquals(BookGenre.INFORMATIONAL, engine.guessGenre(TestFixtures.demoBookMarkdown))
        val novel = (1..30).joinToString("\n") { "— Nereye gidiyorsun, diye sordu Ali.\nAyşe pencereden baktı." }
        assertEquals(BookGenre.FICTION, engine.guessGenre(novel))
    }

    @Test fun `cross-book links only connect different books`() {
        val links = engine.links(mapOf("A" to listOf("Bilişsel yük", "Transfer"), "B" to listOf("Bilişsel yük", "Risk"), "C" to listOf("Transfer")))
        assertTrue(links.links.any { it.fromConcept == "Bilişsel yük" && it.relation == "same_concept" })
        assertTrue(links.links.all { it.confidence <= 0.7 })
    }

    @Test fun `verb forms that repeat in a chapter are not taken for concepts`() {
        val engine = LocalHeuristicEngine()
        val titles = MarkdownParser.parse(TestFixtures.demoBookMarkdown).chapters.flatMap { ch -> engine.extractConcepts(ch.text).map { it.title.lowercase() } }
        println(titles)
        listOf("öğrenilen", "kullanılabilmesidir", "öğrenilmiş").forEach { bad -> assertTrue(titles.none { it.startsWith(bad) }, "$bad in $titles") }
        assertTrue(titles.any { it.startsWith("transfer") } && titles.any { it.startsWith("aralıklı tekrar") }, titles.toString())
    }
}
