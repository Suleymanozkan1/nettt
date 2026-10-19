package com.bookmind.core.ai

import com.bookmind.core.TestFixtures
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.bookmind.core.search.Bm25Index
import com.bookmind.core.text.MarkdownParser
import com.bookmind.core.text.Sentences
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Runs the real pipeline on a phone model file (.litertlm) with the desktop build of the same LiteRT-LM engine
 * the app uses, and compares summary quality of the old frequency extractor, the TextRank extractor and the
 * model on the same books.
 * Opt-in: BOOKMIND_LITERTLM_MODEL=/path/gemma-4-E2B-it.litertlm [BOOKMIND_LITERTLM_CONTEXT=4096] ./gradlew :core:test
 * Writes core/build/reports/local-model-evaluation-<model>.md.
 */
class LocalModelEvaluationTest {
    private val path = System.getenv("BOOKMIND_LITERTLM_MODEL")
    private val context = System.getenv("BOOKMIND_LITERTLM_CONTEXT")?.toIntOrNull() ?: 4_096
    private val model = path?.let { File(it).nameWithoutExtension } ?: "model"

    /** Same engine settings as the app's OnDeviceModels: CPU, one fresh conversation per request, no thinking. */
    private class Runner(path: String, context: Int) : AutoCloseable {
        private val engine = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = context)).also {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            it.initialize()
        }

        fun generate(p: OnDevicePrompt): String {
            val config = ConversationConfig(
                systemInstruction = Contents.of(p.system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = p.temperature),
                maxOutputToken = p.maxOutputTokens,
                // Answers are short and structured; a reasoning preamble would only cost time and battery.
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            return engine.createConversation(config).use { c ->
                c.sendMessage(p.user)
                    .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            }
        }

        override fun close() = engine.close()
    }

    private data class Case(val lang: String, val markdown: String, val keyConcepts: List<String>)

    private val cases = listOf(
        Case("tr", TestFixtures.demoBookMarkdown, listOf("unutma eğrisi", "aralıklı tekrar", "aktif hatırlama", "bilişsel yük", "transfer")),
        Case("en", javaClass.getResource("/science_of_learning.md")!!.readText(), listOf("forgetting curve", "spaced repetition", "active recall", "cognitive load", "transfer")),
    )

    private data class Row(val method: String, val words: Int, val coverage: Int, val redundancy: Double, val unsupported: Int, val quotesOk: String, val ms: Long)

    private fun metrics(method: String, text: String, sources: List<String>, concepts: List<String>, quotes: Pair<Int, Int>?, ms: Long): Row {
        val sentences = Sentences.split(text).map { Lexicon.stems(it.text) }.filter { it.isNotEmpty() }
        val pairs = sentences.indices.flatMap { i -> (i + 1 until sentences.size).map { j -> i to j } }
        val redundancy = if (pairs.isEmpty()) 0.0 else pairs.map { (i, j) ->
            sentences[i].intersect(sentences[j]).size.toDouble() / sentences[i].union(sentences[j]).size
        }.average()
        val folded = com.bookmind.core.text.TextNormalizer.asciiFold(text)
        val coverage = concepts.count { c -> Lexicon.stems(c).all { s -> folded.contains(s.take(5)) } }
        return Row(method, Lexicon.words(text).size, coverage, redundancy, Faithfulness.check(listOf(text), sources).items.size,
            quotes?.let { "${it.first}/${it.second}" } ?: "—", ms)
    }

    @Test fun `free local model runs every task and is compared with the extractive engines`() = runBlocking {
        assumeTrue("set BOOKMIND_LITERTLM_MODEL to run a real on-device model", path != null)
        val load = System.currentTimeMillis()
        val runner = Runner(path!!, context)
        val report = StringBuilder("# Telefondaki model değerlendirmesi\n\nModel: `$model`, bağlam $context token, LiteRT-LM (CPU), yükleme ${System.currentTimeMillis() - load} ms\n\n")
        val engine = LocalHeuristicEngine()
        File("build/reports").mkdirs()
        val out = File("build/reports/local-model-evaluation-$model.md")
        val progress = File("build/reports/local-model-evaluation-$model.log").apply { writeText("") }
        val raw = File("build/reports/local-model-evaluation-$model-raw.txt").apply { writeText("") }
        var calls = 0
        val provider = OnDeviceProvider(model, context) { p ->
            val t0 = System.currentTimeMillis()
            runner.generate(p).also {
                progress.appendText("call ${++calls}: ${p.system.length + p.user.length} chars in, ${it.length} out, ${System.currentTimeMillis() - t0} ms\n")
                raw.appendText("===== call $calls (max ${p.maxOutputTokens} tokens)\n${p.user.take(300)}\n----- output\n$it\n")
            }
        }
        val langs = (System.getenv("BOOKMIND_EVAL_LANGS") ?: "tr,en").split(',')
        for (case in cases.filter { it.lang in langs }) {
            val book = MarkdownParser.parse(case.markdown, case.lang)
            val chapters = book.chapters.mapIndexed { i, c -> LocalHeuristicEngine.ChapterInput(i, c.title, c.text) }
            val sources = chapters.map { it.text }
            // One corrective retry for invalid JSON; there is no network to retry.
            val ai = LearningAi({ provider }, baseBackoffMs = 500, maxAttempts = 2)
            val info = LearningAi.Book(book.title ?: "Book", null, case.lang, BookGenre.INFORMATIONAL)
            val rows = mutableListOf<Row>()

            var t = System.currentTimeMillis()
            val old = chapters.joinToString(" ") { ch -> engine.keySentencesByFrequency(ch.text, 2).joinToString(" ") { it.text } }
            rows += metrics("Eski: frekans (yapay zekâsız)", old, sources, case.keyConcepts, null, System.currentTimeMillis() - t)
            t = System.currentTimeMillis()
            val textRank = chapters.joinToString(" ") { ch -> engine.keySentences(ch.text, 2).joinToString(" ") { it.text } }
            rows += metrics("Yeni: TextRank (yapay zekâsız)", textRank, sources, case.keyConcepts, null, System.currentTimeMillis() - t)

            // Map step: one analysis per chapter by the local model; reduce step: the book summary from the digests.
            t = System.currentTimeMillis()
            val analyses = chapters.map { ch -> ai.analyzeChapter(info, ch, ch.text, "") }
            val digests = analyses.mapIndexed { i, r -> i to ((r as? AiResult.Ok)?.value?.summary ?: "") }.toMap()
            val concepts = analyses.flatMapIndexed { i, r ->
                (r as? AiResult.Ok)?.value?.concepts.orEmpty().map { LocalHeuristicEngine.ConceptInput(it.title, it.explanation, i, it.quote) }
            }
            val summary = ai.summarize(info, SummaryType.DETAILED, SummaryLength.SHORT, chapters, digests, concepts)
            val llmMs = System.currentTimeMillis() - t
            summary as AiResult.Ok
            val doc = summary.value
            val llmText = (doc.sections.flatMap { listOf(it.body) + it.bullets } + doc.ideas.map { it.explanation }).joinToString(" ")
            val refs = doc.sections.flatMap { it.sources }
            rows += metrics("Telefon modeli ($model, map-reduce)", llmText, sources, case.keyConcepts,
                refs.count { it.verified && it.quote != null } to refs.count { it.quote != null || !it.verified }, llmMs)

            assertTrue(analyses.all { it is AiResult.Ok })
            val aiShare = analyses.count { (it as AiResult.Ok).info.aiGenerated }

            t = System.currentTimeMillis()
            val quiz = ai.generateQuiz(info, chapters, concepts.ifEmpty { chapters.flatMap { ch -> engine.extractConcepts(ch.text, 3).map { LocalHeuristicEngine.ConceptInput(it.title, it.explanation, ch.index, it.quote) } } },
                4, 3, setOf(QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE, QuestionType.SHORT_ANSWER), false, 1)
            val quizMs = System.currentTimeMillis() - t
            t = System.currentTimeMillis()
            val question = if (case.lang == "tr") "Aralıklı tekrar neden işe yarar?" else "Why does spaced repetition work?"
            val passages = Bm25Index(Bm25Index.passages(chapters.map { Triple(it.index, it.title, it.text) })).search(question, 4).map { it.passage }
            val answer = ai.answer(question, passages, case.lang)
            val answerMs = System.currentTimeMillis() - t

            report.append("## ${book.title} (${case.lang})\n\n")
            report.append("| Yöntem | Kelime | Kavram kapsamı (/${case.keyConcepts.size}) | Tekrar (Jaccard ort.) | Kitapta olmayan sayı/isim | Doğrulanan alıntı | Süre |\n|---|---|---|---|---|---|---|\n")
            rows.forEach { r -> report.append("| ${r.method} | ${r.words} | ${r.coverage} | ${"%.3f".format(r.redundancy)} | ${r.unsupported} | ${r.quotesOk} | ${r.ms} ms |\n") }
            report.append("\n- Bölüm analizi: ${aiShare}/${chapters.size} bölüm modelle, kalan yapay zekâsız yöntemle (geçersiz çıktı → otomatik yerel yedek).\n")
            report.append("- Özet uyarıları: ${summary.warnings.ifEmpty { listOf("yok") }.joinToString("; ")}\n")
            report.append("- Quiz: ${(quiz as? AiResult.Ok)?.let { "${it.value.size} geçerli soru, yapay zekâ: ${it.info.aiGenerated}" } ?: "başarısız"} ($quizMs ms)\n")
            report.append("- Soru-cevap (\"$question\"): ${(answer as? AiResult.Ok)?.let { "yapay zekâ: ${it.info.aiGenerated}, kaynak: ${it.value.citations}, kitapta: ${it.value.inBook}" } ?: "başarısız"} ($answerMs ms)\n")
            (answer as? AiResult.Ok)?.let { report.append("\n> ${it.value.answer.replace("\n", " ")}\n") }
            report.append("\nModel özetinden:\n\n> ${llmText.take(600).replace("\n", " ")}…\n\n")
            out.writeText(report.toString())
        }
        runner.close()
        out.writeText(report.toString())
        println(report)
    }
}
