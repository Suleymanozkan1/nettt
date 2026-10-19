package com.bookmind.core.ai

import com.bookmind.core.i18n.CoreText

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

    fun chapterAnalysis(a: ChapterAnalysis, chapterText: String, lang: String? = null): Validated<ChapterAnalysis> {
        val t = CoreText.of(lang)
        val warnings = mutableListOf<String>()
        val concepts = a.concepts
            .filter { it.title.isNotBlank() && it.explanation.isNotBlank() }
            .distinctBy { norm(it.title) }
            .map { c ->
                val quote = limitQuote(c.quote)
                val verified = quote != null && quoteOccursIn(quote, chapterText)
                if (quote != null && !verified) warnings += t.quoteRemoved(c.title)
                c.copy(
                    title = c.title.trim().take(120),
                    explanation = c.explanation.trim().take(1_500),
                    importance = c.importance.coerceIn(1, 5),
                    difficulty = c.difficulty.coerceIn(1, 5),
                    quote = if (verified) quote else null,
                )
            }
        if (a.summary.isBlank() && concepts.isEmpty()) throw AiException.InvalidOutput("empty chapter analysis")
        return Validated(a.copy(concepts = concepts, summary = a.summary.trim()), warnings)
    }

    fun summary(doc: SummaryDocument, chapterTexts: List<String>, lang: String? = null): Validated<SummaryDocument> {
        val t = CoreText.of(lang)
        val warnings = mutableListOf<String>()
        fun checkRef(ref: SourceRef): SourceRef {
            val text = chapterTexts.getOrNull(ref.chapterIndex)
            if (text == null) {
                warnings += t.sourceChapterMissing(ref.chapterIndex + 1)
                return ref.copy(verified = false, quote = null, pageStart = null, pageEnd = null)
            }
            val quote = limitQuote(ref.quote)
            val ok = quote == null || quoteOccursIn(quote, text)
            if (!ok) warnings += t.quoteUnverified
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
        if (sections.isEmpty() && ideas.isEmpty() && (map?.nodes.isNullOrEmpty())) throw AiException.InvalidOutput("empty summary")
        // Numbers and names the model wrote must exist in the book; otherwise the reader is told.
        val generated = sections.flatMap { listOf(it.body) + it.bullets } + ideas.flatMap { listOf(it.idea, it.explanation, it.support, it.example) }
        Faithfulness.check(generated, chapterTexts).takeIf { !it.clean }?.let { warnings += t.unsupportedClaims(it.items.joinToString(", ")) }
        return Validated(doc.copy(sections = sections, ideas = ideas, conceptMap = map), warnings.distinct())
    }

    fun quiz(draft: QuizDraft, chapterTexts: List<String>, lang: String? = null): Validated<List<QuestionDraft>> {
        val t = CoreText.of(lang)
        val warnings = mutableListOf<String>()
        val result = mutableListOf<QuestionDraft>()
        for (q in draft.questions) {
            val issue = questionIssue(q)
            if (issue != null) { warnings += t.questionSkipped(issue); continue }
            var question = q.copy(prompt = q.prompt.trim(), answer = q.answer.trim(), options = q.options.map { it.trim() })
            val chapterText = q.chapterIndex?.let { chapterTexts.getOrNull(it) }
            val quote = limitQuote(q.quote)
            if (quote != null && (chapterText == null || !quoteOccursIn(quote, chapterText))) {
                question = question.copy(quote = null)
                warnings += t.questionQuoteUnverified
            } else question = question.copy(quote = quote)
            if (isAmbiguous(question)) question = question.copy(needsReview = true)
            result += question
        }
        if (result.isEmpty()) throw AiException.InvalidOutput("no valid questions")
        return Validated(result.distinctBy { norm(it.prompt) }, warnings.distinct())
    }

    /** Returns why a question is unusable, or null if it is structurally valid. */
    fun questionIssue(q: QuestionDraft): String? {
        if (q.prompt.isBlank()) return "empty prompt"
        return when (q.type) {
            QuestionType.MULTIPLE_CHOICE, QuestionType.SCENARIO, QuestionType.REAL_LIFE, QuestionType.FIND_ERROR -> when {
                q.options.size < 2 -> if (q.type == QuestionType.MULTIPLE_CHOICE) "needs at least two options" else null
                q.options.none { norm(it) == norm(q.answer) } -> "answer is not among the options"
                else -> null
            }
            QuestionType.TRUE_FALSE -> if (q.answer.lowercase() !in setOf("true", "false", "doğru", "yanlış")) "invalid true/false answer" else null
            QuestionType.FILL_BLANK -> when {
                q.answer.isBlank() -> "missing blank answer"
                !q.prompt.contains("___") -> "missing blank marker"
                else -> null
            }
            QuestionType.MATCHING -> if (q.pairs.size < 2 || q.pairs.any { it.left.isBlank() || it.right.isBlank() }) "missing matching pairs" else null
            QuestionType.ORDERING -> if (q.answer.split('|').map { it.trim() }.filter { it.isNotEmpty() }.size < 2) "missing ordering items" else null
            QuestionType.CALCULATION, QuestionType.SHORT_ANSWER -> if (q.answer.isBlank()) "missing answer key" else null
            QuestionType.OPEN_ENDED, QuestionType.DEFINE_CONCEPT, QuestionType.EXPLAIN_OWN_WORDS ->
                if (q.answer.isBlank() && q.rubric.isEmpty()) "missing rubric" else null
        }
    }

    /** Duplicate options, or the answer matching more than one option, means the question needs review. */
    fun isAmbiguous(q: QuestionDraft): Boolean {
        if (q.options.isEmpty()) return q.needsReview
        val normalized = q.options.map(::norm)
        return q.needsReview || normalized.toSet().size != normalized.size || normalized.count { it == norm(q.answer) } > 1
    }

    fun grade(g: GradeResult, lang: String? = null): GradeResult = g.copy(
        score = if (g.score.isNaN()) 0.0 else g.score.coerceIn(0.0, 1.0),
        confidence = if (g.confidence.isNaN()) 0.0 else g.confidence.coerceIn(0.0, 1.0),
        correct = g.score >= 0.5,
        feedback = g.feedback.ifBlank { if (g.score >= 0.5) CoreText.of(lang).generallyCorrect else CoreText.of(lang).incompleteOrWrong },
    )

    fun links(d: ConceptLinksDraft, known: Set<String>, lang: String? = null): Validated<List<ConceptLinkDraft>> {
        val warnings = mutableListOf<String>()
        val knownNorm = known.associateBy(::norm)
        val links = d.links.mapNotNull { l ->
            val from = knownNorm[norm(l.fromConcept)]
            val to = knownNorm[norm(l.toConcept)]
            if (from == null || to == null || from == to) {
                warnings += CoreText.of(lang).unknownLinkSkipped
                null
            } else l.copy(fromConcept = from, toConcept = to, confidence = l.confidence.coerceIn(0.0, 1.0))
        }
        return Validated(links, warnings.distinct())
    }

    /** Drops citations that point nowhere; an answer with no valid citation is marked as not grounded. */
    fun answer(a: BookAnswer, passages: List<com.bookmind.core.search.Passage>, lang: String? = null): Validated<BookAnswer> {
        if (a.answer.isBlank()) throw AiException.InvalidOutput("empty answer")
        val citations = a.citations.filter { it in passages.indices }.distinct()
        val warnings = mutableListOf<String>()
        if (a.inBook && citations.isEmpty()) warnings += CoreText.of(lang).answerUncited
        return Validated(a.copy(answer = a.answer.trim(), citations = citations, confidence = a.confidence.coerceIn(0.0, 1.0)), warnings)
    }
}
