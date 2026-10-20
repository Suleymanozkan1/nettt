package com.bookmind.core.ai.study

import com.bookmind.core.ai.AiRequest
import com.bookmind.core.ai.AiTask
import com.bookmind.core.ai.PromptBuilder

/**
 * Prompt templates and JSON shapes for the "Next lesson" modules, written for small on-device models (Gemma,
 * Qwen 0.6–4B): one task per request, a literal example of the expected JSON, fixed item counts and short fields.
 * The book text always goes into a <book_text> block and is treated as untrusted data (see the base system prompt).
 * Output token limits are small on purpose; the provider additionally keeps the prompt within 60 % of the context.
 */
object LearningPrompts {
    object Versions {
        const val NOTES = "study-notes/1"
        const val PODCAST = "study-podcast/1"
        const val FLASHCARDS = "study-flashcards/1"
        const val GAPS = "study-gaps/1"
        const val GAPS_FROM_MISTAKES = "study-gaps-mistakes/1"
        const val EXERCISES = "study-exercises/1"
        const val MCQ = "study-mcq/1"
        const val TRUE_FALSE = "study-tf/1"
        const val WRITTEN_EXAM = "study-written-exam/1"
        const val WRITTEN_GRADE = "study-written-grade/1"
        const val ORAL_TURN = "study-oral-turn/1"
    }

    private fun request(version: String, lang: String?, user: String, maxTokens: Int, temperature: Double = 0.3) = AiRequest(
        task = AiTask.STUDY,
        promptVersion = version,
        system = PromptBuilder.baseSystem(lang),
        user = user.trimIndent(),
        maxOutputTokens = maxTokens,
        temperature = temperature,
    )

    private fun lesson(title: String, text: String) = PromptBuilder.bookBlock("lesson: $title", text)

    fun smartNotes(lesson: String, text: String, lang: String?) = request(Versions.NOTES, lang, """
        Turn this lesson into a structured study note (not a plain summary).
        Give: the main idea in one or two sentences, 4–6 key concepts with a one-sentence explanation each,
        4–6 key points as short bullet sentences, 1–2 striking quotes copied exactly from the text (max 200 characters),
        and one practical takeaway.
        Return JSON exactly like:
        {"title":"…","mainIdea":"…","concepts":[{"term":"…","explanation":"…"}],"keyPoints":["…"],"quotes":["…"],"takeaway":"…"}
        ${lesson(lesson, text)}
    """, maxTokens = 900)

    fun podcast(lesson: String, text: String, lang: String?) = request(Versions.PODCAST, lang, """
        Write a short radio dialogue about this lesson between A (a curious host) and B (an expert).
        A asks questions a learner would ask and reacts naturally; B explains clearly with one simple example.
        10–14 lines in total, alternating A and B, each line at most 2 sentences. Start with A greeting the listener and
        naming the topic; end with B summarising the single most important idea. Use only facts from the text.
        Return JSON exactly like:
        {"title":"…","lines":[{"speaker":"A","text":"…"},{"speaker":"B","text":"…"}]}
        ${lesson(lesson, text)}
    """, maxTokens = 1_000, temperature = 0.6)

    fun flashcards(lesson: String, text: String, count: Int, lang: String?) = request(Versions.FLASHCARDS, lang, """
        Create $count flashcards for the most important concepts and facts of this lesson.
        Front: a short question or a concept name. Back: the answer in at most two sentences, based on the text.
        Return JSON exactly like:
        {"cards":[{"front":"…","back":"…"}]}
        ${lesson(lesson, text)}
    """, maxTokens = 700)

    fun prerequisiteGaps(lesson: String, text: String, lang: String?) = request(Versions.GAPS, lang, """
        Which background ideas does a reader need to understand this lesson but the lesson itself does not explain?
        List 3–4 of them. For each: the topic, why it matters for this lesson (one sentence), a short explanation
        (two sentences) and a simple everyday example.
        Return JSON exactly like:
        {"gaps":[{"topic":"…","why":"…","explanation":"…","example":"…"}]}
        ${lesson(lesson, text)}
    """, maxTokens = 700)

    fun gapsFromMistakes(lesson: String, text: String, mistakes: List<Mistake>, lang: String?) = request(Versions.GAPS_FROM_MISTAKES, lang, """
        The learner got these questions wrong or left them empty:
        ${mistakes.take(6).mapIndexed { i, m -> "${i + 1}. Q: ${m.question.take(200)} | correct: ${m.expected.take(200)}" + (m.given?.let { " | learner: ${it.take(120)}" } ?: "") }.joinToString("\n")}
        For each mistake write one short card that fills the gap, using the lesson text: the topic, why the learner
        probably got it wrong (one sentence), the correct idea explained in two sentences, and a memorable example.
        Return JSON exactly like:
        {"gaps":[{"topic":"…","why":"…","explanation":"…","example":"…"}]}
        ${lesson(lesson, text)}
    """, maxTokens = 800)

    fun exercises(lesson: String, text: String, lang: String?) = request(Versions.EXERCISES, lang, """
        Create practice exercises from this lesson:
        - 4 fill-in-the-blank items: copy a key sentence from the text and replace one important word with "_____";
          give the missing word and 4 options (the answer plus 3 plausible words from the same lesson).
        - 4 matching pairs: a term and its short definition (max 12 words).
        Return JSON exactly like:
        {"cloze":[{"sentence":"… _____ …","answer":"…","options":["…","…","…","…"]}],"matching":[{"term":"…","definition":"…"}]}
        ${lesson(lesson, text)}
    """, maxTokens = 800)

    fun multipleChoice(lesson: String, text: String, count: Int, lang: String?) = request(Versions.MCQ, lang, """
        Create $count multiple-choice questions about this lesson. Each has exactly 4 options:
        one correct answer and 3 distractors that are related to the lesson and plausible but clearly wrong
        according to the text. "answer" is the index (0–3) of the correct option. Add a one-sentence "explanation"
        of why it is correct. Vary the position of the correct option.
        Return JSON exactly like:
        {"questions":[{"question":"…","options":["…","…","…","…"],"answer":2,"explanation":"…"}]}
        ${lesson(lesson, text)}
    """, maxTokens = 1_000, temperature = 0.4)

    fun trueFalse(lesson: String, text: String, count: Int, lang: String?) = request(Versions.TRUE_FALSE, lang, """
        Write $count statements about this lesson. About half are true (as the text says). The others are false:
        take a fact from the text and change one detail subtly (a cause, a direction, a quantity or a name).
        For each give one sentence explaining why it is true or false according to the text.
        Return JSON exactly like:
        {"items":[{"statement":"…","isTrue":false,"explanation":"…"}]}
        ${lesson(lesson, text)}
    """, maxTokens = 700, temperature = 0.4)

    fun writtenExam(lesson: String, text: String, count: Int, difficulty: Int, lang: String?) = request(Versions.WRITTEN_EXAM, lang, """
        Write $count open-ended exam questions about this lesson at difficulty $difficulty/5
        (1 = recall a definition, 3 = explain and connect ideas, 5 = apply to a new situation).
        For each give a model answer (2–3 sentences, based on the text) and 2–4 key points a good answer must contain.
        Return JSON exactly like:
        {"questions":[{"question":"…","idealAnswer":"…","keyPoints":["…","…"]}]}
        ${lesson(lesson, text)}
    """, maxTokens = 800, temperature = 0.4)

    fun gradeWritten(question: ExamQuestion, answer: String, excerpt: String, lang: String?) = request(Versions.WRITTEN_GRADE, lang, """
        Grade the learner's answer to this exam question from 0 to 100 using the key points and the lesson text.
        Give credit for correct ideas in the learner's own words; spelling does not matter.
        Question: ${question.question}
        Key points: ${question.keyPoints.joinToString("; ")}
        Model answer: ${question.idealAnswer}
        <user_answer>${PromptBuilder.sanitize(answer).take(2_000)}</user_answer>
        Return JSON exactly like:
        {"score":70,"missing":["…"],"feedback":"…","idealAnswer":"…"}
        ${PromptBuilder.bookBlock("lesson excerpt", excerpt)}
    """, maxTokens = 350, temperature = 0.1)

    /** Oral exam: kept to ~150 tokens so the spoken reply comes quickly. */
    fun oralTurn(question: ExamQuestion, answer: String, alreadyFollowedUp: Boolean, lang: String?) = request(Versions.ORAL_TURN, lang, """
        You are an oral examiner. Evaluate the learner's spoken answer (it may contain speech-recognition errors).
        Question: ${question.question}
        Key points: ${question.keyPoints.joinToString("; ")}
        <user_answer>${PromptBuilder.sanitize(answer).take(1_000)}</user_answer>
        Reply in at most 2 short sentences of feedback.
        ${if (alreadyFollowedUp) "Do not ask another follow-up; set followUp to null." else "If an important key point is missing, ask ONE short follow-up question about it (e.g. \"What if we look at it from … ?\"); otherwise set followUp to null."}
        Return JSON exactly like:
        {"score":60,"feedback":"…","followUp":null}
    """, maxTokens = 150, temperature = 0.2)
}
