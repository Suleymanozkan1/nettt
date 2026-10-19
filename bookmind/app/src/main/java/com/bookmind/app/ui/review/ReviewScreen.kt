package com.bookmind.app.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.GhostButton
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.PageHeader
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.theme.Lora
import com.bookmind.app.ui.theme.extended
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
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
            q != null && QuizScorer.isObjective(q) -> QuizScorer.score(q, response!!, AppLanguage.tag).let { Measured(it.correct, it.score, it.feedback, false) }
            else -> {
                when (val r = ai.gradeOpenAnswer(item.prompt, item.expectedAnswer, q?.rubric.orEmpty(), response!!, AppLanguage.tag, "", q?.acceptableAnswers.orEmpty())) {
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
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().imePadding()) {
        if (onBack != null) BackTopBar(stringResource(R.string.review_title), onBack) else PageHeader(stringResource(R.string.review_title))
        if (state.loading) { LoadingState(); return@Column }
        val item = state.current
        if (item == null) {
            EmptyState(
                stringResource(if (state.done > 0) R.string.review_done_title else R.string.review_empty_title),
                if (state.done > 0) stringResource(R.string.review_done_body, state.done, state.xp) else stringResource(R.string.review_empty_body),
                icon = if (state.done > 0) Icons.Filled.Celebration else Icons.Filled.Style,
            )
            return@Column
        }
        ContentWidth(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.review_progress, state.index + 1, state.queue.size), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    Spacer(Modifier.width(10.dp))
                    ProgressBar(state.index.toFloat() / state.queue.size, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                // The card: book, prompt, and (once revealed) the answer on the same surface.
                AppCard(Modifier.fillMaxWidth().heightIn(min = 220.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.bookTitle, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                        if (item.reviewCount == 0) Pill(stringResource(R.string.review_new_item), color = MaterialTheme.extended.onWarningContainer, container = MaterialTheme.extended.warningContainer)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(item.prompt, style = MaterialTheme.typography.headlineSmall, fontFamily = Lora)
                    if (state.revealed) Back(item, state)
                }
                Spacer(Modifier.height(16.dp))
                if (!state.revealed) Front(item, state.hintUsed, vm::hint, vm::reveal) else Rate(state, vm::rate)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Front(item: DueItem, hintUsed: Boolean, onHint: () -> Unit, onReveal: (String?) -> Unit) {
    val q = remember(item.itemId) { item.question() }
    var answer by remember(item.itemId) { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    if (q != null && q.options.isNotEmpty() && q.type != QuestionType.ORDERING) {
        q.options.forEachIndexed { i, opt -> OptionRow(('A' + i).toString(), opt, answer == opt) { answer = opt } }
    } else if (q?.type == QuestionType.TRUE_FALSE) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(R.string.true_label), { onReveal("true") }, Modifier.weight(1f), icon = Icons.Filled.CheckCircle, color = MaterialTheme.extended.success)
            GhostButton(stringResource(R.string.false_label), { onReveal("false") }, Modifier.weight(1f), icon = Icons.Filled.Cancel, color = cs.error)
        }
    } else {
        // Recall first, without hints: typing the answer is optional but gives a measured result.
        OutlinedTextField(
            answer, { answer = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp), placeholder = { Text(stringResource(R.string.review_type_answer)) },
            shape = MaterialTheme.shapes.large,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.extended.card, focusedContainerColor = MaterialTheme.extended.card,
                unfocusedBorderColor = MaterialTheme.extended.cardBorder),
        )
    }
    if (hintUsed) Text(
        stringResource(R.string.hint_value, item.expectedAnswer.split(' ').take(3).joinToString(" ") { it.take(2) + "…" }),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extended.onWarningContainer,
        modifier = Modifier.padding(top = 10.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.extended.warningContainer).padding(12.dp).fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!hintUsed) GhostButton(stringResource(R.string.hint), onHint, icon = Icons.Filled.Lightbulb)
        PrimaryButton(stringResource(if (answer.isBlank()) R.string.show_answer else R.string.check_answer), { onReveal(answer) }, Modifier.weight(1f), icon = Icons.Filled.Visibility)
    }
}

@Composable
fun OptionRow(letter: String, text: String, selected: Boolean, enabled: Boolean = true, state: Boolean? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    val accent = when (state) { true -> x.success; false -> cs.error; null -> cs.primary }
    val active = selected || state != null
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(MaterialTheme.shapes.large)
            .background(when (state) { true -> x.successContainer; false -> cs.errorContainer; null -> if (selected) cs.primaryContainer else x.card })
            .border(if (active) 2.dp else 1.dp, if (active) accent else x.cardBorder, MaterialTheme.shapes.large)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(if (active) accent else cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text(letter, style = MaterialTheme.typography.labelLarge, color = if (active) Color.White else cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Back(item: DueItem, state: ReviewViewModel.State) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(x.cardBorder))
    Spacer(Modifier.height(16.dp))
    state.measured?.let { m ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Icon(if (m.correct) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null, tint = if (m.correct) x.success else cs.error, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stringResource(if (m.correct) R.string.correct else R.string.incorrect) + if (m.approximate) " · " + stringResource(R.string.grade_uncertain_short) else "",
                    style = MaterialTheme.typography.titleSmall, color = if (m.correct) x.success else cs.error)
                Text(m.feedback, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
    Text(stringResource(R.string.expected_answer).uppercase(AppLanguage.locale), style = MaterialTheme.typography.labelSmall, color = cs.primary)
    Text(item.expectedAnswer, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    item.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { parseRefs(item.sourceReferences).forEach { SourceChip(it) } }
}

@Composable
private fun Rate(state: ReviewViewModel.State, onRate: (Rating) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Text(stringResource(R.string.how_well), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
    val options = listOf(
        Triple(Rating.AGAIN, R.string.rate_again, cs.error to cs.errorContainer),
        Triple(Rating.HARD, R.string.rate_hard, x.warning to x.warningContainer),
        Triple(Rating.GOOD, R.string.rate_good, x.success to x.successContainer),
        Triple(Rating.EASY, R.string.rate_easy, x.info to x.infoContainer),
    )
    options.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (r, label, colors) ->
                Surface(onClick = { onRate(r) }, modifier = Modifier.weight(1f).heightIn(min = 64.dp), shape = MaterialTheme.shapes.large, color = colors.second) {
                    Column(Modifier.padding(vertical = 10.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = colors.first, textAlign = TextAlign.Center)
                        state.preview[r]?.let { Text(intervalText(it), style = MaterialTheme.typography.labelSmall, color = colors.first.copy(alpha = 0.8f)) }
                    }
                }
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
