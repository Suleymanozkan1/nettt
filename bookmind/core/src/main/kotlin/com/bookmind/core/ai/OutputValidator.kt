package com.bookmind.core.ai

import com.bookmind.core.text.SourceRef
import com.bookmind.core.text.TextNormalizer

/**
 * Checks model output before anything is saved: drops empty or self-contradicting items, verifies that quotes
 * really occur in the book, flags ambiguous questions for review. Never throws for bad content; returns what
 * is usable plus warnings so a partial result can still be shown.
 */
object OutputValidator {
    data class Validated<T>(val value: T, val warnings: List<String>)

    private fun norm(s: String) = TextNormalizer.asciiFold(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** True if [quote] (after normalisation) occurs in [source]. Short quotes need an exact-ish match. */
    fun quoteOccursIn(quote: String, source: String): Boolean {
        val q = norm(quote)
        if (q.length < 12) return false
        val s = norm(source)
        if (s.contains(q)) return true
        // Allow small differences (ellipsis in the middle of a quote): both halves must occur.
        val half = q.length / 2
        return q.length > 60 && s.contains(q.substring(0, half - 5)) && s.contains(q.substring(half + 5))
    }

    fun limitQuote(quote: String?): String? =
        quote?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > SourceRef.MAX_QUOTE_CHARS) it.take(SourceRef.MAX_QUOTE_CHARS - 1) + "…" else it }

    fun chapterAnalysis(a: ChapterAnalysis, chapterText: String): Validated<ChapterAnalysis> {
        val warnings = mutableListOf<String>()
        val concepts = a.concepts
            .filter { it.title.isNotBlank() && it.explanation.isNotBlank() }
            .distinctBy { norm(it.title) }
            .map { c ->
                val quote = limitQuote(c.quote)
                val verified = quote != null && quoteOccursIn(quote, chapterText)
                if (quote != null && !verified) warnings += "“${c.title}” için verilen alıntı kitapta bulunamadı; alıntı kaldırıldı."
                c.copy(
                    title = c.title.trim().take(120),
                    explanation = c.explanation.trim().take(1_500),
                    importance = c.importance.coerceIn(1, 5),
                    difficulty = c.difficulty.coerceIn(1, 5),
                    quote = if (verified) quote else null,
                )
            }
        if (a.summary.isBlank() && concepts.isEmpty()) throw AiException.InvalidOutput("bölüm analizi boş")
        return Validated(a.copy(concepts = concepts, summary = a.summary.trim()), warnings)
    }

    fun summary(doc: SummaryDocument, chapterTexts: List<String>): Validated<SummaryDocument> {
        val warnings = mutableListOf<String>()
        fun checkRef(ref: SourceRef): SourceRef {
            val text = chapterTexts.getOrNull(ref.chapterIndex)
            if (text == null) {
                warnings += "Kaynak bölüm ${ref.chapterIndex + 1} bulunamadı."
                return ref.copy(verified = false, quote = null, pageStart = null, pageEnd = null)
            }
            val quote = limitQuote(ref.quote)
            val ok = quote == null || quoteOccursIn(quote, text)
            if (!ok) warnings += "Bir alıntı kitapta doğrulanamadı."
            return ref.copy(quote = if (ok) quote else null, verified = ok)
        }
        val sections = doc.sections
            .filter { it.heading.isNotBlank() && (it.body.isNotBlank() || it.bullets.any { b -> b.isNotBlank() }) }
            .map { s -> s.copy(bullets = s.bullets.filter { it.isNotBlank() }, sources = s.sources.map(::checkRef)) }
        val ideas = doc.ideas.filter { it.idea.isNotBlank() }.map { i -> i.copy(source = i.source?.let(::checkRef)) }
        val map = doc.conceptMap?.let { m ->
            val ids = m.nodes.map { it.id }.toSet()
            m.copy(edges = m.edges.filter { it.from in ids && it.to in ids && it.from != it.to })
        }
        if (sections.isEmpty() && ideas.isEmpty() && (map?.nodes.isNullOrEmpty())) throw AiException.InvalidOutput("özet boş")
        return Validated(doc.copy(sections = sections, ideas = ideas, conceptMap = map), warnings.distinct())
    }

    fun quiz(draft: QuizDraft, chapterTexts: List<String>): Validated<List<QuestionDraft>> {
        val warnings = mutableListOf<String>()
        val result = mutableListOf<QuestionDraft>()
        for (q in draft.questions) {
            val issue = questionIssue(q)
            if (issue != null) { warnings += "Soru atlandı: $issue"; continue }
            var question = q.copy(prompt = q.prompt.trim(), answer = q.answer.trim(), options = q.options.map { it.trim() })
            val chapterText = q.chapterIndex?.let { chapterTexts.getOrNull(it) }
            val quote = limitQuote(q.quote)
            if (quote != null && (chapterText == null || !quoteOccursIn(quote, chapterText))) {
                question = question.copy(quote = null)
                warnings += "Bir sorunun kaynak alıntısı doğrulanamadı."
            } else question = question.copy(quote = quote)
            if (isAmbiguous(question)) question = question.copy(needsReview = true)
            result += question
        }
        if (result.isEmpty()) throw AiException.InvalidOutput("geçerli soru yok")
        return Validated(result.distinctBy { norm(it.prompt) }, warnings.distinct())
    }

    /** Returns why a question is unusable, or null if it is structurally valid. */
    fun questionIssue(q: QuestionDraft): String? {
        if (q.prompt.isBlank()) return "boş soru"
        return when (q.type) {
            QuestionType.MULTIPLE_CHOICE, QuestionType.SCENARIO, QuestionType.REAL_LIFE, QuestionType.FIND_ERROR -> when {
                q.options.size < 2 -> if (q.type == QuestionType.MULTIPLE_CHOICE) "en az iki seçenek gerekli" else null
                q.options.none { norm(it) == norm(q.answer) } -> "cevap seçenekler arasında değil"
                else -> null
            }
            QuestionType.TRUE_FALSE -> if (q.answer.lowercase() !in setOf("true", "false", "doğru", "yanlış")) "doğru/yanlış cevabı geçersiz" else null
            QuestionType.FILL_BLANK -> when {
                q.answer.isBlank() -> "boşluk cevabı yok"
                !q.prompt.contains("___") -> "boşluk işareti yok"
                else -> null
            }
            QuestionType.MATCHING -> if (q.pairs.size < 2 || q.pairs.any { it.left.isBlank() || it.right.isBlank() }) "eşleştirme çiftleri eksik" else null
            QuestionType.ORDERING -> if (q.answer.split('|').map { it.trim() }.filter { it.isNotEmpty() }.size < 2) "sıralama öğeleri eksik" else null
            QuestionType.CALCULATION, QuestionType.SHORT_ANSWER -> if (q.answer.isBlank()) "cevap anahtarı yok" else null
            QuestionType.OPEN_ENDED, QuestionType.DEFINE_CONCEPT, QuestionType.EXPLAIN_OWN_WORDS ->
                if (q.answer.isBlank() && q.rubric.isEmpty()) "değerlendirme ölçütü yok" else null
        }
    }

    /** Duplicate options, or the answer matching more than one option, means the question needs review. */
    fun isAmbiguous(q: QuestionDraft): Boolean {
        if (q.options.isEmpty()) return q.needsReview
        val normalized = q.options.map(::norm)
        return q.needsReview || normalized.toSet().size != normalized.size || normalized.count { it == norm(q.answer) } > 1
    }

    fun grade(g: GradeResult): GradeResult = g.copy(
        score = if (g.score.isNaN()) 0.0 else g.score.coerceIn(0.0, 1.0),
        confidence = if (g.confidence.isNaN()) 0.0 else g.confidence.coerceIn(0.0, 1.0),
        correct = g.score >= 0.5,
        feedback = g.feedback.ifBlank { if (g.score >= 0.5) "Cevabın genel olarak doğru." else "Cevap eksik veya hatalı." },
    )

    fun links(d: ConceptLinksDraft, known: Set<String>): Validated<List<ConceptLinkDraft>> {
        val warnings = mutableListOf<String>()
        val knownNorm = known.associateBy(::norm)
        val links = d.links.mapNotNull { l ->
            val from = knownNorm[norm(l.fromConcept)]
            val to = knownNorm[norm(l.toConcept)]
            if (from == null || to == null || from == to) {
                warnings += "Bilinmeyen kavramlara dayanan bir ilişki atlandı."
                null
            } else l.copy(fromConcept = from, toConcept = to, confidence = l.confidence.coerceIn(0.0, 1.0))
        }
        return Validated(links, warnings.distinct())
    }
}
