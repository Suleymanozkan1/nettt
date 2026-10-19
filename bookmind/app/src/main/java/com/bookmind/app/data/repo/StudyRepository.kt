package com.bookmind.app.data.repo

import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.data.db.BookEntity
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.ChapterEntity
import com.bookmind.app.data.db.ConceptEntity
import com.bookmind.app.data.db.SessionType
import com.bookmind.app.data.db.StoryEntity
import com.bookmind.app.data.db.SummaryEntity
import com.bookmind.app.data.db.TeachMessageEntity
import com.bookmind.app.data.db.XpEventEntity
import com.bookmind.app.data.files.BookFileStore
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.AppJson
import com.bookmind.app.util.newId
import com.bookmind.app.util.parseRefs
import com.bookmind.app.util.toJson
import com.bookmind.app.util.toJsonList
import com.bookmind.core.ai.AiException
import com.bookmind.core.ai.AiResult
import com.bookmind.core.ai.BookGenre
import com.bookmind.core.ai.ChapterAnalysis
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.LocalHeuristicEngine
import com.bookmind.core.ai.QuestionDraft
import com.bookmind.core.ai.SummaryDocument
import com.bookmind.core.ai.SummaryLength
import com.bookmind.core.ai.SummaryType
import com.bookmind.core.ai.TeachTurn
import com.bookmind.core.ai.TeachingLevel
import com.bookmind.core.learning.Gamification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

class GenerationException(val error: AiException) : Exception(AppLanguage.text.aiError(error))

/** Summaries, concepts, the AI tutor and story/example generation. */
@Singleton
class StudyRepository @Inject constructor(
    private val db: BookMindDatabase,
    private val files: BookFileStore,
    private val ai: LearningAi,
    private val users: UserRepository,
    private val sessions: SessionTracker,
    private val reviews: ReviewRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    fun bookInfo(book: BookEntity) = LearningAi.Book(
        book.title, book.author, AppLanguage.tag,
        book.category?.let { runCatching { BookGenre.valueOf(it) }.getOrNull() } ?: BookGenre.INFORMATIONAL,
    )

    suspend fun chapterInputs(bookId: String, only: Set<String>? = null): List<LocalHeuristicEngine.ChapterInput> = withContext(Dispatchers.IO) {
        db.chapters().forBook(bookId).filter { only == null || it.id in only }
            .map { LocalHeuristicEngine.ChapterInput(it.orderIndex, it.title, files.readChapterText(it.textFile), it.pageStart) }
    }

    suspend fun conceptInputs(bookId: String): List<LocalHeuristicEngine.ConceptInput> {
        val chapters = db.chapters().forBook(bookId).associateBy { it.id }
        return db.concepts().forBook(bookId).map { c ->
            LocalHeuristicEngine.ConceptInput(c.title, c.explanation, chapters[c.chapterId]?.orderIndex ?: 0, parseRefs(c.sourceReferences).firstOrNull()?.quote)
        }
    }

    // ---------- Summaries ----------

    fun summary(bookId: String, type: SummaryType, length: SummaryLength, chapterId: String?): Flow<SummaryEntity?> =
        db.summaries().observe(bookId, type.name, length.name, chapterId)

    /** Generates (or regenerates) a summary and stores it. Partial book analysis is fine: only READY chapters are used. */
    suspend fun generateSummary(bookId: String, type: SummaryType, length: SummaryLength, chapterId: String? = null): SummaryEntity {
        val book = db.books().get(bookId) ?: error("book not found")
        val chapterRows = db.chapters().forBook(bookId).filter { chapterId == null || it.id == chapterId }
        val chapters = chapterInputs(bookId, chapterRows.map { it.id }.toSet())
        val digests = chapterRows.associate { it.orderIndex to digest(it) }
        val concepts = conceptInputs(bookId).filter { c -> chapters.any { it.index == c.chapterIndex } }
        val result = ai.summarize(bookInfo(book), type, length, chapters, digests, concepts, spoilerFree = settings.current().spoilerFree && type != SummaryType.CHAPTER)
        val (doc, info, warnings) = when (result) {
            is AiResult.Ok -> Triple(result.value, result.info, result.warnings)
            is AiResult.Failed -> throw GenerationException(result.error)
        }
        val now = clock.now()
        val entity = SummaryEntity(
            id = newId(), bookId = bookId, chapterId = chapterId, summaryType = type.name, length = length.name,
            content = AppJson.encodeToString(SummaryDocument.serializer(), doc),
            sourceReferences = (doc.sections.flatMap { it.sources } + doc.ideas.mapNotNull { it.source }).toJson(),
            modelMetadata = info.toJson(), warnings = warnings.toJsonList().takeIf { warnings.isNotEmpty() }, createdAt = now, updatedAt = now,
        )
        db.summaries().upsert(entity)
        return entity
    }

    private fun digest(ch: ChapterEntity): String {
        val a = ch.analysisJson?.let { runCatching { AppJson.decodeFromString(ChapterAnalysis.serializer(), it) }.getOrNull() }
        return buildString {
            append(ch.summary.orEmpty())
            a?.keyPoints?.takeIf { it.isNotEmpty() }?.let { append("\nKey points: ").append(it.joinToString("; ")) }
            a?.claims?.takeIf { it.isNotEmpty() }?.let { append("\nClaims: ").append(it.joinToString("; ")) }
            a?.examples?.takeIf { it.isNotEmpty() }?.let { append("\nExamples: ").append(it.joinToString("; ")) }
        }
    }

    suspend fun editSummary(id: String, text: String?) {
        db.summaries().setUserText(id, text?.takeIf { it.isNotBlank() }, clock.now())
    }

    // ---------- Concepts ----------

    fun concepts(bookId: String): Flow<List<ConceptEntity>> = db.concepts().observeForBook(bookId)
    suspend fun concept(id: String) = db.concepts().get(id)

    suspend fun toggleFavorite(concept: ConceptEntity) {
        db.concepts().setFavorite(concept.id, !concept.isFavorite, clock.now())
    }

    suspend fun editConcept(concept: ConceptEntity, title: String, explanation: String) {
        db.concepts().upsert(concept.copy(title = title.trim(), explanation = explanation.trim(), userEdited = true, updatedAt = clock.now()))
    }

    // ---------- Tutor ----------

    data class TeachState(val sessionId: String, val messages: List<TeachMessageEntity>)

    private fun turnText(t: TeachTurn): String = buildString {
        append(t.message)
        t.question?.let { append("\n\n").append(it) }
    }

    suspend fun startTeaching(bookId: String, conceptId: String, level: TeachingLevel, style: String): String {
        val concept = db.concepts().get(conceptId) ?: error("concept")
        val sessionId = sessions.start(SessionType.TEACH, bookId, """{"conceptId":"$conceptId","level":"${level.name}"}""")
        val turn = teachTurn(bookId, concept, sessionId, step = 0, level, style, null)
        addTutorMessage(sessionId, turn)
        return sessionId
    }

    fun messages(sessionId: String) = db.sessions().observeMessages(sessionId)

    /** Sends the learner's answer and returns the tutor's reply. The session ends after the wrap-up turn. */
    suspend fun reply(bookId: String, conceptId: String, sessionId: String, answer: String, level: TeachingLevel, style: String): TeachTurn {
        val concept = db.concepts().get(conceptId) ?: error("concept")
        db.sessions().insertMessage(TeachMessageEntity(newId(), sessionId, "learner", answer.take(4_000), null, clock.now()))
        val history = db.sessions().messages(sessionId)
        val step = history.count { it.role == "learner" }
        val lastExpected = history.lastOrNull { it.role == "tutor" }?.metadata
            ?.let { runCatching { AppJson.decodeFromString(TeachTurn.serializer(), it) }.getOrNull() }
            ?.example?.let { ex -> ai.local.termRegex(concept.title).find(ex)?.value }
        val turn = teachTurn(bookId, concept, sessionId, step, level, style, answer, lastExpected)
        addTutorMessage(sessionId, turn)
        if (!turn.expectsAnswer || step >= 6) finishTeaching(bookId, concept, sessionId, turn.understood)
        return turn
    }

    /** Re-explains the current point in a different style without counting as an answer. */
    suspend fun restyle(bookId: String, conceptId: String, sessionId: String, level: TeachingLevel, style: String): TeachTurn {
        val concept = db.concepts().get(conceptId) ?: error("concept")
        val step = db.sessions().messages(sessionId).count { it.role == "learner" }.coerceAtLeast(1)
        val turn = teachTurn(bookId, concept, sessionId, step, level, style, null)
        addTutorMessage(sessionId, turn)
        return turn
    }

    private suspend fun teachTurn(bookId: String, concept: ConceptEntity, sessionId: String, step: Int, level: TeachingLevel, style: String, answer: String?, expected: String? = null): TeachTurn {
        val book = db.books().get(bookId) ?: error("book")
        val chapter = db.chapters().get(concept.chapterId)
        val chapterInput = chapter?.let { LocalHeuristicEngine.ChapterInput(it.orderIndex, it.title, files.readChapterText(it.textFile), it.pageStart) }
        val history = db.sessions().messages(sessionId).map { it.role to it.content }
        val ctx = LearningAi.TeachContext(
            step = step,
            concept = LocalHeuristicEngine.ConceptInput(concept.title, concept.explanation, chapter?.orderIndex ?: 0, parseRefs(concept.sourceReferences).firstOrNull()?.quote),
            chapter = chapterInput, level = level, style = style, history = history, lastAnswer = answer, lastExpected = expected,
        )
        return when (val r = ai.teach(bookInfo(book), ctx)) {
            is AiResult.Ok -> r.value
            is AiResult.Failed -> throw GenerationException(r.error)
        }
    }

    private suspend fun addTutorMessage(sessionId: String, turn: TeachTurn) {
        db.sessions().insertMessage(TeachMessageEntity(newId(), sessionId, "tutor", turnText(turn), AppJson.encodeToString(TeachTurn.serializer(), turn), clock.now()))
    }

    private suspend fun finishTeaching(bookId: String, concept: ConceptEntity, sessionId: String, understood: Boolean?) {
        sessions.finish(sessionId)
        val userId = users.userId()
        if (db.xp().countRef(userId, Gamification.Kind.TEACH_SESSION_COMPLETED.name, sessionId) == 0) {
            db.xp().insert(XpEventEntity(newId(), userId, Gamification.Kind.TEACH_SESSION_COMPLETED.name, Gamification.xp(Gamification.XpEvent(Gamification.Kind.TEACH_SESSION_COMPLETED)), clock.now(), sessionId))
        }
        // The taught concept enters the review queue; if the learner struggled it is due right away.
        reviews.ensureItem(
            bookId = bookId, type = com.bookmind.app.data.db.LearningItemType.CONCEPT,
            dedupeKey = "concept:$bookId:${concept.normalizedTitle}",
            prompt = AppLanguage.text.whatIs(concept.title), expected = concept.explanation, explanation = null,
            sourceRefs = concept.sourceReferences, conceptId = concept.id, dueNow = understood == false,
        )
    }

    // ---------- Stories ----------

    fun stories(conceptId: String) = db.stories().observeForConcept(conceptId)

    suspend fun generateStory(bookId: String, conceptId: String, kind: String): StoryEntity {
        val book = db.books().get(bookId) ?: error("book")
        val concept = db.concepts().get(conceptId) ?: error("concept")
        val chapter = db.chapters().get(concept.chapterId)
        val input = chapter?.let { LocalHeuristicEngine.ChapterInput(it.orderIndex, it.title, files.readChapterText(it.textFile), it.pageStart) }
        val sid = sessions.start(SessionType.STORY, bookId)
        val result = ai.story(bookInfo(book), LocalHeuristicEngine.ConceptInput(concept.title, concept.explanation, chapter?.orderIndex ?: 0), input, kind)
        sessions.finish(sid)
        val (story, info) = when (result) {
            is AiResult.Ok -> result.value to result.info
            is AiResult.Failed -> throw GenerationException(result.error)
        }
        val entity = StoryEntity(
            newId(), bookId, conceptId, story.kind, story.title, story.body, story.fromBook,
            story.source?.let { AppJson.encodeToString(com.bookmind.core.text.SourceRef.serializer(), it) },
            AppJson.encodeToString(ListSerializer(QuestionDraft.serializer()), story.questions), info.toJson(), clock.now(),
        )
        db.stories().insert(entity)
        return entity
    }

    fun storyQuestions(story: StoryEntity): List<QuestionDraft> =
        runCatching { AppJson.decodeFromString(ListSerializer(QuestionDraft.serializer()), story.questionsJson) }.getOrDefault(emptyList())
}
