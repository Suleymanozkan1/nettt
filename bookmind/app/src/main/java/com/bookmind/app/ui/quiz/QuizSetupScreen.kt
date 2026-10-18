package com.bookmind.app.ui.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.repo.NotEnoughMaterialException
import com.bookmind.app.data.repo.QuizConfig
import com.bookmind.app.data.repo.QuizMode
import com.bookmind.app.data.repo.QuizRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ErrorState
import com.bookmind.core.ai.QuestionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QuizSetupViewModel @Inject constructor(handle: SavedStateHandle, library: LibraryRepository, private val quizzes: QuizRepository, private val settings: SettingsRepository) : ViewModel() {
    val bookId: String = handle["bookId"]!!
    val chapters = library.chapters(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<Int?>(null)
    val error = _error.asStateFlow()

    fun create(config: QuizConfig, onCreated: (String) -> Unit) = viewModelScope.launch {
        _busy.value = true; _error.value = null
        try {
            val spoiler = settings.current().spoilerFree
            onCreated(quizzes.create(bookId, config.copy(spoilerFree = config.spoilerFree && spoiler)).quizId)
        } catch (e: NotEnoughMaterialException) {
            _error.value = if (config.mode == QuizMode.WRONG_ONLY) R.string.quiz_no_wrong else R.string.quiz_not_enough
        } catch (e: Exception) {
            _error.value = R.string.quiz_failed
        }
        _busy.value = false
    }
}

@Composable
fun questionTypeLabel(t: QuestionType) = stringResource(
    when (t) {
        QuestionType.MULTIPLE_CHOICE -> R.string.qt_mcq
        QuestionType.TRUE_FALSE -> R.string.qt_tf
        QuestionType.FILL_BLANK -> R.string.qt_fill
        QuestionType.SHORT_ANSWER -> R.string.qt_short
        QuestionType.OPEN_ENDED -> R.string.qt_open
        QuestionType.DEFINE_CONCEPT -> R.string.qt_define
        QuestionType.MATCHING -> R.string.qt_matching
        QuestionType.ORDERING -> R.string.qt_ordering
        QuestionType.SCENARIO -> R.string.qt_scenario
        QuestionType.CALCULATION -> R.string.qt_calculation
        QuestionType.FIND_ERROR -> R.string.qt_find_error
        QuestionType.EXPLAIN_OWN_WORDS -> R.string.qt_explain
        QuestionType.REAL_LIFE -> R.string.qt_real_life
    },
)

@Composable
fun QuizSetupScreen(bookId: String, onBack: () -> Unit, onCreated: (String) -> Unit, vm: QuizSetupViewModel = hiltViewModel()) {
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var count by remember { mutableFloatStateOf(8f) }
    var difficulty by remember { mutableFloatStateOf(3f) }
    var mode by remember { mutableStateOf(QuizMode.STANDARD) }
    var timed by remember { mutableStateOf(false) }
    var spoilerFree by remember { mutableStateOf(true) }
    var selectedChapters by remember { mutableStateOf(setOf<String>()) }
    var types by remember { mutableStateOf(QuizConfig().types.toSet()) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.quiz_setup_title), onBack)
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.quiz_mode), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(QuizMode.STANDARD to R.string.mode_standard, QuizMode.WRONG_ONLY to R.string.mode_wrong, QuizMode.NEAR_FORGETTING to R.string.mode_near,
                    QuizMode.MIXED_REVIEW to R.string.mode_mixed, QuizMode.FULL_BOOK to R.string.mode_full)
                    .forEach { (m, label) -> FilterChip(mode == m, { mode = m }, { Text(stringResource(label)) }) }
            }
            Text(stringResource(R.string.question_count, count.toInt()), Modifier.padding(top = 12.dp))
            Slider(count, { count = it }, valueRange = 3f..25f, steps = 21)
            Text(stringResource(R.string.difficulty_value, difficulty.toInt()))
            Slider(difficulty, { difficulty = it }, valueRange = 1f..5f, steps = 3)
            Row(Modifier.fillMaxWidth().toggleable(timed, role = Role.Switch) { timed = it }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.time_limit), Modifier.weight(1f)); Switch(timed, null)
            }
            Row(Modifier.fillMaxWidth().toggleable(spoilerFree, role = Role.Switch) { spoilerFree = it }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.spoiler_free), Modifier.weight(1f)); Switch(spoilerFree, null)
            }
            Text(stringResource(R.string.question_types), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuestionType.entries.forEach { t ->
                    FilterChip(t in types, { types = if (t in types && types.size > 1) types - t else types + t }, { Text(questionTypeLabel(t)) })
                }
            }
            if (mode == QuizMode.STANDARD && chapters.isNotEmpty()) {
                Text(stringResource(R.string.chapters_filter), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chapters.forEach { ch ->
                        FilterChip(ch.id in selectedChapters, { selectedChapters = if (ch.id in selectedChapters) selectedChapters - ch.id else selectedChapters + ch.id },
                            { Text("${ch.orderIndex + 1}. ${ch.title}", maxLines = 1) })
                    }
                }
            }
            error?.let { ErrorState(stringResource(it)) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    vm.create(QuizConfig(count.toInt(), difficulty.toInt(), selectedChapters.toList(), if (timed) count.toInt() * 60 else null, mode, types.toList(), spoilerFree), onCreated)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(stringResource(R.string.create_quiz)) }
        }
    }
}
