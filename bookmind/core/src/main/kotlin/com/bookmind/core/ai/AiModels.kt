package com.bookmind.core.ai

import com.bookmind.core.text.SourceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bump when a prompt or schema changes; stored with every generated object and part of the cache key. */
object PromptVersions {
    const val BOOK_ANALYSIS = "book-analysis/3"
    const val CHAPTER_ANALYSIS = "chapter-analysis/3"
    const val SUMMARY = "summary/3"
    const val QUIZ = "quiz/3"
    const val TEACH = "teach/2"
    const val GRADE = "grade/2"
    const val STORY = "story/2"
    const val LINKS = "concept-links/2"
    const val SCHEMA_VERSION = 1
}

@Serializable
enum class BookGenre {
    @SerialName("informational") INFORMATIONAL,
    @SerialName("self_development") SELF_DEVELOPMENT,
    @SerialName("fiction") FICTION,
    @SerialName("biography") BIOGRAPHY,
    @SerialName("technical") TECHNICAL,
    @SerialName("mixed") MIXED,
}

@Serializable
enum class SummaryType {
    @SerialName("quick") QUICK,
    @SerialName("detailed") DETAILED,
    @SerialName("executive") EXECUTIVE,
    @SerialName("concept_map") CONCEPT_MAP,
    @SerialName("key_ideas") KEY_IDEAS,
    @SerialName("chapter_comparison") CHAPTER_COMPARISON,
    @SerialName("chapter") CHAPTER,
}

@Serializable
enum class SummaryLength { @SerialName("short") SHORT, @SerialName("medium") MEDIUM, @SerialName("long") LONG }

@Serializable
enum class TeachingLevel { BEGINNER, BASIC, INTERMEDIATE, ADVANCED, EXPERT }

/** Who produced a piece of content. Shown in the UI so users can tell book facts from generated material. */
@Serializable
data class GenerationInfo(
    val engine: String,
    val model: String? = null,
    val promptVersion: String,
    val schemaVersion: Int = PromptVersions.SCHEMA_VERSION,
    /** True for LLM output; false for the deterministic on-device engine. */
    val aiGenerated: Boolean,
    val inputHash: String? = null,
    val createdAt: Long = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

@Serializable
data class BookAnalysis(
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    val genre: BookGenre = BookGenre.INFORMATIONAL,
    val secondaryGenres: List<BookGenre> = emptyList(),
    val mainTopic: String = "",
    val mainMessage: String = "",
    val audience: String = "",
    val learningGoals: List<String> = emptyList(),
    val prerequisites: List<String> = emptyList(),
    val keyPeople: List<String> = emptyList(),
)

@Serializable
data class ConceptDraft(
    val title: String,
    val explanation: String,
    val importance: Int = 3,
    val difficulty: Int = 3,
    val isPrerequisite: Boolean = false,
    /** Short supporting excerpt from the chapter. Verified against the text before saving. */
    val quote: String? = null,
    val related: List<String> = emptyList(),
)

@Serializable
data class ChapterAnalysis(
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val concepts: List<ConceptDraft> = emptyList(),
    val claims: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val people: List<String> = emptyList(),
)

@Serializable
data class SummarySection(
    val heading: String,
    val body: String = "",
    val bullets: List<String> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
)

@Serializable
data class KeyIdea(
    val idea: String,
    val explanation: String = "",
    val whyItMatters: String = "",
    val support: String = "",
    val example: String = "",
    val dailyUse: String = "",
    val memoryTip: String = "",
    val source: SourceRef? = null,
)

@Serializable
data class ConceptMapNode(val id: String, val label: String, val parent: String? = null, val prerequisite: Boolean = false)

@Serializable
data class ConceptMapEdge(val from: String, val to: String, val relation: String, val causal: Boolean = false)

@Serializable
data class ConceptMap(val nodes: List<ConceptMapNode> = emptyList(), val edges: List<ConceptMapEdge> = emptyList())

@Serializable
data class SummaryDocument(
    val type: SummaryType,
    val title: String = "",
    val sections: List<SummarySection> = emptyList(),
    val ideas: List<KeyIdea> = emptyList(),
    val conceptMap: ConceptMap? = null,
    val notes: List<String> = emptyList(),
) {
    fun plainText(): String = buildString {
        if (title.isNotBlank()) appendLine(title).appendLine()
        sections.forEach { s ->
            appendLine(s.heading)
            if (s.body.isNotBlank()) appendLine(s.body)
            s.bullets.forEach { appendLine("• $it") }
            appendLine()
        }
        ideas.forEach { appendLine("• ${it.idea}: ${it.explanation}") }
    }.trim()
}

@Serializable
enum class QuestionType {
    @SerialName("multiple_choice") MULTIPLE_CHOICE,
    @SerialName("true_false") TRUE_FALSE,
    @SerialName("fill_blank") FILL_BLANK,
    @SerialName("short_answer") SHORT_ANSWER,
    @SerialName("open_ended") OPEN_ENDED,
    @SerialName("define_concept") DEFINE_CONCEPT,
    @SerialName("matching") MATCHING,
    @SerialName("ordering") ORDERING,
    @SerialName("scenario") SCENARIO,
    @SerialName("calculation") CALCULATION,
    @SerialName("find_error") FIND_ERROR,
    @SerialName("explain_own_words") EXPLAIN_OWN_WORDS,
    @SerialName("real_life") REAL_LIFE;

    /** Objective types are graded by exact rules; the others need the open-answer evaluator. */
    val isObjective: Boolean
        get() = this in setOf(MULTIPLE_CHOICE, TRUE_FALSE, FILL_BLANK, MATCHING, ORDERING, REAL_LIFE, FIND_ERROR, CALCULATION, SCENARIO)

    /** Which active-recall level this question type measures (section 10 of the product spec). */
    val recallLevel: RecallLevel
        get() = when (this) {
            MULTIPLE_CHOICE, TRUE_FALSE, MATCHING -> RecallLevel.RECOGNITION
            FILL_BLANK, SHORT_ANSWER, DEFINE_CONCEPT, ORDERING -> RecallLevel.RECALL
            OPEN_ENDED, EXPLAIN_OWN_WORDS, FIND_ERROR -> RecallLevel.EXPLANATION
            SCENARIO, REAL_LIFE -> RecallLevel.TRANSFER
            CALCULATION -> RecallLevel.APPLICATION
        }
}

@Serializable
enum class RecallLevel { RECOGNITION, RECALL, EXPLANATION, TRANSFER, APPLICATION }

@Serializable
data class MatchPair(val left: String, val right: String)

@Serializable
data class QuestionDraft(
    val type: QuestionType,
    val prompt: String,
    val options: List<String> = emptyList(),
    /** Canonical answer. MCQ: the option text. True/false: "true"/"false". Ordering: items joined by " | ". */
    val answer: String = "",
    val acceptableAnswers: List<String> = emptyList(),
    val pairs: List<MatchPair> = emptyList(),
    val explanation: String = "",
    val chapterIndex: Int? = null,
    val quote: String? = null,
    val conceptTitle: String? = null,
    val difficulty: Int = 3,
    /** Key points an open answer should cover; drives the rubric. */
    val rubric: List<String> = emptyList(),
    val needsReview: Boolean = false,
)

@Serializable
data class QuizDraft(val questions: List<QuestionDraft> = emptyList())

@Serializable
data class TeachTurn(
    val message: String,
    val question: String? = null,
    val expectsAnswer: Boolean = question != null,
    val example: String? = null,
    val source: SourceRef? = null,
    val suggestedLevel: TeachingLevel? = null,
    val understood: Boolean? = null,
)

@Serializable
data class GradeResult(
    val score: Double,
    val correct: Boolean,
    val feedback: String,
    val coveredPoints: List<String> = emptyList(),
    val missingPoints: List<String> = emptyList(),
    val misconceptions: List<String> = emptyList(),
    /** 0..1. Below [LOW_CONFIDENCE] the UI says the grade is approximate. */
    val confidence: Double = 0.5,
    val rationale: String = "",
) {
    companion object { const val LOW_CONFIDENCE = 0.6 }
}

@Serializable
data class StoryDraft(
    val title: String,
    val body: String,
    val kind: String = "story",
    /** True only if the example is taken from the book; generated stories are labelled as such. */
    val fromBook: Boolean = false,
    val source: SourceRef? = null,
    val questions: List<QuestionDraft> = emptyList(),
)

@Serializable
data class ConceptLinkDraft(
    val fromConcept: String,
    val toConcept: String,
    val relation: String,
    val explanation: String = "",
    val confidence: Double = 0.5,
)

@Serializable
data class ConceptLinksDraft(val links: List<ConceptLinkDraft> = emptyList())
