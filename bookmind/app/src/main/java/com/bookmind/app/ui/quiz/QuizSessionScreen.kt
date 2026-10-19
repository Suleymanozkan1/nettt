package com.bookmind.app.ui.quiz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.QuizQuestionEntity
import com.bookmind.app.data.repo.QuizRepository
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.components.SourceChip
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.review.OptionRow
import com.bookmind.app.ui.theme.Lora
import com.bookmind.app.ui.theme.extended
import com.bookmind.app.util.parseRefs
import com.bookmind.core.ai.GradeResult
import com.bookmind.core.ai.QuestionDraft
import com.bookmind.core.ai.QuestionType
import com.bookmind.core.quiz.QuizScorer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class QuizSessionViewModel @Inject constructor(handle: SavedStateHandle, private val quizzes: QuizRepository) : ViewModel() {
    private val quizId: String = handle["quizId"]!!

    data class Item(val entity: QuizQuestionEntity, val draft: QuestionDraft)
    data class State(
        val items: List<Item> = emptyList(),
        val index: Int = 0,
        val graded: QuizRepository.Graded? = null,
        val grading: Boolean = false,
        val timeLimitSeconds: Int? = null,
        val loading: Boolean = true,
        /** Set when the attempt is finished; the screen then navigates to the results. */
        val finishedAttemptId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var attempt: QuizRepository.Attempt? = null
    private var shownAt = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            val quiz = quizzes.quiz(quizId) ?: return@launch
            val items = quizzes.questions(quizId).mapNotNull { q -> quizzes.decode(q.payload)?.let { Item(q, it) } }
            attempt = quizzes.startAttempt(quizId)
            _state.value = State(items, timeLimitSeconds = quizzes.config(quiz).timeLimitSeconds, loading = false)
            shownAt = System.currentTimeMillis()
        }
    }

    fun submit(answer: String) = viewModelScope.launch {
        val s = _state.value
        val a = attempt ?: return@launch
        val item = s.items.getOrNull(s.index) ?: return@launch
        _state.value = s.copy(grading = true)
        val graded = quizzes.answer(a.attemptId, item.entity, answer, System.currentTimeMillis() - shownAt)
        _state.value = _state.value.copy(graded = graded, grading = false)
    }

    fun next() {
        val s = _state.value
        if (s.index + 1 >= s.items.size) { finish(); return }
        _state.value = s.copy(index = s.index + 1, graded = null)
        shownAt = System.currentTimeMillis()
    }

    fun finish() = viewModelScope.launch {
        val a = attempt ?: return@launch
        if (_state.value.finishedAttemptId != null) return@launch
        quizzes.finish(a)
        _state.value = _state.value.copy(finishedAttemptId = a.attemptId)
    }
}

@Composable
fun QuizSessionScreen(quizId: String, onBack: () -> Unit, onFinished: (String) -> Unit, vm: QuizSessionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmExit by remember { mutableStateOf(false) }
    var remaining by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(state.finishedAttemptId) { state.finishedAttemptId?.let(onFinished) }

    LaunchedEffect(state.timeLimitSeconds, state.loading) {
        val limit = state.timeLimitSeconds ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        remaining = limit.toLong()
        while (remaining > 0) { delay(1_000); remaining-- }
        vm.finish()
    }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(R.string.quiz), { confirmExit = true }) {
            if (remaining >= 0) Pill("%d:%02d".format(remaining / 60, remaining % 60), Modifier.padding(end = 12.dp), icon = Icons.Filled.Timer)
        }
        if (state.loading) { LoadingState(); return@Column }
        val item = state.items.getOrNull(state.index) ?: return@Column
        ContentWidth(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.question_n_of, state.index + 1, state.items.size), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    Spacer(Modifier.width(10.dp))
                    ProgressBar((state.index + 1f) / state.items.size, Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                Pill(questionTypeLabel(item.draft.type), color = cs.onSurfaceVariant, container = cs.surfaceContainerHigh)
                if (item.draft.needsReview) WarningBanner(listOf(stringResource(R.string.question_needs_review)))
                Spacer(Modifier.height(12.dp))
                Text(item.draft.prompt, style = MaterialTheme.typography.headlineSmall, fontFamily = Lora)
                Spacer(Modifier.height(18.dp))
                val graded = state.graded
                AnswerInput(item.draft, graded, enabled = graded == null && !state.grading, onSubmit = vm::submit)
                if (graded != null) {
                    Feedback(graded, item)
                    PrimaryButton(stringResource(if (state.index + 1 >= state.items.size) R.string.see_results else R.string.next), vm::next, Modifier.fillMaxWidth().padding(top = 14.dp))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text(stringResource(R.string.quiz_exit_title)) },
        text = { Text(stringResource(R.string.quiz_exit_body)) },
        confirmButton = { TextButton({ confirmExit = false; onBack() }) { Text(stringResource(R.string.exit)) } },
        dismissButton = { TextButton({ confirmExit = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Feedback(g: QuizRepository.Graded, item: QuizSessionViewModel.Item) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    val fg = if (g.correct) x.onSuccessContainer else cs.onErrorContainer
    AppCard(Modifier.fillMaxWidth().padding(top = 16.dp), color = if (g.correct) x.successContainer else cs.errorContainer, border = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (g.correct) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null, tint = if (g.correct) x.success else cs.error)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (g.correct) R.string.correct else R.string.incorrect), style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.weight(1f))
            Text("%${(g.score * 100).toInt()}", style = MaterialTheme.typography.labelLarge, color = fg)
        }
        Text(g.feedback, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.padding(top = 8.dp))
        if (g.confidence < GradeResult.LOW_CONFIDENCE) Text(stringResource(R.string.grade_uncertain), style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.8f))
        if (!g.correct || item.draft.type.isObjective.not()) {
            Text(stringResource(R.string.expected_answer).uppercase(AppLanguage.locale), style = MaterialTheme.typography.labelSmall, color = fg, modifier = Modifier.padding(top = 12.dp))
            Text(g.correctAnswer, style = MaterialTheme.typography.bodyMedium, color = fg)
        }
        if (g.missingPoints.isNotEmpty()) Text(stringResource(R.string.missing_points, g.missingPoints.joinToString()), style = MaterialTheme.typography.bodySmall, color = fg,
            modifier = Modifier.padding(top = 6.dp))
        if (item.draft.explanation.isNotBlank()) Text(item.draft.explanation, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { parseRefs(item.entity.sourceReferences).forEach { SourceChip(it) } }
    }
}

@Composable
private fun AnswerInput(q: QuestionDraft, graded: QuizRepository.Graded?, enabled: Boolean, onSubmit: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    when {
        q.type == QuestionType.TRUE_FALSE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple("true", R.string.true_label, Icons.Filled.CheckCircle), Triple("false", R.string.false_label, Icons.Filled.Cancel)).forEach { (v, label, icon) ->
                val color = if (v == "true") x.success else cs.error
                AppCard(Modifier.weight(1f).height(84.dp), onClick = if (enabled) ({ onSubmit(v) }) else null) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, null, tint = color)
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        q.type == QuestionType.ORDERING -> Ordering(q.options, enabled, onSubmit)
        q.type == QuestionType.MATCHING -> Matching(q, enabled, onSubmit)
        q.options.isNotEmpty() -> {
            var selected by remember(q) { mutableStateOf<String?>(null) }
            q.options.forEachIndexed { i, opt ->
                val result = when {
                    graded == null -> null
                    QuizScorer.normalize(opt) == QuizScorer.normalize(q.answer) -> true
                    opt == selected -> false
                    else -> null
                }
                OptionRow(('A' + i).toString(), opt, selected == opt, enabled = enabled, state = result) { selected = opt }
            }
            if (graded == null) PrimaryButton(stringResource(R.string.check_answer), { selected?.let(onSubmit) }, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = enabled && selected != null)
        }
        else -> {
            var text by remember(q) { mutableStateOf("") }
            val open = !q.type.isObjective
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = if (open) 140.dp else 56.dp), enabled = enabled,
                placeholder = { Text(stringResource(if (open) R.string.answer_in_own_words else R.string.your_answer)) }, singleLine = !open,
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = x.card, focusedContainerColor = x.card, disabledContainerColor = x.card, unfocusedBorderColor = x.cardBorder))
            if (graded == null) PrimaryButton(stringResource(R.string.check_answer), { onSubmit(text) }, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = enabled && text.isNotBlank())
        }
    }
}

@Composable
private fun Ordering(items: List<String>, enabled: Boolean, onSubmit: (String) -> Unit) {
    var order by remember(items) { mutableStateOf(items) }
    val cs = MaterialTheme.colorScheme
    order.forEachIndexed { i, item ->
        AppCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentPadding = PaddingValues(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
                Text(item, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Column {
                    IconButton({ order = order.toMutableList().apply { add(i - 1, removeAt(i)) } }, enabled = enabled && i > 0, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.ArrowUpward, stringResource(R.string.move_up), Modifier.size(18.dp))
                    }
                    IconButton({ order = order.toMutableList().apply { add(i + 1, removeAt(i)) } }, enabled = enabled && i < order.lastIndex, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.ArrowDownward, stringResource(R.string.move_down), Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    PrimaryButton(stringResource(R.string.check_answer), { onSubmit(order.joinToString(" | ")) }, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = enabled)
}

@Composable
private fun Matching(q: QuestionDraft, enabled: Boolean, onSubmit: (String) -> Unit) {
    val rights = remember(q) { q.pairs.map { it.right }.shuffled(java.util.Random(q.prompt.hashCode().toLong())) }
    var chosen by remember(q) { mutableStateOf(mapOf<String, String>()) }
    val cs = MaterialTheme.colorScheme
    q.pairs.forEach { pair ->
        var open by remember { mutableStateOf(false) }
        AppCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(pair.left, style = MaterialTheme.typography.titleSmall)
            Box(Modifier.padding(top = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.surfaceContainerHigh).clickable(enabled = enabled) { open = true }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(chosen[pair.left] ?: stringResource(R.string.choose), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = if (chosen[pair.left] == null) cs.onSurfaceVariant else cs.onSurface)
                    Icon(Icons.Filled.ExpandMore, null)
                }
                DropdownMenu(open, { open = false }) {
                    rights.forEach { r -> DropdownMenuItem({ Text(r, style = MaterialTheme.typography.bodySmall) }, { chosen = chosen + (pair.left to r); open = false }) }
                }
            }
        }
    }
    PrimaryButton(stringResource(R.string.check_answer), { onSubmit(chosen.entries.joinToString(" | ") { "${it.key}=${it.value}" }) }, Modifier.fillMaxWidth().padding(top = 10.dp),
        enabled = enabled && chosen.size == q.pairs.size)
}
