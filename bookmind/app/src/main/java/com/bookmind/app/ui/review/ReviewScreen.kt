package com.bookmind.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.DueItem
import com.bookmind.app.data.db.SessionType
import com.bookmind.app.data.repo.ReviewRepository
import com.bookmind.app.data.repo.SessionTracker
import com.bookmind.app.data.repo.question
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.SourceChip
import com.bookmind.app.util.parseRefs
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.QuestionType
import com.bookmind.core.ai.RecallLevel
import com.bookmind.core.quiz.QuizScorer
import com.bookmind.core.srs.Rating
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val reviews: ReviewRepository,
    private val sessions: SessionTracker,
    private val ai: LearningAi,
    private val db: BookMindDatabase,
) : ViewModel() {
    private val bookId: String? = handle["bookId"]

    data class Measured(val correct: Boolean, val score: Double, val feedback: String, val approximate: Boolean)
    data class State(
        val loading: Boolean = true,
        val queue: List<DueItem> = emptyList(),
        val index: Int = 0,
        val revealed: Boolean = false,
        val hintUsed: Boolean = false,
        val measured: Measured? = null,
        val preview: Map<Rating, Long> = emptyMap(),
        val done: Int = 0,
        val xp: Int = 0,
    ) { val current get() = queue.getOrNull(index) }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var sessionId: String? = null
    private var shownAt = 0L
    private var response: String? = null

    init {
        viewModelScope.launch {
            val queue = reviews.todayQueue(bookId)
            _state.value = State(loading = false, queue = queue)
            if (queue.isNotEmpty()) sessionId = sessions.start(SessionType.REVIEW, bookId)
            shownAt = System.currentTimeMillis()
        }
    }

    fun hint() { _state.value = _state.value.copy(hintUsed = true) }

    /** Reveals the answer; if the user typed something, it is checked first so the rating has evidence. */
    fun reveal(answer: String?) = viewModelScope.launch {
        val item = _state.value.current ?: return@launch
        response = answer?.takeIf { it.isNotBlank() }
        val q = item.question()
        val measured = when {
            response == null -> null
            q != null && QuizScorer.isObjective(q) -> QuizScorer.score(q, response!!).let { Measured(it.correct, it.score, it.feedback, false) }
            else -> {
                val book = db.books().get(item.bookId)
                when (val r = ai.gradeOpenAnswer(item.prompt, item.expectedAnswer, q?.rubric.orEmpty(), response!!, book?.language, "", q?.acceptableAnswers.orEmpty())) {
                    is com.bookmind.core.ai.AiResult.Ok -> Measured(r.value.correct, r.value.score, r.value.feedback, r.value.confidence < com.bookmind.core.ai.GradeResult.LOW_CONFIDENCE)
                    is com.bookmind.core.ai.AiResult.Failed -> null
                }
            }
        }
        _state.value = _state.value.copy(revealed = true, measured = measured, preview = reviews.preview(item))
    }

    fun rate(rating: Rating) = viewModelScope.launch {
        val s = _state.value
        val item = s.current ?: return@launch
        val q = item.question()
        val level = when {
            q != null -> q.type.recallLevel
            response != null -> RecallLevel.RECALL
            else -> RecallLevel.RECOGNITION
        }
        // An approximate (low-confidence) grade is treated as a score, not as hard evidence.
        val outcome = reviews.submit(
            item, rating,
            correct = s.measured?.takeIf { !it.approximate }?.correct,
            score = s.measured?.takeIf { it.approximate }?.score,
            response = response, responseTimeMs = System.currentTimeMillis() - shownAt, hintUsed = s.hintUsed, level = level,
        )
        // Items rated "again" come back at the end of today's session.
        val requeue = if (outcome.rating == Rating.AGAIN && s.queue.count { it.itemId == item.itemId } < 3) {
            reviews.todayQueue(bookId).firstOrNull { it.itemId == item.itemId }?.let { listOf(it) }.orEmpty()
        } else emptyList()
        response = null
        shownAt = System.currentTimeMillis()
        _state.value = s.copy(queue = s.queue + requeue, index = s.index + 1, revealed = false, hintUsed = false, measured = null, done = s.done + 1, xp = s.xp + outcome.xp)
        if (_state.value.current == null) finish()
    }

    fun finish() { sessionId?.let(sessions::finishDetached); sessionId = null }
    override fun onCleared() = finish()
}

@Composable
fun ReviewScreen(bookId: String?, onBack: (() -> Unit)?, vm: ReviewViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.finish() } }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(R.string.review_title), onBack)
        if (state.loading) { LoadingState(); return@Column }
        val item = state.current
        if (item == null) {
            EmptyState(
                stringResource(if (state.done > 0) R.string.review_done_title else R.string.review_empty_title),
                if (state.done > 0) stringResource(R.string.review_done_body, state.done, state.xp) else stringResource(R.string.review_empty_body),
            )
            return@Column
        }
        LinearProgressIndicator({ state.index.toFloat() / state.queue.size }, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.review_progress, state.index + 1, state.queue.size) + " · " + item.bookTitle, style = MaterialTheme.typography.labelMedium)
            if (item.reviewCount == 0) Text(stringResource(R.string.review_new_item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) { Text(item.prompt, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(12.dp))
            if (!state.revealed) Front(item, state.hintUsed, vm::hint, vm::reveal) else Back(item, state, vm::rate)
        }
    }
}

@Composable
private fun Front(item: DueItem, hintUsed: Boolean, onHint: () -> Unit, onReveal: (String?) -> Unit) {
    val q = remember(item.itemId) { item.question() }
    var answer by remember(item.itemId) { mutableStateOf("") }
    if (q != null && q.options.isNotEmpty() && q.type != QuestionType.ORDERING) {
        q.options.forEach { opt ->
            Row(Modifier.fillMaxWidth().selectable(answer == opt, role = Role.RadioButton) { answer = opt }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(answer == opt, null); Text(opt, Modifier.padding(start = 8.dp))
            }
        }
    } else if (q?.type == QuestionType.TRUE_FALSE) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ onReveal("true") }) { Text(stringResource(R.string.true_label)) }
            OutlinedButton({ onReveal("false") }) { Text(stringResource(R.string.false_label)) }
        }
    } else {
        // Recall first, without hints: typing the answer is optional but gives a measured result.
        OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp), placeholder = { Text(stringResource(R.string.review_type_answer)) })
    }
    if (hintUsed) Text(
        stringResource(R.string.hint_value, item.expectedAnswer.split(' ').take(3).joinToString(" ") { it.take(2) + "…" }),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
    )
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hintUsed) TextButton(onHint) { Text(stringResource(R.string.hint)) }
        Spacer(Modifier.weight(1f))
        Button({ onReveal(answer) }) { Text(stringResource(if (answer.isBlank()) R.string.show_answer else R.string.check_answer)) }
    }
}

@Composable
private fun Back(item: DueItem, state: ReviewViewModel.State, onRate: (Rating) -> Unit) {
    state.measured?.let { m ->
        Text(
            stringResource(if (m.correct) R.string.correct else R.string.incorrect) + if (m.approximate) " · " + stringResource(R.string.grade_uncertain_short) else "",
            color = if (m.correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall,
        )
        Text(m.feedback, style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.expected_answer), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Text(item.expectedAnswer, style = MaterialTheme.typography.bodyLarge)
    item.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    parseRefs(item.sourceReferences).forEach { SourceChip(it) }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.how_well), style = MaterialTheme.typography.titleSmall)
    val labels = listOf(Rating.AGAIN to R.string.rate_again, Rating.HARD to R.string.rate_hard, Rating.GOOD to R.string.rate_good, Rating.EASY to R.string.rate_easy)
    labels.forEach { (r, label) ->
        FilledTonalButton({ onRate(r) }, Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(label))
                state.preview[r]?.let { Text(intervalText(it), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
fun intervalText(ms: Long): String = when {
    ms < 3_600_000 -> pluralStringResource(R.plurals.minutes, (ms / 60_000).toInt().coerceAtLeast(1), (ms / 60_000).toInt().coerceAtLeast(1))
    ms < 86_400_000 -> pluralStringResource(R.plurals.hours, (ms / 3_600_000).toInt(), (ms / 3_600_000).toInt())
    else -> pluralStringResource(R.plurals.days, (ms / 86_400_000).toInt(), (ms / 86_400_000).toInt())
}
