package com.bookmind.core.ai.study

import com.bookmind.core.ai.Lexicon
import com.bookmind.core.ai.LocalHeuristicEngine
import com.bookmind.core.ai.LocalHeuristicEngine.ConceptInput
import com.bookmind.core.ai.QuestionType
import com.bookmind.core.i18n.CoreText
import com.bookmind.core.text.Sentences

/** One lesson (a chapter) as the study modules see it. */
data class Lesson(
    val index: Int,
    val title: String,
    val text: String,
    /** Concepts found in this chapter. */
    val concepts: List<ConceptInput>,
    /** Concepts of the whole book, for prerequisites from earlier chapters and distractors. */
    val bookConcepts: List<ConceptInput> = concepts,
)

/**
 * Packs a long lesson into a prompt budget: when the text does not fit, the most central sentences (TextRank) are
 * kept in their original order instead of cutting the text off, so the model still sees the whole chapter's ideas.
 */
object ContextPacker {
    fun pack(text: String, budgetChars: Int, engine: LocalHeuristicEngine = LocalHeuristicEngine()): String {
        if (text.length <= budgetChars) return text
        val sentences = Sentences.split(text)
        if (sentences.isEmpty()) return text.take(budgetChars)
        // keySentences(n) gives the n most central sentences in book order; find the largest n that fits.
        var lo = 1
        var hi = sentences.size
        var best = emptyList<Sentences.Span>()
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val picked = engine.keySentences(text, mid)
            if (picked.sumOf { it.text.length + 1 } <= budgetChars) { best = picked; lo = mid + 1 } else hi = mid - 1
        }
        if (best.isEmpty()) return sentences.first().text.take(budgetChars)
        val chosen = best
        return chosen.sortedBy { it.start }.joinToString(" ") { it.text }
    }
}

/**
 * AI-free versions of every study module, built from TextRank sentences, extracted concepts and the existing
 * question generators. Used when no model is installed, AI is off, or the model fails; nothing is invented, all
 * content comes from the book's own sentences.
 */
class StudyFallbacks(private val engine: LocalHeuristicEngine = LocalHeuristicEngine()) {

    /**
     * The lesson's own concepts, topped up with concepts from other chapters that this lesson also talks about, and
     * finally with concepts extracted from the text, so short chapters still give enough cards and questions.
     */
    private fun concepts(lesson: Lesson, max: Int): List<ConceptInput> {
        val own = lesson.concepts.ifEmpty {
            engine.extractConcepts(lesson.text, max).map { ConceptInput(it.title, it.explanation, lesson.index, it.quote) }
        }
        if (own.size >= max) return own.take(max)
        val mentioned = lesson.bookConcepts.filter { it.chapterIndex != lesson.index && engine.containsTerm(lesson.text, it.title) }
        return (own + mentioned).distinctBy { it.title.lowercase() }.take(max)
    }

    private fun shortSentence(s: String, max: Int = 220) = if (s.length <= max) s else s.take(max - 1).substringBeforeLast(' ') + "…"

    fun notes(lesson: Lesson): SmartNotes {
        val key = engine.keySentences(lesson.text, 7).map { it.text }
        val main = engine.keySentences(lesson.text, 1).firstOrNull()?.text.orEmpty()
        val rest = key.filter { it != main }
        return SmartNotes(
            title = lesson.title,
            mainIdea = main,
            concepts = concepts(lesson, 6).map { TermNote(it.title, shortSentence(it.explanation)) },
            keyPoints = rest.take(5),
            quotes = rest.filter { it.length <= 200 }.takeLast(2),
            takeaway = rest.lastOrNull().orEmpty(),
        )
    }

    fun podcast(lesson: Lesson, lang: String?): PodcastScript {
        val t = CoreText.of(lang)
        val main = engine.keySentences(lesson.text, 1).firstOrNull()?.text.orEmpty()
        val sentences = Sentences.split(lesson.text).map { it.text }
        val lines = mutableListOf(PodcastLine("A", t.podcastWelcome(lesson.title)), PodcastLine("B", main))
        val said = mutableSetOf(main)
        concepts(lesson, 5).forEach { c ->
            if (lines.size >= 14) return@forEach
            val explanation = shortSentence(c.explanation, 260)
            if (!said.add(c.explanation)) return@forEach
            lines += PodcastLine("A", t.podcastAskTerm(c.title))
            lines += PodcastLine("B", explanation)
            // A second sentence about the same term, if the book has one, answers "why does it matter".
            sentences.firstOrNull { it !in said && engine.containsTerm(it, c.title) && it.length < 260 }?.let {
                said += it
                lines += PodcastLine("A", t.podcastWhyMatters)
                lines += PodcastLine("B", it)
            }
        }
        lines += PodcastLine("A", t.podcastClosing)
        lines += PodcastLine("B", notes(lesson).takeaway.takeIf { it.isNotBlank() && it !in said } ?: main)
        return PodcastScript(lesson.title, lines.filter { it.text.isNotBlank() })
    }

    fun flashcards(lesson: Lesson, count: Int, lang: String?): FlashcardDeck {
        val t = CoreText.of(lang)
        val cards = concepts(lesson, count).map { Flashcard(t.flashFront(it.title), shortSentence(it.explanation)) }.toMutableList()
        if (cards.size < count) {
            // Top up with key sentences whose concept word is hidden on the front.
            val terms = concepts(lesson, 12)
            engine.keySentences(lesson.text, count * 2).forEach { s ->
                if (cards.size >= count) return@forEach
                val term = terms.firstOrNull { engine.containsTerm(s.text, it.title) } ?: return@forEach
                val front = engine.maskTerm(s.text, term.title, lang)
                if (cards.none { it.front == front }) cards += Flashcard(front, term.title)
            }
        }
        return FlashcardDeck(cards.take(count))
    }

    fun prerequisiteGaps(lesson: Lesson, lang: String?): GapDeck {
        val t = CoreText.of(lang)
        val earlier = lesson.bookConcepts.filter { it.chapterIndex < lesson.index && engine.containsTerm(lesson.text, it.title) }
            .distinctBy { it.title.lowercase() }
            .map { GapCard(it.title, t.gapFromEarlier(it.chapterIndex + 1), shortSentence(it.explanation), it.quote.orEmpty()) }
        val core = concepts(lesson, 6).sortedByDescending { it.explanation.length }
            .map { GapCard(it.title, t.gapCore, shortSentence(it.explanation), "") }
        return GapDeck((earlier + core).distinctBy { it.topic.lowercase() }.take(4))
    }

    fun gapsFromMistakes(lesson: Lesson, mistakes: List<Mistake>, lang: String?): GapDeck {
        val t = CoreText.of(lang)
        val sentences = Sentences.split(lesson.text).map { it.text }
        return GapDeck(mistakes.take(6).map { m ->
            val stems = Lexicon.stems(m.expected + " " + m.question)
            val support = sentences.maxByOrNull { s -> Lexicon.stems(s).count { it in stems } }.orEmpty()
            GapCard(shortSentence(m.question, 120), t.gapMistake, shortSentence(m.expected), support.takeIf { it != m.expected }.orEmpty())
        })
    }

    fun exercises(lesson: Lesson, lang: String?): ExerciseSet {
        val terms = concepts(lesson, 8)
        val sentences = Sentences.split(lesson.text).map { it.text }.filter { it.length in 40..240 }
        val cloze = terms.mapNotNull { c ->
            val s = sentences.firstOrNull { it != c.explanation && engine.containsTerm(it, c.title) }
                ?: sentences.firstOrNull { engine.containsTerm(it, c.title) } ?: return@mapNotNull null
            val match = engine.termRegex(c.title).find(s) ?: return@mapNotNull null
            // Blank only the term and keep its Turkish suffix ("_____nın"), so every option fits the gap.
            val stemEnd = match.range.first + minOf(c.title.length, match.value.length)
            val sentence = s.substring(0, match.range.first) + "_____" + s.substring(stemEnd)
            val others = terms.filter { it.title != c.title }.shuffled(java.util.Random(c.title.hashCode().toLong())).take(3).map { it.title }
            ClozeItem(sentence, c.title, (others + c.title).shuffled(java.util.Random(s.hashCode().toLong())))
        }.take(4)
        val matching = terms.take(5).map { c ->
            MatchItem(c.title, definitionWithoutTerm(c).let { d -> Lexicon.words(d).let { w -> if (w.size <= 12) d.trimEnd('.') else w.take(12).joinToString(" ") + "…" } })
        }
        return ExerciseSet(cloze, if (matching.size >= 2) matching else emptyList())
    }

    /** "Tanıma, doğru cevabı … seçmektir." → "Doğru cevabı … seçmektir." so a matching pair does not give itself away. */
    private fun definitionWithoutTerm(c: ConceptInput): String {
        val e = c.explanation.trim()
        val prefix = Regex("^" + engine.termRegex(c.title).pattern + "\\s*[,:–-]\\s*", RegexOption.IGNORE_CASE).find(e)
        val d = if (prefix != null) e.substring(prefix.range.last + 1) else engine.termRegex(c.title).replace(e, "…")
        return d.replaceFirstChar { it.uppercase() }
    }

    private fun chapters(lesson: Lesson) = listOf(LocalHeuristicEngine.ChapterInput(lesson.index, lesson.title, lesson.text))

    fun multipleChoice(lesson: Lesson, count: Int, lang: String?): McqSet {
        val own = concepts(lesson, 12)
        // Other chapters' concepts only serve as distractors.
        val pool = (own + lesson.bookConcepts).distinctBy { it.title.lowercase() }
        val ownTitles = own.map { it.title }.toSet()
        val drafts = engine.quiz(pool, chapters(lesson), pool.size, setOf(QuestionType.MULTIPLE_CHOICE), seed = lesson.index + 1, lang = lang)
        return McqSet(drafts.filter { it.conceptTitle in ownTitles }.mapNotNull { d ->
            val idx = d.options.indexOfFirst { it == d.answer }
            if (d.options.size < 2 || idx < 0) null else McqQuestion(d.prompt, d.options.map { o -> o.replaceFirstChar { it.uppercase() } }, idx, d.explanation)
        }.take(count))
    }

    fun trueFalse(lesson: Lesson, count: Int, lang: String?): TrueFalseSet {
        val drafts = engine.quiz(concepts(lesson, 12), chapters(lesson), count * 3, setOf(QuestionType.TRUE_FALSE), seed = lesson.index + 7, lang = lang)
        val items = drafts.map { TrueFalseItem(it.prompt.replaceFirstChar { c -> c.uppercase() }, it.answer == "true", it.explanation) }
            .distinctBy { it.statement.lowercase() }
        // About half true, half false, alternating so the answer pattern cannot be guessed.
        val (yes, no) = items.partition { it.isTrue }
        val mixed = (0 until maxOf(yes.size, no.size)).flatMap { i -> listOfNotNull(no.getOrNull(i), yes.getOrNull(i)).let { if (i % 2 == 0) it.reversed() else it } }
        return TrueFalseSet(mixed.take(count))
    }

    fun writtenExam(lesson: Lesson, count: Int, difficulty: Int, lang: String?): WrittenExam {
        val t = CoreText.of(lang)
        val cs = concepts(lesson, count + 1)
        val qs = cs.mapIndexed { i, c ->
            val question = when {
                difficulty >= 4 && i % 2 == 1 -> t.examApply(c.title)
                difficulty >= 3 && i + 1 < cs.size && i % 2 == 0 -> t.examCompare(c.title, cs[i + 1].title)
                else -> t.examExplain(c.title)
            }
            val ideal = if (question == t.examCompare(c.title, cs.getOrNull(i + 1)?.title.orEmpty())) c.explanation + " " + cs[i + 1].explanation else c.explanation
            ExamQuestion(question, shortSentence(ideal, 400), engine.keyPoints(ideal, c.title).take(4))
        }
        return WrittenExam(qs.take(count))
    }

    fun gradeWritten(question: ExamQuestion, answer: String, lang: String?): WrittenGrade {
        val g = engine.grade(question.idealAnswer, question.keyPoints, answer, lang = lang)
        val phrases = g.missingPoints.map { phraseAround(question.idealAnswer, it) }.distinct()
        // Neighbouring keywords give overlapping phrases; keep each idea once.
        val missing = mutableListOf<String>()
        phrases.forEach { p ->
            val words = p.lowercase().split(' ').toSet()
            if (missing.none { m -> m.lowercase().split(' ').count { it in words } * 2 >= minOf(words.size, m.split(' ').size) }) missing += p
        }
        return WrittenGrade((g.score * 100).coerceIn(0.0, 100.0), missing.take(3), g.feedback, question.idealAnswer)
    }

    private val joiners = setOf("ve", "ile", "veya", "ya", "da", "de", "ama", "ancak", "and", "or", "but", "with", "the", "a", "an", "of", "to")

    /** A few words of [text] around [keyword], so a missing point reads as an idea rather than a lone word. */
    internal fun phraseAround(text: String, keyword: String, radius: Int = 3): String {
        val sentence = Sentences.split(text).map { it.text }.firstOrNull { it.contains(keyword, ignoreCase = true) } ?: return keyword
        val words = sentence.split(Regex("\\s+"))
        val i = words.indexOfFirst { it.contains(keyword, ignoreCase = true) }.takeIf { it >= 0 } ?: return keyword
        val window = words.subList((i - radius).coerceAtLeast(0), (i + radius + 1).coerceAtMost(words.size))
        // Do not start or end on a conjunction ("… kurulması ve").
        val joiner = { w: String -> w.trim { c -> !c.isLetterOrDigit() }.lowercase() in joiners }
        return window.dropWhile(joiner).dropLastWhile(joiner).joinToString(" ").trim { !it.isLetterOrDigit() }.ifBlank { keyword }
    }

    fun oralTurn(question: ExamQuestion, answer: String, alreadyFollowedUp: Boolean, lang: String?): OralTurn {
        val t = CoreText.of(lang)
        val g = engine.grade(question.idealAnswer, question.keyPoints, answer, lang = lang)
        val score = (g.score * 100).coerceIn(0.0, 100.0)
        val feedback = when {
            score >= 70 -> t.oralGood
            score >= 35 -> t.oralPartial
            else -> t.oralMissed
        }
        val follow = if (!alreadyFollowedUp && score < 70) g.missingPoints.firstOrNull()?.let { t.oralFollowUp(phraseAround(question.idealAnswer, it)) } else null
        return OralTurn(score, feedback, follow)
    }
}
