package com.bookmind.app.data.repo

import com.bookmind.app.data.settings.AppLanguage
import androidx.room.withTransaction
import com.bookmind.app.data.db.AttemptSummary
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.LearningItemType
import com.bookmind.app.data.db.QuizAnswerEntity
import com.bookmind.app.data.db.QuizAttemptEntity
import com.bookmind.app.data.db.QuizEntity
import com.bookmind.app.data.db.QuizQuestionEntity
import com.bookmind.app.data.db.SessionType
import com.bookmind.app.data.db.XpEventEntity
import com.bookmind.app.data.files.BookFileStore
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.AppJson
import com.bookmind.app.util.newId
import com.bookmind.app.util.toJson
import com.bookmind.app.util.toJsonList
import com.bookmind.core.ai.AiResult
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.QuestionDraft
import com.bookmind.core.ai.QuestionType
import com.bookmind.core.learning.Gamification
import com.bookmind.core.quiz.QuizScorer
import com.bookmind.core.srs.FsrsScheduler
import com.bookmind.core.srs.Rating
import com.bookmind.core.text.Checksums
import com.bookmind.core.text.SourceRef
import com.bookmind.core.text.TextNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class QuizMode { STANDARD, WRONG_ONLY, NEAR_FORGETTING, MIXED_REVIEW, FULL_BOOK }

@Serializable
data class QuizConfig(
    val count: Int = 8,
    val difficulty: Int = 3,
    val chapterIds: List<String> = emptyList(),
    val timeLimitSeconds: Int? = null,
    val mode: QuizMode = QuizMode.STANDARD,
    val types: List<QuestionType> = listOf(
        QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE, QuestionType.FILL_BLANK, QuestionType.EXPLAIN_OWN_WORDS,
        QuestionType.MATCHING, QuestionType.ORDERING, QuestionType.SCENARIO, QuestionType.DEFINE_CONCEPT,
    ),
    val spoilerFree: Boolean = true,
)

class NotEnoughMaterialException : Exception("not enough material")

@Singleton
class QuizRepository @Inject constructor(
    private val db: BookMindDatabase,
    private val study: StudyRepository,
    private val reviews: ReviewRepository,
    private val sessions: SessionTracker,
    private val users: UserRepository,
    private val ai: LearningAi,
    private val files: BookFileStore,
    private val clock: AppClock,
) {
    data class Created(val quizId: String, val warnings: List<String>, val aiGenerated: Boolean)

    suspend fun create(bookId: String, config: QuizConfig): Created {
        val book = db.books().get(bookId) ?: error("book")
        val userId = users.userId()
        val now = clock.now()
        val questions: List<QuestionDraft>
        val warnings = mutableListOf<String>()
        var generatedBy = """{"engine":"history","promptVersion":"-","aiGenerated":false}"""
        var aiGenerated = false

        if (config.mode == QuizMode.WRONG_ONLY) {
            questions = db.quizzes().wrongQuestions(bookId).mapNotNull { decode(it.payload) }.take(config.count)
            if (questions.isEmpty()) throw NotEnoughMaterialException()
        } else {
            val chapterFilter = config.chapterIds.toSet().takeIf { it.isNotEmpty() && config.mode != QuizMode.FULL_BOOK }
            val chapters = study.chapterInputs(bookId, chapterFilter)
            var concepts = study.conceptInputs(bookId).filter { c -> chapters.any { it.index == c.chapterIndex } }
            if (config.mode == QuizMode.NEAR_FORGETTING || config.mode == QuizMode.MIXED_REVIEW) {
                // Concepts whose predicted recall is lowest (or due within two days) come first.
                val fsrs = FsrsScheduler()
                val soon = clock.now() + 2 * 86_400_000L
                val weakTitles = reviews.itemsForBook(bookId)
                    .filter { it.nextReviewAt != null && (it.nextReviewAt <= soon || (fsrs.retrievability(it.memoryState(), clock.now()) ?: 1.0) < 0.85) }
                    .sortedBy { it.nextReviewAt }
                    .map { TextNormalizer.asciiFold(it.prompt) }
                val near = concepts.filter { c -> weakTitles.any { it.contains(TextNormalizer.asciiFold(c.title)) } }
                concepts = if (config.mode == QuizMode.NEAR_FORGETTING) near.ifEmpty { concepts } else (near + concepts).distinct()
            }
            if (concepts.isEmpty() && chapters.isEmpty()) throw NotEnoughMaterialException()
            val r = ai.generateQuiz(study.bookInfo(book), chapters, concepts, config.count, config.difficulty, config.types.toSet(), config.spoilerFree, seed = (now % 10_000).toInt())
            when (r) {
                is AiResult.Ok -> { questions = r.value; warnings += r.warnings; generatedBy = r.info.toJson(); aiGenerated = r.info.aiGenerated }
                is AiResult.Failed -> throw NotEnoughMaterialException()
            }
        }

        val quiz = QuizEntity(newId(), userId, bookId, title(book.title, config), AppJson.encodeToString(QuizConfig.serializer(), config), generatedBy,
            warnings.takeIf { it.isNotEmpty() }?.toJsonList(), now)
        val chapters = db.chapters().forBook(bookId)
        val rows = questions.mapIndexed { i, q ->
            val chapter = q.chapterIndex?.let { idx -> chapters.firstOrNull { it.orderIndex == idx } }
            val item = q.conceptTitle?.let { reviews.conceptItem(bookId, TextNormalizer.asciiFold(it).trim()) }
            QuizQuestionEntity(
                id = newId(), quizId = quiz.id, learningItemId = item?.itemId, orderIndex = i, questionType = q.type.name, prompt = q.prompt,
                options = q.options.toJsonList(), expectedAnswer = q.answer, explanation = q.explanation,
                sourceReferences = listOfNotNull(chapter?.let { SourceRef(it.orderIndex, it.title, it.pageStart, it.pageEnd, quote = q.quote, verified = q.quote != null) }).toJson(),
                gradingRubric = q.rubric.toJsonList(), payload = AppJson.encodeToString(QuestionDraft.serializer(), q), needsReview = q.needsReview,
            )
        }
        db.withTransaction {
            db.quizzes().insertQuiz(quiz)
            db.quizzes().insertQuestions(rows)
        }
        return Created(quiz.id, warnings, aiGenerated)
    }

    private fun title(bookTitle: String, c: QuizConfig) = when (c.mode) {
        QuizMode.WRONG_ONLY -> "$bookTitle – " + AppLanguage.text.quizWrongOnly
        QuizMode.NEAR_FORGETTING -> "$bookTitle – " + AppLanguage.text.quizNearForgetting
        QuizMode.MIXED_REVIEW -> "$bookTitle – " + AppLanguage.text.quizMixed
        QuizMode.FULL_BOOK -> "$bookTitle – " + AppLanguage.text.quizFullBook
        QuizMode.STANDARD -> bookTitle
    }

    fun decode(payload: String): QuestionDraft? = runCatching { AppJson.decodeFromString(QuestionDraft.serializer(), payload) }.getOrNull()

    suspend fun quiz(id: String) = db.quizzes().quiz(id)
    suspend fun questions(quizId: String) = db.quizzes().questions(quizId)
    fun config(quiz: QuizEntity): QuizConfig = runCatching { AppJson.decodeFromString(QuizConfig.serializer(), quiz.configuration) }.getOrDefault(QuizConfig())

    data class Attempt(val attemptId: String, val sessionId: String)

    suspend fun startAttempt(quizId: String): Attempt {
        val quiz = db.quizzes().quiz(quizId) ?: error("quiz")
        val attempt = QuizAttemptEntity(newId(), users.userId(), quizId, clock.now())
        db.quizzes().insertAttempt(attempt)
        return Attempt(attempt.id, sessions.start(SessionType.QUIZ, quiz.bookId, """{"quizId":"$quizId"}"""))
    }

    data class Graded(val score: Double, val correct: Boolean, val feedback: String, val confidence: Double, val missingPoints: List<String>, val correctAnswer: String)

    /** Grades and stores one answer; feeds the result into spaced repetition. The answer key is never sent to the UI before this. */
    suspend fun answer(attemptId: String, question: QuizQuestionEntity, userAnswer: String, responseTimeMs: Long, selfRating: Rating? = null): Graded {
        val q = decode(question.payload) ?: error("question payload")
        val attempt = db.quizzes().attempt(attemptId) ?: error("attempt")
        val quiz = db.quizzes().quiz(attempt.quizId) ?: error("quiz")
        val book = db.books().get(quiz.bookId)
        val graded = if (QuizScorer.isObjective(q)) {
            val s = QuizScorer.score(q, userAnswer, AppLanguage.tag)
            Graded(s.score, s.correct, s.feedback, 1.0, emptyList(), QuizScorer.displayAnswer(q, AppLanguage.tag))
        } else {
            val excerpt = q.chapterIndex?.let { idx -> db.chapters().forBook(quiz.bookId).firstOrNull { it.orderIndex == idx } }?.let { files.readChapterText(it.textFile).take(6_000) }.orEmpty()
            when (val r = ai.gradeOpenAnswer(q.prompt, q.answer, q.rubric, userAnswer, AppLanguage.tag, excerpt, q.acceptableAnswers)) {
                is AiResult.Ok -> Graded(r.value.score, r.value.correct, r.value.feedback + (r.value.rationale.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""), r.value.confidence, r.value.missingPoints, q.answer)
                is AiResult.Failed -> Graded(0.0, false, AppLanguage.text.couldNotGrade(AppLanguage.text.aiError(r.error)), 0.0, emptyList(), q.answer)
            }
        }
        val answer = QuizAnswerEntity(newId(), attemptId, question.id, userAnswer.take(4_000), graded.score, graded.correct, graded.feedback,
            graded.confidence, if (QuizScorer.isObjective(q)) "rules" else "evaluator", responseTimeMs, clock.now())
        db.quizzes().upsertAnswer(answer)

        // Retrieval practice counts: linked concept cards are rescheduled with this evidence.
        question.learningItemId?.let { itemId ->
            reviews.itemsForBook(quiz.bookId).firstOrNull { it.itemId == itemId && (it.reviewCount > 0 || !graded.correct) }?.let { item ->
                reviews.submit(item, selfRating, correct = graded.correct, score = graded.score.takeIf { !QuizScorer.isObjective(q) },
                    response = userAnswer, responseTimeMs = responseTimeMs, level = q.type.recallLevel, source = "quiz")
            }
        }
        // Wrong answers become their own review cards, due immediately so they come back soon.
        if (!graded.correct) {
            reviews.ensureItem(
                bookId = quiz.bookId, type = LearningItemType.WRONG_ANSWER,
                dedupeKey = "wrong:${quiz.bookId}:${Checksums.sha256(q.prompt).take(16)}",
                prompt = q.prompt, expected = QuizScorer.displayAnswer(q, AppLanguage.tag), explanation = q.explanation,
                sourceRefs = question.sourceReferences, question = q.copy(needsReview = false), dueNow = true,
            )
        }
        val userId = users.userId()
        db.xp().insert(XpEventEntity(newId(), userId, Gamification.Kind.QUIZ_ANSWER.name,
            Gamification.xp(Gamification.XpEvent(Gamification.Kind.QUIZ_ANSWER, graded.correct, q.type.recallLevel)), clock.now(), answer.id))
        return graded
    }

    suspend fun finish(attempt: Attempt): Double {
        val a = db.quizzes().attempt(attempt.attemptId) ?: return 0.0
        val answers = db.quizzes().answers(a.id)
        val total = db.quizzes().questions(a.quizId).size.coerceAtLeast(1)
        val score = answers.sumOf { it.score } / total
        db.quizzes().upsertAttempt(a.copy(completedAt = clock.now(), score = score,
            resultMetadata = """{"answered":${answers.size},"total":$total,"correct":${answers.count { it.correct }}}"""))
        sessions.finish(attempt.sessionId)
        return score
    }

    data class ResultRow(val question: QuizQuestionEntity, val draft: QuestionDraft?, val answer: QuizAnswerEntity?)

    suspend fun results(attemptId: String): Pair<QuizAttemptEntity?, List<ResultRow>> {
        val attempt = db.quizzes().attempt(attemptId) ?: return null to emptyList()
        val answers = db.quizzes().answers(attemptId).associateBy { it.questionId }
        return attempt to db.quizzes().questions(attempt.quizId).map { ResultRow(it, decode(it.payload), answers[it.id]) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun recent(limit: Int = 5): Flow<List<AttemptSummary>> = flow { emit(users.userId()) }.flatMapLatest { db.quizzes().observeRecent(it, limit) }
}
