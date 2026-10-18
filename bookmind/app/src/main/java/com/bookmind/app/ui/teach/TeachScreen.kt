package com.bookmind.app.ui.teach

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.TeachMessageEntity
import com.bookmind.app.data.repo.StudyRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ErrorState
import com.bookmind.app.ui.components.SourceChip
import com.bookmind.app.util.AppJson
import com.bookmind.core.ai.TeachTurn
import com.bookmind.core.ai.TeachingLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Learner-facing explanation styles (section 7 of the product spec). */
val teachStyles = listOf(
    "simple" to R.string.style_simple, "technical" to R.string.style_technical, "example" to R.string.style_example,
    "analogy" to R.string.style_analogy, "step by step" to R.string.style_steps, "explain what I don't know" to R.string.style_gap,
    "ask me" to R.string.style_ask, "debate" to R.string.style_debate, "real life use" to R.string.style_real_life,
    "connect to previous topic" to R.string.style_connect,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class TeachViewModel @Inject constructor(handle: SavedStateHandle, private val study: StudyRepository, settings: SettingsRepository) : ViewModel() {
    val bookId: String = handle["bookId"]!!
    val conceptId: String = handle["conceptId"]!!
    val level = MutableStateFlow(TeachingLevel.BASIC)
    private val sessionId = MutableStateFlow<String?>(null)
    val messages = sessionId.flatMapLatest { it?.let(study::messages) ?: emptyFlow() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _busy = MutableStateFlow(true)
    val busy = _busy.asStateFlow()
    private val _finished = MutableStateFlow(false)
    val finished = _finished.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var style = "simple"

    init {
        viewModelScope.launch {
            level.value = runCatching { TeachingLevel.valueOf(settings.current().teachingLevel) }.getOrDefault(TeachingLevel.BASIC)
            guard { sessionId.value = study.startTeaching(bookId, conceptId, level.value, style) }
        }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        _busy.value = true; _error.value = null
        try { block() } catch (e: Exception) { _error.value = e.message ?: "error" }
        _busy.value = false
    }

    fun send(answer: String) = viewModelScope.launch {
        val sid = sessionId.value ?: return@launch
        guard {
            val turn = study.reply(bookId, conceptId, sid, answer, level.value, style)
            turn.suggestedLevel?.let { level.value = it }
            if (!turn.expectsAnswer) _finished.value = true
        }
    }

    fun restyle(newStyle: String) = viewModelScope.launch {
        style = newStyle
        val sid = sessionId.value ?: return@launch
        guard { study.restyle(bookId, conceptId, sid, level.value, style) }
    }
}

@Composable
fun TeachScreen(bookId: String, conceptId: String, onBack: () -> Unit, vm: TeachViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var levelMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(R.string.teacher_title), onBack) {
            Box {
                TextButton({ levelMenu = true }) { Text(levelLabel(level)) }
                DropdownMenu(levelMenu, { levelMenu = false }) {
                    TeachingLevel.entries.forEach { l -> DropdownMenuItem({ Text(levelLabel(l)) }, { vm.level.value = l; levelMenu = false }) }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            teachStyles.forEach { (key, label) -> AssistChip({ vm.restyle(key) }, { Text(stringResource(label)) }, enabled = !busy && !finished) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            items(messages, key = { it.id }) { Bubble(it) }
            if (busy) item { CircularProgressIndicator(Modifier.padding(12.dp)) }
            error?.let { item { ErrorState(it) } }
            if (finished) item { Text(stringResource(R.string.teach_finished), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp)) }
        }
        if (!finished) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.your_answer)) })
            IconButton({ vm.send(input); input = "" }, enabled = input.isNotBlank() && !busy) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
            }
        }
    }
}

@Composable
fun levelLabel(l: TeachingLevel) = stringResource(
    when (l) {
        TeachingLevel.BEGINNER -> R.string.level_beginner
        TeachingLevel.BASIC -> R.string.level_basic
        TeachingLevel.INTERMEDIATE -> R.string.level_intermediate
        TeachingLevel.ADVANCED -> R.string.level_advanced
        TeachingLevel.EXPERT -> R.string.level_expert
    },
)

@Composable
private fun Bubble(m: TeachMessageEntity) {
    val tutor = m.role == "tutor"
    val turn = remember(m.metadata) { m.metadata?.let { runCatching { AppJson.decodeFromString(TeachTurn.serializer(), it) }.getOrNull() } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (tutor) Arrangement.Start else Arrangement.End) {
        Surface(
            color = if (tutor) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(m.content, style = MaterialTheme.typography.bodyMedium)
                turn?.source?.let { SourceChip(it) }
            }
        }
    }
}
