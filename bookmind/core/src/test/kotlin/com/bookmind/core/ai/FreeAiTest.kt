package com.bookmind.core.ai

import com.bookmind.core.TestFixtures
import com.bookmind.core.search.Bm25Index
import com.bookmind.core.text.MarkdownParser
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Free, local AI paths: the on-device model and the AI-free building blocks. */
class FreeAiTest {
    private val request = AiRequest(AiTask.SUMMARY, "summary/3", "sys", "user", maxOutputTokens = 300, temperature = 0.2)

    @Test fun `on-device provider sends system and user text, costs nothing and strips reasoning blocks`() = runTest {
        var seen: OnDevicePrompt? = null
        val p = OnDeviceProvider("gemma-4-e2b", 4_096) { prompt -> seen = prompt; "<think>hmm</think>\n{\"ok\":true}" }
        val r = p.complete(request)
        assertEquals("{\"ok\":true}", r.text)
        assertEquals(0L, r.costMicros)
        assertTrue(p.free)
        assertEquals("on-device:gemma-4-e2b", p.id)
        assertTrue(seen!!.system.startsWith("sys") && seen!!.user == "user" && seen!!.maxOutputTokens == 300)
        // A long answer leaves less room for the prompt, and never takes more than half the context.
        val long = request.copy(maxOutputTokens = 4_500)
        assertTrue(p.inputBudgetFor(long) < p.inputBudgetFor(request))
        assertTrue(p.inputBudgetFor(long) >= OnDeviceProvider.budgetChars(4_096, 2_048))
        val failing = OnDeviceProvider("m", 4_096) { throw IllegalStateException("model file missing") }
        assertFailsWith<AiException.Server> { failing.complete(request) }
        assertFailsWith<AiException.InvalidOutput> { OnDeviceProvider("m", 4_096) { "  " }.complete(request) }
        assertTrue(OnDeviceProvider.budgetChars(4_096) in 7_000..8_000)
        assertTrue(OnDeviceProvider.budgetChars(512) > 0)
    }

    @Test fun `prompts are trimmed to a small model's budget by shortening book text only`() {
        val long = "x".repeat(10_000)
        val req = AiRequest(AiTask.SUMMARY, "v", "SYSTEM", "Instructions first.\n<book_text source=\"a\">\n$long\n</book_text>\nReturn JSON: {}")
        val fitted = LearningAi.fitToBudget(req, 3_000)
        assertTrue(fitted.system.length + fitted.user.length <= 3_000, "${fitted.user.length}")
        assertTrue(fitted.user.startsWith("Instructions first.") && fitted.user.endsWith("Return JSON: {}"))
        assertTrue(fitted.user.contains("[…]"))
        assertEquals(req, LearningAi.fitToBudget(req, 100_000))
    }

    @Test fun `a failing local model falls back to the on-device engine with a visible warning, never to a paid service`() = runTest {
        val local = OnDeviceProvider("m", 4_096) { throw IllegalStateException("crashed") }
        val chapter = LocalHeuristicEngine.ChapterInput(0, "Unutma Eğrisi", MarkdownParser.parse(TestFixtures.demoBookMarkdown).chapters[0].text)
        val r = LearningAi({ local }, baseBackoffMs = 1).analyzeChapter(LearningAi.Book("K", null, "tr", BookGenre.INFORMATIONAL), chapter, chapter.text, "")
        r as AiResult.Ok
        assertFalse(r.info.aiGenerated)
        assertTrue(r.warnings.single().contains("klasik yöntem"))
    }

    private val book = MarkdownParser.parse(TestFixtures.demoBookMarkdown)
    private val passages = Bm25Index.passages(book.chapters.mapIndexed { i, c -> Triple(i, c.title, c.text) })

    @Test fun `bm25 finds the passage that answers a question, with Turkish suffixes`() {
        val index = Bm25Index(passages)
        val hit = index.search("Kararlılık nedir, algoritmalar neyi hesaplar?").first()
        assertEquals(1, hit.passage.chapterIndex, hit.passage.text)
        assertTrue(hit.passage.text.contains("Kararlılık"))
        assertEquals(2, index.search("tanıdıklık hissi yanılsaması").first().passage.chapterIndex)
        assertTrue(index.search("kuantum kromodinamiği").isEmpty())
        passages.forEach { p -> assertEquals(p.text, book.chapters[p.chapterIndex].text.substring(p.start, p.end).trim()) }
    }

    @Test fun `extractive answers quote the book and say so when the book has no answer`() {
        val engine = LocalHeuristicEngine()
        val hits = Bm25Index(passages).search("Test etkisi nedir?").map { it.passage }
        val a = engine.answer("Test etkisi nedir?", hits)
        assertTrue(a.inBook && a.citations.isNotEmpty())
        assertTrue(a.answer.contains("Test etkisi"), a.answer)
        assertFalse(engine.answer("Uzay yolculuğu nasıl yapılır?", emptyList()).inBook)
        assertTrue(OutputValidator.answer(BookAnswer("x", citations = listOf(0, 99)), hits).value.citations == listOf(0))
    }

    @Test fun `faithfulness check flags invented numbers and names but accepts those in the book`() {
        val src = listOf("Hermann Ebbinghaus on dokuzuncu yüzyılda hafızasını ölçtü. Katılımcıların yüzde 90'ı hatırladı; 1885 yılında yayımlandı.")
        val ok = Faithfulness.check(listOf("Bu deneyde Ebbinghaus 1885'te sonuçlarını yayımladı ve oran 90 idi."), src)
        assertTrue(ok.clean, ok.toString())
        // Case endings on names are not "new" names.
        assertTrue(Faithfulness.check(listOf("Bu, Ebbinghaus'un bulgusudur ve Ebbinghaus’ın deneyi de öyle."), src).clean)
        val bad = Faithfulness.check(listOf("Bunu 1923'te Sigmund Freud doğruladı ve 3 deney yaptı."), src)
        assertEquals(listOf("1923", "3"), bad.unsupportedNumbers)
        assertEquals(listOf("Sigmund", "Freud"), bad.unsupportedNames)
    }

    @Test fun `textrank picks central sentences without near-duplicates, deterministically`() {
        val engine = LocalHeuristicEngine()
        val text = book.chapters[0].text + "\n\nUnutma eğrisi, öğrenilen bir bilginin zaman içinde ne kadar hızlı kaybolduğunu gösteren bir modeldir."
        val ks = engine.keySentences(text, 4)
        assertEquals(ks, engine.keySentences(text, 4))
        assertEquals(1, ks.count { it.text.startsWith("Unutma eğrisi, öğrenilen") }, "duplicate sentence chosen twice")
        assertTrue(ks.zipWithNext().all { (a, b) -> a.start < b.start })
    }

    @Test fun `output cut off at the token limit keeps every complete element`() {
        val cut = """{"type":"quick","title":"T","sections":[{"heading":"A","body":"x, y","bullets":["1","2"]},{"heading":"B","body":"unfinish"""
        assertEquals("""{"type":"quick","title":"T","sections":[{"heading":"A","body":"x, y","bullets":["1","2"]}]}""", AiJson.repairTruncated(cut))
        val doc = AiJson.decode(SummaryDocument.serializer(), "Here: " + cut)
        assertEquals(listOf("A"), doc.sections.map { it.heading })
        assertEquals("""{"a":1}""", AiJson.repairTruncated("""{"a":1,"b":{"c":"""))
        assertEquals("""{"q":[]}""", AiJson.repairTruncated("""{"q":[{"type":"x","prom"""))
        assertEquals(null, AiJson.repairTruncated("""{"a":"no comma yet"""))
        assertEquals("""{"done":true}""", AiJson.repairTruncated("""{"done":true} trailing"""))
    }

    @Test fun `loosely named question types are mapped and one unreadable question does not sink the quiz`() {
        val raw = """{"questions":[
            {"type":"pairs","prompt":"Eşleştir","pairs":[{"left":"a","right":"b"}]},
            {"type":"Multiple Choice","prompt":"Hangisi?","options":["A","B","C","D"],"answer":"A"},
            {"type":"true/false","prompt":"Doğru mu?","answer":"true"},
            {"type":"riddle","prompt":"?"},
            {"prompt":"type missing"}]}"""
        val quiz = AiJson.decode(QuizDraft.serializer(), raw)
        assertEquals(listOf(QuestionType.MATCHING, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE), quiz.questions.map { it.type })
        assertEquals("fill_blank", LenientQuestionList.normalizeType("Fill in the blank"))
    }

    @Test fun `concepts a small model put under keyPoints are recovered`() {
        val raw = """{"summary":"s","keyPoints":[{"title":"Unutma Eğrisi","explanation":"Bilginin kaybolma hızı.","importance":5},"Düz madde"]}"""
        val a = AiJson.decode(LenientChapterAnalysis, raw)
        assertEquals(listOf("Unutma Eğrisi"), a.concepts.map { it.title })
        assertEquals(listOf("Unutma Eğrisi: Bilginin kaybolma hızı.", "Düz madde"), a.keyPoints)
        val normal = AiJson.decode(LenientChapterAnalysis, """{"summary":"s","keyPoints":["a"],"concepts":[{"title":"T","explanation":"e"}]}""")
        assertEquals(listOf("a"), normal.keyPoints); assertEquals(listOf("T"), normal.concepts.map { it.title })
    }
}
