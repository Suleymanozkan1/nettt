package com.bookmind.app.ui.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.QuizQuestionEntity
import com.bookmind.app.data.repo.QuizRepository
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.SourceChip
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.util.parseRefs
import com.bookmind.core.ai.GradeResult
import com.bookmind.core.ai.QuestionDraft
import com.bookmind.core.ai.QuestionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    /** @return the attempt id when the quiz is over. */
    suspend fun next(): String? {
        val s = _state.value
        if (s.index + 1 >= s.items.size) return finish()
        _state.value = s.copy(index = s.index + 1, graded = null)
        shownAt = System.currentTimeMillis()
        return null
    }

    suspend fun finish(): String? {
        val a = attempt ?: return null
        quizzes.finish(a)
        return a.attemptId
    }
}

@Composable
fun QuizSessionScreen(quizId: String, onBack: () -> Unit, onFinished: (String) -> Unit, vm: QuizSessionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmExit by remember { mutableStateOf(false) }
    var remaining by remember { mutableLongStateOf(-1L) }
    var finishing by remember { mutableStateOf(false) }

    LaunchedEffect(state.timeLimitSeconds, state.loading) {
        val limit = state.timeLimitSeconds ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        remaining = limit.toLong()
        while (remaining > 0) { delay(1_000); remaining-- }
        if (!finishing) { finishing = true; vm.finish()?.let(onFinished) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(R.string.quiz), { confirmExit = true }) {
            if (remaining >= 0) Text("%d:%02d".format(remaining / 60, remaining % 60), Modifier.padding(end = 16.dp))
        }
        if (state.loading) { LoadingState(); return@Column }
        val item = state.items.getOrNull(state.index) ?: return@Column
        LinearProgressIndicator({ (state.index + 1f) / state.items.size }, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.question_n_of, state.index + 1, state.items.size) + " · " + questionTypeLabel(item.draft.type), style = MaterialTheme.typography.labelMedium)
            if (item.draft.needsReview) WarningBanner(listOf(stringResource(R.string.question_needs_review)))
            Spacer(Modifier.height(8.dp))
            Text(item.draft.prompt, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val graded = state.graded
            AnswerInput(item.draft, enabled = graded == null && !state.grading, onSubmit = vm::submit)
            if (graded != null) {
                Feedback(graded, item)
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                Button({ scope.launch { vm.next()?.let(onFinished) } }, Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(if (state.index + 1 >= state.items.size) R.string.see_results else R.string.next))
                }
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
    Card(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = if (g.correct) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(if (g.correct) R.string.correct else R.string.incorrect) + " (%${(g.score * 100).toInt()})", style = MaterialTheme.typography.titleSmall)
            Text(g.feedback, style = MaterialTheme.typography.bodyMedium)
            if (g.confidence < GradeResult.LOW_CONFIDENCE) Text(stringResource(R.string.grade_uncertain), style = MaterialTheme.typography.bodySmall)
            if (!g.correct || item.draft.type.isObjective.not()) {
                Text(stringResource(R.string.expected_answer), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Text(g.correctAnswer, style = MaterialTheme.typography.bodyMedium)
            }
            if (g.missingPoints.isNotEmpty()) Text(stringResource(R.string.missing_points, g.missingPoints.joinToString()), style = MaterialTheme.typography.bodySmall)
            if (item.draft.explanation.isNotBlank()) Text(item.draft.explanation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            parseRefs(item.entity.sourceReferences).forEach { SourceChip(it) }
        }
    }
}

@Composable
private fun AnswerInput(q: QuestionDraft, enabled: Boolean, onSubmit: (String) -> Unit) {
    when {
        q.type == QuestionType.TRUE_FALSE -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ onSubmit("true") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.true_label)) }
            Button({ onSubmit("false") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.false_label)) }
        }
        q.type == QuestionType.ORDERING -> Ordering(q.options, enabled, onSubmit)
        q.type == QuestionType.MATCHING -> Matching(q, enabled, onSubmit)
        q.options.isNotEmpty() -> {
            var selected by remember(q) { mutableStateOf<String?>(null) }
            q.options.forEach { opt ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected == opt, enabled = enabled, role = Role.RadioButton) { selected = opt }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { RadioButton(selected == opt, null); Text(opt, Modifier.padding(start = 8.dp)) }
            }
            Button({ selected?.let(onSubmit) }, enabled = enabled && selected != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.check_answer)) }
        }
        else -> {
            var text by remember(q) { mutableStateOf("") }
            val open = !q.type.isObjective
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = if (open) 140.dp else 56.dp), enabled = enabled,
                placeholder = { Text(stringResource(if (open) R.string.answer_in_own_words else R.string.your_answer)) }, singleLine = !open)
            Button({ onSubmit(text) }, enabled = enabled && text.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.check_answer)) }
        }
    }
}

@Composable
private fun Ordering(items: List<String>, enabled: Boolean, onSubmit: (String) -> Unit) {
    var order by remember(items) { mutableStateOf(items) }
    order.forEachIndexed { i, item ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}. $item", Modifier.weight(1f))
            IconButton({ order = order.toMutableList().apply { add(i - 1, removeAt(i)) } }, enabled = enabled && i > 0) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.move_up)) }
            IconButton({ order = order.toMutableList().apply { add(i + 1, removeAt(i)) } }, enabled = enabled && i < order.lastIndex) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.move_down)) }
        }
    }
    Button({ onSubmit(order.joinToString(" | ")) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.check_answer)) }
}

@Composable
private fun Matching(q: QuestionDraft, enabled: Boolean, onSubmit: (String) -> Unit) {
    val rights = remember(q) { q.pairs.map { it.right }.shuffled(java.util.Random(q.prompt.hashCode().toLong())) }
    var chosen by remember(q) { mutableStateOf(mapOf<String, String>()) }
    q.pairs.forEach { pair ->
        var open by remember { mutableStateOf(false) }
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(pair.left, style = MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton({ open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(chosen[pair.left] ?: stringResource(R.string.choose), maxLines = 2) }
                DropdownMenu(open, { open = false }) {
                    rights.forEach { r -> DropdownMenuItem({ Text(r) }, { chosen = chosen + (pair.left to r); open = false }) }
                }
            }
        }
    }
    Button({ onSubmit(chosen.entries.joinToString(" | ") { "${it.key}=${it.value}" }) }, enabled = enabled && chosen.size == q.pairs.size, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.check_answer))
    }
}
