package com.bookmind.core.ai

import kotlinx.coroutines.delay

/**
 * Network-free provider for development and tests. By default it answers every task with valid JSON built by
 * the on-device engine, so the full AI pipeline (prompting → parsing → validation → storage) runs without an
 * API key. Tests can script raw responses or failures to exercise error handling.
 */
class MockAiProvider(
    private val latencyMs: Long = 0,
    private val script: ((AiRequest) -> String)? = null,
) : AiProvider {
    override val id: String = "mock"
    private val local = LocalHeuristicEngine()
    val requests = mutableListOf<AiRequest>()

    /** Queue of failures to throw before answering normally. */
    val failures = ArrayDeque<AiException>()

    override suspend fun complete(request: AiRequest): AiResponse {
        requests += request
        if (latencyMs > 0) delay(latencyMs)
        failures.removeFirstOrNull()?.let { throw it }
        val text = script?.invoke(request) ?: defaultAnswer(request)
        return AiResponse(text, "mock-1", inputTokens = request.user.length / 4, outputTokens = text.length / 4, costMicros = 0)
    }

    private fun defaultAnswer(r: AiRequest): String {
        val p = r.payload
        val j = AiJson.json
        val chapters = p["chapters"]?.let { j.decodeFromString(MockPayload.Chapters.serializer(), it).items }.orEmpty()
            .map { LocalHeuristicEngine.ChapterInput(it.index, it.title, it.text) }
        val concepts = p["concepts"]?.let { j.decodeFromString(MockPayload.Concepts.serializer(), it).items }.orEmpty()
            .map { LocalHeuristicEngine.ConceptInput(it.title, it.explanation, it.chapterIndex, it.quote) }
        return when (r.task) {
            AiTask.BOOK_ANALYSIS -> j.encodeToString(BookAnalysis.serializer(), local.bookAnalysis(p["title"], p["author"], p["lang"], chapters))
            AiTask.CHAPTER_ANALYSIS -> j.encodeToString(ChapterAnalysis.serializer(), local.chapterAnalysis(chapters.first()))
            AiTask.SUMMARY -> j.encodeToString(
                SummaryDocument.serializer(),
                local.summary(SummaryType.valueOf(p["type"] ?: "QUICK"), p["title"].orEmpty(), chapters, concepts, SummaryLength.valueOf(p["length"] ?: "MEDIUM")),
            )
            AiTask.QUIZ -> j.encodeToString(
                QuizDraft.serializer(),
                QuizDraft(local.quiz(concepts, chapters, p["count"]?.toInt() ?: 5, p["types"]?.split(',')?.map { QuestionType.valueOf(it) }?.toSet() ?: QuestionType.entries.toSet())),
            )
            AiTask.TEACH -> j.encodeToString(
                TeachTurn.serializer(),
                local.teach(p["step"]?.toInt() ?: 0, concepts.first(), chapters.firstOrNull(), p["answer"], p["expected"], p["style"]),
            )
            AiTask.GRADE -> j.encodeToString(GradeResult.serializer(), local.grade(p["modelAnswer"].orEmpty(), p["rubric"]?.split('\n').orEmpty().filter { it.isNotBlank() }, p["answer"].orEmpty()))
            AiTask.STORY -> j.encodeToString(StoryDraft.serializer(), local.story(concepts.first(), chapters.firstOrNull()))
            AiTask.ANSWER -> j.encodeToString(
                BookAnswer.serializer(),
                local.answer(p["question"].orEmpty(), p["passages"]?.let { j.decodeFromString(MockPayload.Passages.serializer(), it).items }.orEmpty()
                    .map { com.bookmind.core.search.Passage(it.chapterIndex, it.title, 0, it.text.length, it.text) }),
            )
            // Study modules pass their AI-free result along; the mock echoes it as a well-formed model answer.
            AiTask.STUDY -> p["fallback"] ?: "{}"
            AiTask.CONCEPT_LINKS -> j.encodeToString(
                ConceptLinksDraft.serializer(),
                local.links(p["conceptsByBook"]?.let { j.decodeFromString(MockPayload.ConceptsByBook.serializer(), it).map }.orEmpty()),
            )
        }
    }
}

object MockPayload {
    @kotlinx.serialization.Serializable
    data class Chapter(val index: Int, val title: String, val text: String)

    @kotlinx.serialization.Serializable
    data class Chapters(val items: List<Chapter>)

    @kotlinx.serialization.Serializable
    data class Concept(val title: String, val explanation: String, val chapterIndex: Int, val quote: String? = null)

    @kotlinx.serialization.Serializable
    data class Concepts(val items: List<Concept>)

    @kotlinx.serialization.Serializable
    data class ConceptsByBook(val map: Map<String, List<String>>)

    @kotlinx.serialization.Serializable
    data class Passage(val chapterIndex: Int, val title: String, val text: String)

    @kotlinx.serialization.Serializable
    data class Passages(val items: List<Passage>)
}
