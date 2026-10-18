package com.bookmind.core.ai

import com.bookmind.core.text.Sentences
import com.bookmind.core.text.SourceRef
import com.bookmind.core.text.TextNormalizer
import kotlin.math.ln
import kotlin.math.min

/**
 * Deterministic on-device engine used when no AI provider is configured or reachable. It never invents
 * content: summaries are short selections of the book's own sentences (with sources), concepts come from
 * definition patterns and term frequency, questions are built from the text. Everything it produces is
 * labelled as "local" (not AI) in [GenerationInfo].
 */
class LocalHeuristicEngine {

    data class ChapterInput(val index: Int, val title: String, val text: String, val pageStart: Int? = null)

    data class ConceptInput(
        val title: String,
        val explanation: String,
        val chapterIndex: Int,
        val quote: String? = null,
    )

    // ---------- Sentences ----------

    /** Ranks sentences by content-word frequency, favouring paragraph openings, penalising very short/long ones. */
    fun keySentences(text: String, max: Int): List<Sentences.Span> {
        val sentences = Sentences.split(text).filter { it.text.length in 40..400 && Lexicon.contentWords(it.text).size >= 4 }
        if (sentences.isEmpty()) return emptyList()
        val freq = HashMap<String, Int>()
        sentences.forEach { s -> Lexicon.contentWords(s.text).map(Lexicon::stem).toSet().forEach { freq.merge(it, 1, Int::plus) } }
        val paragraphStarts = buildSet {
            add(0)
            Regex("\\n\\s*\\n").findAll(text).forEach { add(it.range.last + 1) }
        }
        val scored = sentences.mapIndexed { i, s ->
            val stems = Lexicon.contentWords(s.text).map(Lexicon::stem)
            val base = stems.sumOf { ln(1.0 + (freq[it] ?: 0)) } / (stems.size + 4)
            val positionBonus = if (paragraphStarts.any { it in (s.start - 2)..s.start }) 0.15 else 0.0
            val earlyBonus = if (i < 3) 0.1 else 0.0
            Triple(s, base + positionBonus + earlyBonus, i)
        }
        return scored.sortedByDescending { it.second }.take(max).sortedBy { it.third }.map { it.first }
    }

    // ---------- Concepts ----------

    private val trDefinition = Regex("^([\\p{Lu}][\\p{L}'’-]*(?:\\s[\\p{L}'’-]+){0,3}),\\s.{10,}?(?:d|t)(?:ı|i|u|ü)r\\.?$")
    private val trDenir = Regex("([\\p{L}'’-]+(?:\\s[\\p{L}'’-]+)?)\\s(?:denir|denilir|adı verilir|olarak adlandırılır|diye adlandırılır)")
    private val enDefinition = Regex("^(?:The |An? )?([A-Z][\\w-]*(?:\\s[\\w-]+){0,3})\\s(?:is|are|refers to|means|describes)\\s(?:a|an|the|when|how|that|any)\\b", RegexOption.IGNORE_CASE)
    private val enCalled = Regex("(?:is|are)\\s(?:called|known as|termed)\\s(?:the\\s)?([\\w-]+(?:\\s[\\w-]+){0,2})")
    private val parenthetical = Regex("([\\p{L}][\\p{L}-]+(?:\\s[\\p{L}-]+){0,2})\\s\\(([^()]{3,40})\\)")

    fun extractConcepts(text: String, max: Int = 8): List<ConceptDraft> {
        val sentences = Sentences.split(text).filter { it.text.length in 25..500 }
        val candidates = LinkedHashMap<String, Candidate>()

        fun add(raw: String, score: Double, definition: Sentences.Span?) {
            val term = raw.trim().trim(',', '.', ';', ':', '"', '“', '”').replace(Regex("\\s+"), " ")
            val words = Lexicon.words(term)
            if (words.isEmpty() || words.size > 4 || term.length !in 3..60) return
            if (Lexicon.contentWords(term).isEmpty()) return
            val key = words.joinToString(" ") { Lexicon.stem(it) }
            val c = candidates.getOrPut(key) { Candidate(term) }
            c.score += score
            if (definition != null && c.definition == null) c.definition = definition
        }

        for (s in sentences) {
            trDefinition.matchEntire(s.text)?.let { add(it.groupValues[1], 6.0, s) }
            trDenir.find(s.text)?.let { add(it.groupValues[1], 6.0, s) }
            enDefinition.find(s.text)?.let { add(it.groupValues[1], 5.0, s) }
            enCalled.find(s.text)?.let { add(it.groupValues[1], 6.0, s) }
            parenthetical.findAll(s.text).forEach { add(it.groupValues[1], 1.5, null) }
        }

        // Frequent content bigrams and words.
        val bigrams = HashMap<String, Pair<String, Int>>()
        val unigrams = HashMap<String, Pair<String, Int>>()
        for (s in sentences) {
            val ws = Lexicon.words(s.text)
            for (i in ws.indices) {
                val w = TextNormalizer.foldCase(ws[i])
                if (w.length >= 5 && w !in Lexicon.stopwords && !w.all(Char::isDigit)) {
                    val k = Lexicon.stem(w)
                    unigrams[k] = (unigrams[k]?.first ?: w) to ((unigrams[k]?.second ?: 0) + 1)
                }
                if (i + 1 < ws.size) {
                    val a = TextNormalizer.foldCase(ws[i]); val b = TextNormalizer.foldCase(ws[i + 1])
                    if (a.length >= 4 && b.length >= 4 && a !in Lexicon.stopwords && b !in Lexicon.stopwords) {
                        val k = Lexicon.stem(a) + " " + Lexicon.stem(b)
                        bigrams[k] = (bigrams[k]?.first ?: "$a $b") to ((bigrams[k]?.second ?: 0) + 1)
                    }
                }
            }
        }
        bigrams.values.filter { it.second >= 3 }.forEach { (term, n) -> add(term, 1.2 * n, null) }
        unigrams.values.filter { it.second >= 4 }.forEach { (term, n) -> add(term, 0.6 * n, null) }

        // Frequency bonus for every candidate, then explanation from its best sentence.
        val ranked = candidates.values.onEach { c ->
            val hits = sentences.count { containsTerm(it.text, c.term) }
            c.score += min(hits, 10) * 0.4
        }.filter { c -> c.definition != null || sentences.any { containsTerm(it.text, c.term) } }
            .sortedByDescending { it.score }

        val chosen = mutableListOf<Candidate>()
        for (c in ranked) {
            val stems = Lexicon.words(c.term).map(Lexicon::stem).toSet()
            // Skip candidates that are part of an already chosen, higher-ranked term ("öğrenme" vs "öğrenme eğrisi").
            if (chosen.any { other -> Lexicon.words(other.term).map(Lexicon::stem).toSet().let { it.containsAll(stems) || stems.containsAll(it) } }) continue
            chosen += c
            if (chosen.size == max) break
        }
        return chosen.mapIndexed { rank, c ->
            val sentence = c.definition ?: sentences.filter { containsTerm(it.text, c.term) }.maxByOrNull { scoreExplanation(it.text) }!!
            ConceptDraft(
                title = displayTerm(c.term),
                explanation = sentence.text.take(400),
                importance = (5 - rank * 4 / max.coerceAtLeast(1)).coerceIn(2, 5),
                difficulty = 3,
                quote = OutputValidator.limitQuote(sentence.text),
                related = emptyList(),
            )
        }
    }

    private class Candidate(val term: String) {
        var score = 0.0
        var definition: Sentences.Span? = null
    }

    private fun scoreExplanation(s: String): Double = Lexicon.contentWords(s).size.toDouble() - (if (s.length > 250) 3 else 0)

    private fun displayTerm(term: String): String = term.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    fun containsTerm(text: String, term: String): Boolean = termRegex(term).containsMatchIn(text)

    /** Matches the term at a word start, allowing Turkish suffixes ("kavram" matches "kavramların"). */
    fun termRegex(term: String): Regex {
        val parts = Lexicon.words(term).map { Regex.escape(it) }
        return Regex("(?<![\\p{L}])" + parts.joinToString("\\s+") + "[\\p{L}'’]*", RegexOption.IGNORE_CASE)
    }

    // ---------- Analyses ----------

    fun chapterAnalysis(chapter: ChapterInput, maxConcepts: Int = 8): ChapterAnalysis {
        val key = keySentences(chapter.text, 4)
        val concepts = extractConcepts(chapter.text, maxConcepts)
        return ChapterAnalysis(
            summary = key.take(3).joinToString(" ") { it.text },
            keyPoints = key.map { it.text },
            concepts = concepts,
            people = properNames(chapter.text).take(8),
        )
    }

    fun bookAnalysis(title: String?, author: String?, lang: String?, chapters: List<ChapterInput>): BookAnalysis {
        val sample = chapters.joinToString("\n") { it.text.take(8_000) }
        val genre = guessGenre(sample)
        val opening = chapters.firstOrNull()?.let { keySentences(it.text, 1).firstOrNull()?.text }.orEmpty()
        val topWords = Lexicon.contentWords(sample).filter { it.length >= 5 }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(5).map { it.key }
        return BookAnalysis(
            title = title,
            author = author,
            language = lang,
            genre = genre,
            mainTopic = topWords.joinToString(", "),
            mainMessage = opening,
            learningGoals = chapters.take(8).map { it.title },
            keyPeople = if (genre == BookGenre.FICTION || genre == BookGenre.BIOGRAPHY) properNames(sample).take(8) else emptyList(),
        )
    }

    fun guessGenre(text: String): BookGenre {
        val lower = TextNormalizer.foldCase(text)
        val lines = text.lines().filter { it.isNotBlank() }
        val dialogue = lines.count { it.trimStart().startsWith("—") || it.trimStart().startsWith("–") || it.trimStart().startsWith("\"") || it.trimStart().startsWith("“") }
        val dialogueRatio = if (lines.isEmpty()) 0.0 else dialogue.toDouble() / lines.size
        fun count(vararg ws: String) = ws.sumOf { w -> Regex("\\b${Regex.escape(w)}").findAll(lower).count() }
        val technical = count("algoritma", "fonksiyon", "denklem", "formül", "teorem", "algorithm", "function", "equation", "theorem", "formula", "kod", "code") +
            Regex("[=+∑√]|\\d+\\s*[x×*/]\\s*\\d+").findAll(text).count() / 3
        val biography = count("doğdu", "öldü", "hayatı", "çocukluğu", "born", "died", "his life", "her life", "childhood") +
            Regex("\\b(1[5-9]\\d\\d|20[0-2]\\d)\\b").findAll(text).count() / 4
        val self = count("alışkanlık", "hedef", "başarı", "motivasyon", "habit", "goal", "success", "mindset", "yapabilirsin", "you can")
        return when {
            dialogueRatio > 0.12 -> BookGenre.FICTION
            technical >= 8 && technical > biography -> BookGenre.TECHNICAL
            biography >= 8 && biography > self -> BookGenre.BIOGRAPHY
            self >= 8 -> BookGenre.SELF_DEVELOPMENT
            else -> BookGenre.INFORMATIONAL
        }
    }

    private fun properNames(text: String): List<String> {
        val counts = HashMap<String, Int>()
        Sentences.split(text.take(60_000)).forEach { s ->
            val ws = Lexicon.words(s.text)
            for (i in 1 until ws.size) {
                val w = ws[i]
                if (w.length >= 3 && w[0].isUpperCase() && w.drop(1).all { it.isLowerCase() } && TextNormalizer.foldCase(w) !in Lexicon.stopwords) {
                    counts.merge(w, 1, Int::plus)
                }
            }
        }
        return counts.filter { it.value >= 3 }.entries.sortedByDescending { it.value }.map { it.key }
    }

    // ---------- Summaries ----------

    fun summary(type: SummaryType, bookTitle: String, chapters: List<ChapterInput>, concepts: List<ConceptInput>, length: SummaryLength): SummaryDocument {
        val perChapter = when (length) { SummaryLength.SHORT -> 1; SummaryLength.MEDIUM -> 2; SummaryLength.LONG -> 3 }
        val note = "Bu içerik cihaz üzerinde, kitaptaki cümleler seçilerek oluşturuldu (yapay zekâ kullanılmadı). " +
            "Yorumlayıcı bir özet için yapay zekâ sağlayıcısını etkinleştirin."
        fun ref(ch: ChapterInput, s: Sentences.Span) = SourceRef(ch.index, ch.title, ch.pageStart, null, s.start, s.end, OutputValidator.limitQuote(s.text))

        return when (type) {
            SummaryType.QUICK -> {
                val picks = chapters.mapNotNull { ch -> keySentences(ch.text, 1).firstOrNull()?.let { ch to it } }.let { all ->
                    if (all.size <= 8) all else all.filterIndexed { i, _ -> i % ((all.size + 7) / 8) == 0 }
                }
                SummaryDocument(
                    type, "$bookTitle – Hızlı özet",
                    sections = listOfNotNull(
                        picks.firstOrNull()?.let { (ch, s) -> SummarySection("Temel fikir", s.text, sources = listOf(ref(ch, s))) },
                        SummarySection("Öne çıkan noktalar", bullets = picks.drop(1).map { it.second.text }, sources = picks.drop(1).map { (ch, s) -> ref(ch, s) }),
                        concepts.take(10).takeIf { it.isNotEmpty() }?.let { cs -> SummarySection("Temel kavramlar", bullets = cs.map { it.title }) },
                    ),
                    notes = listOf(note),
                )
            }
            SummaryType.DETAILED, SummaryType.CHAPTER, SummaryType.EXECUTIVE -> SummaryDocument(
                type, if (type == SummaryType.CHAPTER) chapters.firstOrNull()?.title.orEmpty() else "$bookTitle – Bölüm özetleri",
                sections = chapters.map { ch ->
                    val ks = keySentences(ch.text, perChapter + if (type == SummaryType.CHAPTER) 1 else 0)
                    SummarySection(
                        heading = ch.title,
                        body = ks.joinToString(" ") { it.text },
                        bullets = concepts.filter { it.chapterIndex == ch.index }.take(5).map { it.title },
                        sources = ks.map { ref(ch, it) },
                    )
                }.filter { it.body.isNotBlank() },
                notes = listOf(note) + if (type == SummaryType.EXECUTIVE) listOf("Yönetici özeti (problem, tez, çözüm, kanıt) yorum gerektirir; yerel modda bölüm özetleri gösteriliyor.") else emptyList(),
            )
            SummaryType.KEY_IDEAS -> SummaryDocument(
                type, "$bookTitle – En önemli fikirler",
                ideas = concepts.take(12).map { c ->
                    val ch = chapters.firstOrNull { it.index == c.chapterIndex }
                    KeyIdea(idea = c.title, explanation = c.explanation, support = c.quote.orEmpty(), source = SourceRef(c.chapterIndex, ch?.title, quote = c.quote))
                },
                notes = listOf(note),
            )
            SummaryType.CONCEPT_MAP -> {
                val nodes = mutableListOf<ConceptMapNode>()
                val edges = mutableListOf<ConceptMapEdge>()
                chapters.forEach { ch -> nodes += ConceptMapNode("ch${ch.index}", ch.title) }
                concepts.forEachIndexed { i, c -> nodes += ConceptMapNode("c$i", c.title, parent = "ch${c.chapterIndex}") }
                // Concepts mentioned in the same sentence are connected; this is co-occurrence, not causation.
                for (ch in chapters) {
                    val sentences = Sentences.split(ch.text)
                    for (i in concepts.indices) for (j in i + 1 until concepts.size) {
                        val together = sentences.count { containsTerm(it.text, concepts[i].title) && containsTerm(it.text, concepts[j].title) }
                        if (together >= 2 && edges.none { it.from == "c$i" && it.to == "c$j" }) edges += ConceptMapEdge("c$i", "c$j", "birlikte geçiyor", causal = false)
                    }
                }
                SummaryDocument(type, "$bookTitle – Kavram haritası", conceptMap = ConceptMap(nodes.filter { n -> n.id.startsWith("c") || nodes.any { it.parent == n.id } }, edges), notes = listOf(note, "Bağlantılar kavramların aynı cümlelerde birlikte geçmesine dayanır; neden-sonuç ilişkisi anlamına gelmez."))
            }
            SummaryType.CHAPTER_COMPARISON -> {
                val stemsByChapter = chapters.associate { ch -> ch.index to concepts.filter { it.chapterIndex == ch.index }.map { it.title } }
                val sections = mutableListOf<SummarySection>()
                for (a in chapters) for (b in chapters) {
                    if (b.index <= a.index) continue
                    val shared = (stemsByChapter[a.index].orEmpty()).filter { title -> containsTerm(b.text, title) } +
                        (stemsByChapter[b.index].orEmpty()).filter { title -> containsTerm(a.text, title) }
                    if (shared.distinct().size >= 2) sections += SummarySection("${a.title} ↔ ${b.title}", "Bu bölümler ortak kavramlar üzerinde duruyor.", bullets = shared.distinct().take(6))
                }
                SummaryDocument(type, "$bookTitle – Bölüm karşılaştırması", sections = sections.take(12).ifEmpty {
                    listOf(SummarySection("Ortak kavram bulunamadı", "Bölümler arasında belirgin ortak kavram tespit edilmedi."))
                }, notes = listOf(note, "Destekleme/çelişki ilişkisini belirlemek yorum gerektirir; yerel mod yalnızca ortak kavramları gösterir."))
            }
        }
    }

    // ---------- Questions ----------

    fun quiz(concepts: List<ConceptInput>, chapters: List<ChapterInput>, count: Int, allowed: Set<QuestionType>, seed: Int = 1): List<QuestionDraft> {
        val rnd = java.util.Random(seed.toLong())
        val out = mutableListOf<QuestionDraft>()
        val pool = concepts.shuffledWith(rnd)
        val masked = { c: ConceptInput -> maskTerm(c.explanation, c.title) }
        val chapterText = { i: Int -> chapters.firstOrNull { it.index == i }?.text.orEmpty() }

        fun sentenceWith(c: ConceptInput): Sentences.Span? =
            Sentences.split(chapterText(c.chapterIndex)).filter { it.text.length in 40..260 && containsTerm(it.text, c.title) }
                .let { list -> list.firstOrNull { it.text != c.explanation } ?: list.firstOrNull() }

        var round = 0
        while (out.size < count && round < 6) {
            for (c in pool) {
                if (out.size >= count) break
                val type = listOf(
                    QuestionType.MULTIPLE_CHOICE, QuestionType.FILL_BLANK, QuestionType.TRUE_FALSE,
                    QuestionType.EXPLAIN_OWN_WORDS, QuestionType.DEFINE_CONCEPT,
                ).filter { it in allowed }.let { if (it.isEmpty()) return@let null else it[(round + out.size) % it.size] } ?: break
                val others = pool.filter { it.title != c.title }
                val q: QuestionDraft? = when (type) {
                    QuestionType.MULTIPLE_CHOICE -> if (others.size >= 3) {
                        val correct = masked(c)
                        val options = (others.shuffledWith(rnd).take(3).map(masked) + correct).distinct().shuffledWith(rnd)
                        QuestionDraft(type, "“${c.title}” için aşağıdakilerden hangisi kitaptaki açıklamaya uygundur?", options, correct,
                            explanation = "Kitapta: ${c.explanation}", chapterIndex = c.chapterIndex, quote = c.quote, conceptTitle = c.title, difficulty = 2)
                    } else null
                    QuestionType.FILL_BLANK -> sentenceWith(c)?.let { s ->
                        val m = termRegex(c.title).find(s.text) ?: return@let null
                        QuestionDraft(type, s.text.replaceRange(m.range, "_____"), answer = m.value, acceptableAnswers = listOf(c.title),
                            explanation = "Boşluğa “${m.value}” gelir.", chapterIndex = c.chapterIndex, quote = OutputValidator.limitQuote(s.text), conceptTitle = c.title, difficulty = 2)
                    }
                    QuestionType.TRUE_FALSE -> {
                        val s = sentenceWith(c)
                        val swap = others.firstOrNull { o -> s != null && !containsTerm(s.text, o.title) }
                        if (s == null) null else if (swap != null && rnd.nextBoolean()) {
                            val m = termRegex(c.title).find(s.text)!!
                            QuestionDraft(type, s.text.replaceRange(m.range, swap.title.lowercase()), answer = "false",
                                explanation = "Yanlış. Kitapta bu ifade “${c.title}” için geçiyor: ${s.text}", chapterIndex = c.chapterIndex, quote = OutputValidator.limitQuote(s.text), conceptTitle = c.title, difficulty = 3)
                        } else QuestionDraft(type, s.text, answer = "true", explanation = "Doğru; bu ifade kitapta geçiyor.", chapterIndex = c.chapterIndex, quote = OutputValidator.limitQuote(s.text), conceptTitle = c.title, difficulty = 1)
                    }
                    QuestionType.EXPLAIN_OWN_WORDS -> QuestionDraft(type, "“${c.title}” kavramını kendi cümlelerinle açıkla.", answer = c.explanation,
                        rubric = keyPoints(c.explanation, c.title), explanation = "Kitaptaki açıklama: ${c.explanation}", chapterIndex = c.chapterIndex, quote = c.quote, conceptTitle = c.title, difficulty = 4)
                    QuestionType.DEFINE_CONCEPT -> QuestionDraft(type, "Tanımla: ${c.title}", answer = c.explanation, rubric = keyPoints(c.explanation, c.title),
                        explanation = "Kitaptaki açıklama: ${c.explanation}", chapterIndex = c.chapterIndex, quote = c.quote, conceptTitle = c.title, difficulty = 3)
                    else -> null
                }
                if (q != null && OutputValidator.questionIssue(q) == null && out.none { it.prompt == q.prompt }) out += q
            }
            round++
        }
        if (QuestionType.MATCHING in allowed && pool.size >= 3 && out.size < count + 1) {
            val pairs = pool.take(4).map { MatchPair(it.title, masked(it).take(140)) }
            out += QuestionDraft(QuestionType.MATCHING, "Kavramları açıklamalarıyla eşleştir.", pairs = pairs, answer = pairs.joinToString(" | ") { it.left },
                explanation = pairs.joinToString("\n") { "${it.left}: ${it.right}" }, chapterIndex = pool.first().chapterIndex, difficulty = 2)
        }
        if (QuestionType.ORDERING in allowed && out.size < count + 1) {
            chapters.firstOrNull { keySentences(it.text, 4).size == 4 }?.let { ch ->
                val items = keySentences(ch.text, 4).map { it.text.take(140) }
                out += QuestionDraft(QuestionType.ORDERING, "Bu ifadeleri “${ch.title}” bölümünde geçtikleri sıraya göre diz.",
                    options = items.shuffledWith(rnd), answer = items.joinToString(" | "), explanation = "Doğru sıra kitaptaki akışı izler.", chapterIndex = ch.index, difficulty = 3)
            }
        }
        return out.take(count)
    }

    private fun <T> List<T>.shuffledWith(rnd: java.util.Random): List<T> = toMutableList().also { java.util.Collections.shuffle(it, rnd) }

    fun maskTerm(text: String, term: String): String = termRegex(term).replace(text, "bu kavram").let {
        if (it.length > 220) it.take(219) + "…" else it
    }

    /** Main content words of a model answer, used as rubric points for the heuristic grader. */
    fun keyPoints(answer: String, exclude: String): List<String> {
        val excluded = Lexicon.stems(exclude)
        return Lexicon.contentWords(answer).filter { Lexicon.stem(it) !in excluded && it.length >= 4 }
            .distinctBy(Lexicon::stem).sortedByDescending { it.length }.take(5)
    }

    // ---------- Grading ----------

    /**
     * Keyword-coverage grading for open answers. It cannot judge reasoning, so confidence stays low and the
     * UI labels the result as approximate.
     */
    fun grade(modelAnswer: String, rubric: List<String>, userAnswer: String, acceptable: List<String> = emptyList()): GradeResult {
        val answerStems = Lexicon.stems(userAnswer)
        val wordCount = Lexicon.words(userAnswer).size
        if (wordCount == 0 || TextNormalizer.foldCase(userAnswer.trim()) in setOf("bilmiyorum", "i don't know", "bilmiyorum.", "?")) {
            return GradeResult(0.0, false, "Sorun değil; birlikte bakalım.", missingPoints = rubric, confidence = 0.9, rationale = "Cevap verilmedi.")
        }
        if (acceptable.any { TextNormalizer.asciiFold(it).trim() == TextNormalizer.asciiFold(userAnswer).trim() }) {
            return GradeResult(1.0, true, "Doğru.", confidence = 0.9, rationale = "Kabul edilen cevaplardan biriyle eşleşti.")
        }
        val points = rubric.ifEmpty { keyPoints(modelAnswer, "") }
        val covered = points.filter { p -> Lexicon.stems(p).let { s -> s.isNotEmpty() && s.count { it in answerStems } * 2 >= s.size } }
        val missing = points - covered.toSet()
        val modelStems = Lexicon.stems(modelAnswer)
        val overlap = if (modelStems.isEmpty()) 0.0 else modelStems.count { it in answerStems }.toDouble() / modelStems.size
        var score = if (points.isEmpty()) overlap else 0.7 * covered.size / points.size + 0.3 * min(1.0, overlap * 2)
        if (wordCount < 4) score = min(score, 0.4)
        score = score.coerceIn(0.0, 1.0)
        val feedback = when {
            score >= 0.8 -> "Ana noktaları kapsıyorsun."
            score >= 0.5 -> "Kısmen doğru; bazı noktalar eksik."
            else -> "Cevap kitaptaki açıklamanın ana noktalarını kapsamıyor."
        }
        return GradeResult(score, score >= 0.5, feedback, covered, missing, confidence = 0.45,
            rationale = "Anahtar kelime kapsamına göre yaklaşık değerlendirme (${covered.size}/${points.size} nokta).")
    }

    // ---------- Tutor ----------

    /** Scripted Socratic flow for offline teaching: diagnose → explain with book example → check → transfer → wrap up. */
    fun teach(step: Int, concept: ConceptInput, chapter: ChapterInput?, lastAnswer: String?, lastExpected: String?, style: String?): TeachTurn {
        val examples = chapter?.let { ch -> Sentences.split(ch.text).filter { it.text != concept.explanation && containsTerm(it.text, concept.title) && it.text.length in 40..300 } }.orEmpty()
        val source = chapter?.let { SourceRef(it.index, it.title, it.pageStart, quote = concept.quote) }
        return when (step) {
            0 -> TeachTurn(
                message = "“${concept.title}” konusuna başlayalım. Önce ne bildiğini görelim.",
                question = "“${concept.title}” sana ne ifade ediyor? Bir iki cümleyle anlat (bilmiyorsan “bilmiyorum” yazabilirsin).",
            )
            1 -> {
                val g = grade(concept.explanation, keyPoints(concept.explanation, concept.title), lastAnswer.orEmpty())
                val level = when { g.score >= 0.75 -> TeachingLevel.INTERMEDIATE; g.score >= 0.4 -> TeachingLevel.BASIC; else -> TeachingLevel.BEGINNER }
                val cloze = examples.firstOrNull()
                val styleNote = when (style) {
                    "analogy", "debate" -> "\n\n(Benzetme ve tartışma üretmek için yapay zekâ sağlayıcısı gerekiyor; şimdilik kitaptan örnek gösteriyorum.)"
                    else -> ""
                }
                TeachTurn(
                    message = (if (g.score >= 0.5) "Güzel, temel fikri biliyorsun. " else "Hiç sorun değil, adım adım gidelim. ") +
                        "Kitap bu kavramı şöyle açıklıyor:\n\n“${concept.explanation}”" +
                        (cloze?.let { "\n\nKitaptan bir örnek:\n“${it.text}”" } ?: "") + styleNote,
                    question = cloze?.let { s -> termRegex(concept.title).find(s.text)?.let { m -> "Boşluğu doldur: ${s.text.replaceRange(m.range, "_____")}" } }
                        ?: "Bu açıklamadaki en önemli kelime sence hangisi, neden?",
                    example = cloze?.text,
                    source = source,
                    suggestedLevel = level,
                )
            }
            2 -> {
                val correct = lastExpected?.let { exp -> TextNormalizer.asciiFold(lastAnswer.orEmpty()).contains(TextNormalizer.asciiFold(exp).take(5)) } ?: (lastAnswer.orEmpty().length > 10)
                val alt = examples.getOrNull(1)
                TeachTurn(
                    message = if (correct) "Doğru! 👏" else "Tam değil. Boşluğa “${lastExpected ?: concept.title}” gelmeliydi; çünkü cümle bu kavramın tanımına dayanıyor." +
                        (alt?.let { "\n\nFarklı bir cümleyle tekrar bakalım:\n“${it.text}”" } ?: ""),
                    question = "Bu kavramı günlük hayatında ya da işinde nerede kullanabilirsin? Somut bir durum yaz.",
                    source = source,
                )
            }
            3 -> TeachTurn(
                message = if (lastAnswer.orEmpty().trim().length >= 15) "Bu, kavramı uygulamaya dökmenin iyi bir yolu." else "Biraz daha somut düşünmeyi dene: kim, nerede, ne zaman?",
                question = "Son olarak: “${concept.title}” kavramını hiç bilmeyen birine kendi cümlelerinle nasıl anlatırdın?",
            )
            else -> {
                val g = grade(concept.explanation, keyPoints(concept.explanation, concept.title), lastAnswer.orEmpty())
                TeachTurn(
                    message = (if (g.correct) "Harika, kavramı kendi cümlelerinle açıklayabildin. " else "Açıklaman bazı noktaları kaçırıyor: ${g.missingPoints.joinToString()}. ") +
                        "Bu kavramı tekrar kuyruğuna ekledim; unutma eğrine göre yeniden soracağım.",
                    question = null,
                    expectsAnswer = false,
                    understood = g.correct,
                )
            }
        }
    }

    // ---------- Stories & links ----------

    fun story(concept: ConceptInput, chapter: ChapterInput?): StoryDraft {
        val examples = chapter?.let { ch -> Sentences.split(ch.text).filter { containsTerm(it.text, concept.title) && it.text.length in 40..300 } }.orEmpty().take(3)
        return StoryDraft(
            title = "Kitaptan örnekler: ${concept.title}",
            body = if (examples.isEmpty()) concept.explanation else examples.joinToString("\n\n") { "“${it.text}”" },
            kind = "book_examples",
            fromBook = true,
            source = chapter?.let { SourceRef(it.index, it.title, it.pageStart, quote = OutputValidator.limitQuote(examples.firstOrNull()?.text)) },
            questions = listOf(
                QuestionDraft(QuestionType.EXPLAIN_OWN_WORDS, "Bu örneklerde “${concept.title}” nasıl ortaya çıkıyor?", answer = concept.explanation,
                    rubric = keyPoints(concept.explanation, concept.title), chapterIndex = concept.chapterIndex),
            ),
        )
    }

    fun links(conceptsByBook: Map<String, List<String>>): ConceptLinksDraft {
        val entries = conceptsByBook.flatMap { (book, cs) -> cs.map { book to it } }
        val links = mutableListOf<ConceptLinkDraft>()
        for (i in entries.indices) for (j in i + 1 until entries.size) {
            val (bookA, a) = entries[i]; val (bookB, b) = entries[j]
            if (bookA == bookB) continue
            val sa = Lexicon.stems(a); val sb = Lexicon.stems(b)
            if (sa.isEmpty() || sb.isEmpty()) continue
            val jaccard = sa.intersect(sb).size.toDouble() / sa.union(sb).size
            when {
                jaccard >= 0.99 -> links += ConceptLinkDraft(a, b, "same_concept", "İki kitapta aynı adla geçiyor.", 0.7)
                jaccard >= 0.5 -> links += ConceptLinkDraft(a, b, "related", "Adları büyük ölçüde örtüşüyor; ilişki yerel olarak tahmin edildi.", 0.35)
            }
        }
        return ConceptLinksDraft(links)
    }
}
