package com.bookmind.app.ui.study

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookmind.app.ai.GeminiConfig
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.data.db.SessionType
import com.bookmind.app.data.repo.ReviewRepository
import com.bookmind.app.data.repo.SessionTracker
import com.bookmind.app.data.repo.StudyHubRepository
import com.bookmind.app.data.repo.StudyModule
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.core.ai.AiResult
import com.bookmind.core.ai.GenerationInfo
import com.bookmind.core.ai.QuestionDraft
import com.bookmind.core.ai.QuestionType
import com.bookmind.core.ai.study.ClozeItem
import com.bookmind.core.ai.study.ExamQuestion
import com.bookmind.core.ai.study.Flashcard
import com.bookmind.core.ai.study.Lesson
import com.bookmind.core.ai.study.McqQuestion
import com.bookmind.core.ai.study.OralTurn
import com.bookmind.core.ai.study.TrueFalseItem
import com.bookmind.core.ai.study.WrittenGrade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the hub shows about Gemini. */
enum class AiStatus { READY, NO_KEY, OFF }

@HiltViewModel
class StudyHubViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: StudyHubRepository,
    private val reviews: ReviewRepository,
    settings: SettingsRepository,
    gemini: GeminiConfig,
) : ViewModel() {
    val bookId: String = handle["bookId"]!!

    data class State(
        val loading: Boolean = true,
        val lessons: List<StudyHubRepository.LessonInfo> = emptyList(),
        val selected: Int = 0,
        val dueForBook: Int = 0,
    ) {
        val lesson get() = lessons.firstOrNull { it.index == selected }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var chosen: Int? = handle.get<String>("lesson")?.toIntOrNull()

    val ai: StateFlow<AiStatus> = settings.settings.map { s ->
        when {
            !gemini.configured -> AiStatus.NO_KEY
            s.aiMode == AiMode.GEMINI -> AiStatus.READY
            else -> AiStatus.OFF
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiStatus.READY)

    init {
        viewModelScope.launch { repo.version.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val lessons = repo.lessons(bookId)
        // The hub opens on the next lesson and then stays on it while the learner works through its modules.
        val selected = chosen?.takeIf { c -> lessons.any { it.index == c } } ?: repo.nextLesson(lessons)?.index ?: 0
        chosen = selected
        val due = reviews.todayQueue(bookId).size
        _state.value = State(loading = false, lessons = lessons, selected = selected, dueForBook = due)
    }

    fun select(index: Int) {
        chosen = index
        _state.value = _state.value.copy(selected = index)
    }
}

/** Loaded content of one ÖĞREN / PRATİK YAP module. */
data class ModuleContent<T>(val value: T, val info: GenerationInfo, val warnings: List<String>)

@HiltViewModel
class StudyModuleViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: StudyHubRepository,
    private val sessions: SessionTracker,
) : ViewModel() {
    val bookId: String = handle["bookId"]!!
    val lessonIndex: Int = handle.get<String>("lesson")?.toIntOrNull() ?: 0
    val module: StudyModule = StudyModule.valueOf(handle["module"]!!)

    data class State(
        val lesson: Lesson? = null,
        val content: ModuleContent<*>? = null,
        /** For knowledge gaps: true when the cards explain the learner's own mistakes. */
        val fromMistakes: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var sessionId: String? = null

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = State()
        try {
            val lesson = repo.lesson(bookId, lessonIndex)
            _state.value = State(lesson = lesson)
            if (sessionId == null) sessionId = sessions.start(if (module.ordinal <= StudyModule.GAPS.ordinal) SessionType.READ else SessionType.QUIZ, bookId)
            val lang = repo.lang
            val ai = repo.ai
            var fromMistakes = false
            val result: AiResult<*> = when (module) {
                StudyModule.NOTES -> ai.smartNotes(lesson, lang)
                StudyModule.PODCAST -> ai.podcast(lesson, lang)
                StudyModule.FLASHCARDS -> ai.flashcards(lesson, 8, lang)
                StudyModule.GAPS -> {
                    val mistakes = repo.mistakes(bookId, lesson)
                    fromMistakes = mistakes.isNotEmpty()
                    if (fromMistakes) ai.gapsFromMistakes(lesson, mistakes, lang) else ai.prerequisiteGaps(lesson, lang)
                }
                StudyModule.EXERCISES -> ai.exercises(lesson, lang)
                StudyModule.MCQ -> ai.multipleChoice(lesson, 6, lang)
                StudyModule.TRUE_FALSE -> ai.trueFalse(lesson, 6, lang)
                StudyModule.WRITTEN_EXAM, StudyModule.ORAL_EXAM -> error("exams have their own screens")
            }
            _state.value = when (result) {
                is AiResult.Ok -> State(lesson, ModuleContent(result.value, result.info, result.warnings), fromMistakes)
                is AiResult.Failed -> State(lesson, error = AppLanguage.text.aiError(result.error))
            }
            // Reading notes or a gap deck counts as done once opened; the others when the learner finishes them.
            if (module == StudyModule.NOTES || module == StudyModule.GAPS) done()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = _state.value.copy(error = e.message ?: e.toString())
        }
    }

    fun done() = repo.markDone(bookId, lessonIndex, module)

    private fun review(prompt: String, expected: String, explanation: String?, question: QuestionDraft? = null) {
        val lesson = _state.value.lesson ?: return
        viewModelScope.launch { repo.addToReview(bookId, lesson, prompt, expected, explanation, question) }
    }

    fun flashcardAgain(card: Flashcard) = review(card.front, card.back, null)

    fun wrongCloze(item: ClozeItem) = review(item.sentence, item.answer, null,
        QuestionDraft(QuestionType.FILL_BLANK, item.sentence, item.options, item.answer))

    fun wrongMatch(term: String, definition: String) = review(term, definition, null)

    fun wrongMcq(q: McqQuestion) = review(q.question, q.options[q.answer], q.explanation,
        QuestionDraft(QuestionType.MULTIPLE_CHOICE, q.question, q.options, q.options[q.answer], explanation = q.explanation))

    fun wrongTrueFalse(item: TrueFalseItem) = review(item.statement, item.isTrue.toString(), item.explanation,
        QuestionDraft(QuestionType.TRUE_FALSE, item.statement, answer = item.isTrue.toString(), explanation = item.explanation))

    override fun onCleared() { sessionId?.let(sessions::finishDetached) }
}

/** Written mock exam: open questions, typed answers, each graded separately, then a report card. */
@HiltViewModel
class WrittenExamViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: StudyHubRepository,
    private val sessions: SessionTracker,
) : ViewModel() {
    val bookId: String = handle["bookId"]!!
    val lessonIndex: Int = handle.get<String>("lesson")?.toIntOrNull() ?: 0
    val count: Int = handle.get<String>("count")?.toIntOrNull() ?: 5
    val difficulty: Int = handle.get<String>("difficulty")?.toIntOrNull() ?: 3

    sealed interface Phase {
        data object Preparing : Phase
        data class Answering(val questions: List<ExamQuestion>, val info: GenerationInfo, val warnings: List<String>) : Phase
        data class Grading(val done: Int, val total: Int) : Phase
        data class Report(val questions: List<ExamQuestion>, val answers: List<String>, val grades: List<WrittenGrade>, val aiGraded: Boolean) : Phase {
            val score: Int get() = if (grades.isEmpty()) 0 else grades.sumOf { it.score }.div(grades.size).toInt()
        }
        data class Error(val message: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Preparing)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    val answers = MutableStateFlow<List<String>>(emptyList())
    private var lesson: Lesson? = null
    private var sessionId: String? = null

    init { prepare() }

    fun prepare() = viewModelScope.launch {
        _phase.value = Phase.Preparing
        try {
            val l = repo.lesson(bookId, lessonIndex).also { lesson = it }
            if (sessionId == null) sessionId = sessions.start(SessionType.QUIZ, bookId)
            _phase.value = when (val r = repo.ai.writtenExam(l, count, difficulty, repo.lang)) {
                is AiResult.Ok -> { answers.value = List(r.value.questions.size) { "" }; Phase.Answering(r.value.questions, r.info, r.warnings) }
                is AiResult.Failed -> Phase.Error(AppLanguage.text.aiError(r.error))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _phase.value = Phase.Error(e.message ?: e.toString())
        }
    }

    fun answer(index: Int, text: String) { answers.value = answers.value.toMutableList().also { if (index in it.indices) it[index] = text } }

    /** Grades the answers one by one (small prompts keep a phone model fast) and builds the report. */
    fun submit() = viewModelScope.launch {
        val p = _phase.value as? Phase.Answering ?: return@launch
        val l = lesson ?: return@launch
        val given = answers.value
        val grades = mutableListOf<WrittenGrade>()
        var aiGraded = false
        p.questions.forEachIndexed { i, q ->
            _phase.value = Phase.Grading(i, p.questions.size)
            val answer = given.getOrElse(i) { "" }
            when (val r = repo.ai.gradeWritten(q, answer, l, repo.lang)) {
                is AiResult.Ok -> { grades += r.value; aiGraded = aiGraded || r.info.aiGenerated }
                is AiResult.Failed -> grades += WrittenGrade(0.0, q.keyPoints, AppLanguage.text.aiError(r.error), q.idealAnswer)
            }
            // Weak answers go to the review queue with the model answer.
            if (grades.last().score < 60) repo.addToReview(bookId, l, q.question, q.idealAnswer, grades.last().feedback.ifBlank { null },
                QuestionDraft(QuestionType.OPEN_ENDED, q.question, answer = q.idealAnswer, rubric = q.keyPoints))
        }
        repo.markDone(bookId, lessonIndex, StudyModule.WRITTEN_EXAM)
        _phase.value = Phase.Report(p.questions, given, grades, aiGraded)
    }

    override fun onCleared() { sessionId?.let(sessions::finishDetached) }
}

/**
 * Oral mock exam: the examiner asks (spoken by TTS), the learner answers by voice (or typing), the model reacts in
 * at most two sentences and may ask one short follow-up before moving on. Ends with a spoken-exam score.
 */
@HiltViewModel
class OralExamViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: StudyHubRepository,
    private val sessions: SessionTracker,
) : ViewModel() {
    val bookId: String = handle["bookId"]!!
    val lessonIndex: Int = handle.get<String>("lesson")?.toIntOrNull() ?: 0
    val count: Int = handle.get<String>("count")?.toIntOrNull() ?: 3
    val difficulty: Int = handle.get<String>("difficulty")?.toIntOrNull() ?: 3

    /** One line of the exam conversation. */
    data class Message(val fromExaminer: Boolean, val text: String, val score: Int? = null)

    data class Result(val question: ExamQuestion, val answer: String, val score: Double, val feedback: String)

    data class State(
        val preparing: Boolean = true,
        val error: String? = null,
        val questions: List<ExamQuestion> = emptyList(),
        val index: Int = 0,
        val followingUp: Boolean = false,
        val thinking: Boolean = false,
        val messages: List<Message> = emptyList(),
        val results: List<Result> = emptyList(),
        val finished: Boolean = false,
        val info: GenerationInfo? = null,
    ) {
        val current get() = questions.getOrNull(index)
        val score: Int get() = if (results.isEmpty()) 0 else results.sumOf { it.score }.div(results.size).toInt()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    /** The examiner's latest line, for the screen to speak aloud. */
    val toSpeak = MutableStateFlow<Pair<Int, String>?>(null)
    private var spoken = 0
    private var lesson: Lesson? = null
    private var firstAnswer = ""
    private var sessionId: String? = null
    private var job: Job? = null

    init { prepare() }

    private fun say(text: String) { toSpeak.value = ++spoken to text }

    fun prepare() = viewModelScope.launch {
        _state.value = State()
        try {
            val l = repo.lesson(bookId, lessonIndex).also { lesson = it }
            if (sessionId == null) sessionId = sessions.start(SessionType.QUIZ, bookId)
            when (val r = repo.ai.writtenExam(l, count, difficulty, repo.lang)) {
                is AiResult.Ok -> {
                    val first = r.value.questions.first()
                    _state.value = State(preparing = false, questions = r.value.questions, messages = listOf(Message(true, first.question)), info = r.info)
                    say(first.question)
                }
                is AiResult.Failed -> _state.value = State(preparing = false, error = AppLanguage.text.aiError(r.error))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = State(preparing = false, error = e.message ?: e.toString())
        }
    }

    fun answer(text: String) {
        val s = _state.value
        val q = s.current ?: return
        val l = lesson ?: return
        if (text.isBlank() || s.thinking || s.finished) return
        _state.value = s.copy(thinking = true, messages = s.messages + Message(false, text.trim()))
        job = viewModelScope.launch {
            // A follow-up answer is judged together with the first one.
            val full = if (s.followingUp) "$firstAnswer ${text.trim()}" else text.trim()
            val turn = when (val r = repo.ai.oralTurn(q, full, alreadyFollowedUp = s.followingUp, repo.lang)) {
                is AiResult.Ok -> r.value
                is AiResult.Failed -> OralTurn(0.0, AppLanguage.text.aiError(r.error), null)
            }
            val now = _state.value
            if (turn.followUp != null && !s.followingUp) {
                firstAnswer = full
                val line = turn.feedback + " " + turn.followUp
                _state.value = now.copy(thinking = false, followingUp = true, messages = now.messages + Message(true, line))
                say(line)
                return@launch
            }
            conclude(q, l, full, turn)
        }
    }

    private suspend fun conclude(q: ExamQuestion, l: Lesson, answer: String, turn: OralTurn) {
        val now = _state.value
        val results = now.results + Result(q, answer, turn.score, turn.feedback)
        if (turn.score < 60) repo.addToReview(bookId, l, q.question, q.idealAnswer, turn.feedback,
            QuestionDraft(QuestionType.OPEN_ENDED, q.question, answer = q.idealAnswer, rubric = q.keyPoints))
        val next = now.questions.getOrNull(now.index + 1)
        val scored = Message(true, turn.feedback, turn.score.toInt())
        if (next == null) {
            repo.markDone(bookId, lessonIndex, StudyModule.ORAL_EXAM)
            _state.value = now.copy(thinking = false, followingUp = false, results = results, messages = now.messages + scored, finished = true)
            say(turn.feedback)
        } else {
            _state.value = now.copy(thinking = false, followingUp = false, results = results, index = now.index + 1,
                messages = now.messages + scored + Message(true, next.question))
            say(turn.feedback + " " + next.question)
        }
    }

    /** Skips the current question: it counts as 0 and goes to the review queue. */
    fun skip(feedback: String) {
        val s = _state.value
        val q = s.current ?: return
        val l = lesson ?: return
        if (s.thinking || s.finished) return
        _state.value = s.copy(thinking = true)
        job = viewModelScope.launch { conclude(q, l, if (s.followingUp) firstAnswer else "", OralTurn(0.0, feedback, null)) }
    }

    override fun onCleared() { job?.cancel(); sessionId?.let(sessions::finishDetached) }
}
