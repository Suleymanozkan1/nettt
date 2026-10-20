package com.bookmind.core.ai.study

import com.bookmind.core.ai.AiException
import com.bookmind.core.ai.AiJson
import com.bookmind.core.ai.AiRequest
import com.bookmind.core.ai.AiResult
import com.bookmind.core.ai.Faithfulness
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.OutputValidator
import com.bookmind.core.i18n.CoreText
import com.bookmind.core.text.Sentences
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The "Next lesson" study modules on top of [LearningAi]: each call fits the lesson into the model's prompt budget
 * (TextRank packing), asks the on-device model with a [LearningPrompts] template, cleans and validates the JSON,
 * retries once on broken output and otherwise falls back to [StudyFallbacks] — never to a paid service.
 */
class StudyAi(private val ai: LearningAi) {
    private val fallbacks = StudyFallbacks(ai.local)

    /** Builds a request for [lesson], packing its text into whatever room the provider leaves next to the template. */
    private suspend fun prompt(lesson: Lesson, build: (String) -> AiRequest): AiRequest {
        val empty = build("")
        val budget = ai.promptBudget(empty) ?: return build(lesson.text)
        return build(ContextPacker.pack(lesson.text, budget.coerceAtLeast(600), ai.local))
    }

    private fun <T> payload(request: AiRequest, serializer: KSerializer<T>, fallback: T) =
        request.copy(payload = mapOf("fallback" to AiJson.json.encodeToString(serializer, fallback)))

    private fun invalid(reason: String): Nothing = throw AiException.InvalidOutput(reason)

    // ---------- ÖĞREN ----------

    suspend fun smartNotes(lesson: Lesson, lang: String?): AiResult<SmartNotes> {
        val local = fallbacks.notes(lesson)
        val request = payload(prompt(lesson) { LearningPrompts.smartNotes(lesson.title, it, lang) }, SmartNotes.serializer(), local)
        return ai.run(request, SmartNotes.serializer(), { n ->
            if (n.mainIdea.isBlank() && n.keyPoints.isEmpty()) invalid("empty notes")
            val warnings = mutableListOf<String>()
            val quotes = n.quotes.filter { q -> OutputValidator.quoteOccursIn(q, lesson.text).also { if (!it) warnings += CoreText.of(lang).quoteUnverified } }
            val generated = listOf(n.mainIdea, n.takeaway) + n.keyPoints + n.concepts.map { it.explanation }
            Faithfulness.check(generated, listOf(lesson.text)).takeUnless { it.clean }?.let { warnings += CoreText.of(lang).unsupportedClaims(it.items.joinToString(", ")) }
            OutputValidator.Validated(n.copy(title = n.title.ifBlank { lesson.title }, quotes = quotes, concepts = n.concepts.filter { it.term.isNotBlank() }), warnings.distinct())
        }, { local }, lang = lang)
    }

    suspend fun podcast(lesson: Lesson, lang: String?): AiResult<PodcastScript> {
        val local = fallbacks.podcast(lesson, lang)
        val request = payload(prompt(lesson) { LearningPrompts.podcast(lesson.title, it, lang) }, PodcastScript.serializer(), local)
        return ai.run(request, PodcastScript.serializer(), { p ->
            // Speaker names vary ("Sunucu", "Host", "Uzman"…): the first voice becomes A, any other B.
            val first = p.lines.firstOrNull()?.speaker
            val lines = p.lines.filter { it.text.isNotBlank() }.map { it.copy(speaker = if (it.speaker == first || it.speaker.equals("A", true)) "A" else "B", text = it.text.trim()) }
            if (lines.size < 4) invalid("dialogue too short")
            OutputValidator.Validated(PodcastScript(p.title.ifBlank { lesson.title }, lines), emptyList())
        }, { local }, lang = lang, arrayKey = "lines")
    }

    suspend fun flashcards(lesson: Lesson, count: Int, lang: String?): AiResult<FlashcardDeck> {
        val local = fallbacks.flashcards(lesson, count, lang)
        val request = payload(prompt(lesson) { LearningPrompts.flashcards(lesson.title, it, count, lang) }, FlashcardDeck.serializer(), local)
        return ai.run(request, FlashcardDeck.serializer(), { d ->
            val cards = d.cards.filter { it.front.isNotBlank() && it.back.isNotBlank() }.distinctBy { it.front.lowercase() }
            if (cards.size < minOf(3, count)) invalid("too few cards")
            OutputValidator.Validated(FlashcardDeck(cards.take(count)), emptyList())
        }, { local }, lang = lang, arrayKey = "cards")
    }

    suspend fun prerequisiteGaps(lesson: Lesson, lang: String?): AiResult<GapDeck> {
        val local = fallbacks.prerequisiteGaps(lesson, lang)
        val request = payload(prompt(lesson) { LearningPrompts.prerequisiteGaps(lesson.title, it, lang) }, GapDeck.serializer(), local)
        return ai.run(request, GapDeck.serializer(), ::validGaps, { local }, lang = lang, arrayKey = "gaps")
    }

    /** Gap cards for the learner's own mistakes; not cached, mistakes change all the time. */
    suspend fun gapsFromMistakes(lesson: Lesson, mistakes: List<Mistake>, lang: String?): AiResult<GapDeck> {
        val local = fallbacks.gapsFromMistakes(lesson, mistakes, lang)
        val request = payload(prompt(lesson) { LearningPrompts.gapsFromMistakes(lesson.title, it, mistakes, lang) }, GapDeck.serializer(), local)
        return ai.runUncached(request, GapDeck.serializer(), ::validGaps, lang, arrayKey = "gaps") { local }
    }

    private fun validGaps(d: GapDeck): OutputValidator.Validated<GapDeck> {
        val gaps = d.gaps.filter { it.topic.isNotBlank() && it.explanation.isNotBlank() }
        if (gaps.isEmpty()) invalid("no gaps")
        return OutputValidator.Validated(GapDeck(gaps.take(6)), emptyList())
    }

    // ---------- PRATİK YAP ----------

    suspend fun exercises(lesson: Lesson, lang: String?): AiResult<ExerciseSet> {
        val local = fallbacks.exercises(lesson, lang)
        val request = payload(prompt(lesson) { LearningPrompts.exercises(lesson.title, it, lang) }, ExerciseSet.serializer(), local)
        return ai.run(request, ExerciseSet.serializer(), { e ->
            val cloze = e.cloze.mapNotNull { c ->
                if (c.answer.isBlank()) return@mapNotNull null
                // Accept a sentence that still contains the answer: blank it out ourselves.
                val sentence = when {
                    "___" in c.sentence -> c.sentence.replace(Regex("_{3,}"), "_____")
                    c.sentence.contains(c.answer, ignoreCase = true) -> c.sentence.replaceFirst(c.answer, "_____", ignoreCase = true)
                    else -> return@mapNotNull null
                }
                val options = (c.options.filter { it.isNotBlank() } + c.answer).distinctBy { it.lowercase() }.let { if (it.size >= 2) it.shuffled(java.util.Random(sentence.hashCode().toLong())) else emptyList() }
                ClozeItem(sentence, c.answer.trim(), options)
            }
            val matching = e.matching.filter { it.term.isNotBlank() && it.definition.isNotBlank() }.distinctBy { it.term.lowercase() }
            if (cloze.isEmpty() && matching.size < 2) invalid("no exercises")
            OutputValidator.Validated(ExerciseSet(cloze.take(5), if (matching.size >= 2) matching.take(5) else emptyList()), emptyList())
        }, { local }, lang = lang)
    }

    suspend fun multipleChoice(lesson: Lesson, count: Int, lang: String?): AiResult<McqSet> {
        val local = fallbacks.multipleChoice(lesson, count, lang)
        val request = payload(prompt(lesson) { LearningPrompts.multipleChoice(lesson.title, it, count, lang) }, McqSet.serializer(), local)
        return ai.run(request, LenientMcq, { m ->
            val qs = m.questions.mapNotNull { q ->
                val options = q.options.map { it.trim() }.filter { it.isNotBlank() }
                if (q.question.isBlank() || options.size < 2 || options.distinctBy { it.lowercase() }.size != options.size || q.answer !in options.indices) null
                else q.copy(options = options)
            }
            if (qs.isEmpty()) invalid("no valid questions")
            OutputValidator.Validated(McqSet(qs.take(count)), emptyList())
        }, { local }, lang = lang, arrayKey = "questions")
    }

    suspend fun trueFalse(lesson: Lesson, count: Int, lang: String?): AiResult<TrueFalseSet> {
        val local = fallbacks.trueFalse(lesson, count, lang)
        val request = payload(prompt(lesson) { LearningPrompts.trueFalse(lesson.title, it, count, lang) }, TrueFalseSet.serializer(), local)
        return ai.run(request, LenientTrueFalse, { s ->
            val items = s.items.filter { it.statement.isNotBlank() }.distinctBy { it.statement.lowercase() }
            if (items.size < minOf(2, count)) invalid("too few statements")
            OutputValidator.Validated(TrueFalseSet(items.take(count)), emptyList())
        }, { local }, lang = lang, arrayKey = "items")
    }

    // ---------- SINAV ----------

    suspend fun writtenExam(lesson: Lesson, count: Int, difficulty: Int, lang: String?): AiResult<WrittenExam> {
        val local = fallbacks.writtenExam(lesson, count, difficulty, lang)
        val request = payload(prompt(lesson) { LearningPrompts.writtenExam(lesson.title, it, count, difficulty, lang) }, WrittenExam.serializer(), local)
        // Not cached: a retake should be able to get new questions.
        return ai.runUncached(request, WrittenExam.serializer(), { e ->
            val qs = e.questions.filter { it.question.isNotBlank() && it.idealAnswer.isNotBlank() }
                .map { q -> if (q.keyPoints.isEmpty()) q.copy(keyPoints = ai.local.keyPoints(q.idealAnswer, "").take(4)) else q }
            if (qs.isEmpty()) invalid("no questions")
            OutputValidator.Validated(WrittenExam(qs.take(count)), emptyList())
        }, lang, arrayKey = "questions") { local }
    }

    /** Grades one written answer at a time (small prompt, small answer). */
    suspend fun gradeWritten(question: ExamQuestion, answer: String, lesson: Lesson, lang: String?): AiResult<WrittenGrade> {
        val local = fallbacks.gradeWritten(question, answer, lang)
        if (answer.isBlank()) return AiResult.Ok(local.copy(score = 0.0), com.bookmind.core.ai.GenerationInfo(engine = "local", model = "heuristic-1", promptVersion = LearningPrompts.Versions.WRITTEN_GRADE, aiGenerated = false), emptyList())
        val excerpt = relevantExcerpt(lesson.text, question.question + " " + question.idealAnswer, 1_500)
        val request = payload(LearningPrompts.gradeWritten(question, answer, excerpt, lang), WrittenGrade.serializer(), local)
        return ai.runUncached(request, WrittenGrade.serializer(), { g ->
            OutputValidator.Validated(g.copy(score = g.score.coerceIn(0.0, 100.0), idealAnswer = g.idealAnswer.ifBlank { question.idealAnswer }, missing = g.missing.filter { it.isNotBlank() }), emptyList())
        }, lang) { local }
    }

    /** One oral-exam reaction, limited to ~150 tokens so it can be spoken right away. */
    suspend fun oralTurn(question: ExamQuestion, answer: String, alreadyFollowedUp: Boolean, lang: String?): AiResult<OralTurn> {
        val local = fallbacks.oralTurn(question, answer, alreadyFollowedUp, lang)
        val request = payload(LearningPrompts.oralTurn(question, answer, alreadyFollowedUp, lang), OralTurn.serializer(), local)
        return ai.runUncached(request, OralTurn.serializer(), { o ->
            if (o.feedback.isBlank()) invalid("no feedback")
            val feedback = Sentences.split(o.feedback).take(2).joinToString(" ") { it.text }.ifBlank { o.feedback }
            OutputValidator.Validated(o.copy(score = o.score.coerceIn(0.0, 100.0), feedback = feedback, followUp = o.followUp?.takeIf { !alreadyFollowedUp && it.isNotBlank() }), emptyList())
        }, lang) { local }
    }

    /** The sentences of [text] that share the most words with [query], within [maxChars]. */
    private fun relevantExcerpt(text: String, query: String, maxChars: Int): String {
        val q = com.bookmind.core.ai.Lexicon.stems(query)
        val ranked = Sentences.split(text).sortedByDescending { s -> com.bookmind.core.ai.Lexicon.stems(s.text).count { it in q } }
        val picked = mutableListOf<Sentences.Span>()
        var used = 0
        for (s in ranked) { if (used + s.text.length > maxChars) break; picked += s; used += s.text.length + 1 }
        return picked.sortedBy { it.start }.joinToString(" ") { it.text }
    }
}

/** Accepts "answer": "B", "answer": "2" or the option text, as small models write all three. */
object LenientMcq : JsonTransformingSerializer<McqSet>(McqSet.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val qs = obj["questions"] as? JsonArray ?: return element
        return JsonObject(obj + ("questions" to JsonArray(qs.map { q ->
            val o = q as? JsonObject ?: return@map q
            val a = o["answer"] as? JsonPrimitive ?: return@map o
            if (a.intOrNull != null && !a.isString) return@map o
            val text = a.contentOrNull?.trim().orEmpty()
            val options = (o["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.orEmpty()
            val index = text.toIntOrNull()
                ?: Regex("^([A-Da-d])[).:]?$").find(text)?.let { it.groupValues[1].uppercase()[0] - 'A' }
                ?: options.indexOfFirst { it.equals(text, ignoreCase = true) }
            JsonObject(o + ("answer" to JsonPrimitive(index)))
        })))
    }
}

/** Accepts "isTrue": "Doğru" / "Yanlış" / "true" as well as a JSON boolean. */
object LenientTrueFalse : JsonTransformingSerializer<TrueFalseSet>(TrueFalseSet.serializer()) {
    private val yes = setOf("true", "doğru", "dogru", "evet", "yes", "d", "t")

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val items = obj["items"] as? JsonArray ?: return element
        return JsonObject(obj + ("items" to JsonArray(items.map { i ->
            val o = i as? JsonObject ?: return@map i
            val v = (o["isTrue"] ?: o["answer"] ?: o["correct"]) as? JsonPrimitive ?: return@map o
            JsonObject(o + ("isTrue" to JsonPrimitive(v.booleanOrNull ?: (v.contentOrNull?.trim()?.lowercase() in yes))))
        })))
    }
}
