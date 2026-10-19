package com.bookmind.app.ui.teach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.ErrorState
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.SourceChip
import com.bookmind.app.ui.theme.extended
import com.bookmind.app.util.AppJson
import com.bookmind.core.ai.TeachTurn
import com.bookmind.core.ai.TeachingLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(R.string.teacher_title), onBack) {
            Box {
                Row(Modifier.padding(end = 8.dp).clip(CircleShape).background(cs.primaryContainer).clickable { levelMenu = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(levelLabel(level), style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                    Icon(Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = cs.onPrimaryContainer)
                }
                DropdownMenu(levelMenu, { levelMenu = false }) {
                    TeachingLevel.entries.forEach { l -> DropdownMenuItem({ Text(levelLabel(l)) }, { vm.level.value = l; levelMenu = false }) }
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(teachStyles) { (key, label) -> ChoiceChip(false, stringResource(label), { vm.restyle(key) }, enabled = !busy && !finished) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            items(messages, key = { it.id }) { Bubble(it) }
            if (busy) item { TypingBubble() }
            error?.let { item { ErrorState(it) } }
            if (finished) item {
                AppCard(Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.extended.successContainer, border = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.extended.success)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.teach_finished), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extended.onSuccessContainer)
                    }
                }
            }
        }
        if (!finished) Surface(color = MaterialTheme.extended.card, shadowElevation = 0.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.your_answer)) }, shape = RoundedCornerShape(26.dp),
                    maxLines = 5, colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.extended.cardBorder, unfocusedContainerColor = cs.surfaceContainer,
                        focusedContainerColor = cs.surfaceContainer))
                Spacer(Modifier.width(8.dp))
                val canSend = input.isNotBlank() && !busy
                Box(Modifier.size(50.dp).clip(CircleShape).background(if (canSend) cs.primary else cs.surfaceContainerHighest)
                    .clickable(enabled = canSend) { vm.send(input); input = "" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send), tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant)
                }
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
private fun TypingBubble() {
    Row(Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.extended.card).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline)) }
    }
}

@Composable
private fun Bubble(m: TeachMessageEntity) {
    val tutor = m.role == "tutor"
    val cs = MaterialTheme.colorScheme
    val turn = remember(m.metadata) { m.metadata?.let { runCatching { AppJson.decodeFromString(TeachTurn.serializer(), it) }.getOrNull() } }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = if (tutor) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.Bottom) {
        if (tutor) {
            IconBadge(Icons.Filled.School, cs.onPrimary, cs.primary, size = 30.dp, iconSize = 16.dp)
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            color = if (tutor) MaterialTheme.extended.card else cs.primary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (tutor) 6.dp else 20.dp, bottomEnd = if (tutor) 20.dp else 6.dp),
            border = if (tutor) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.extended.cardBorder) else null,
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(m.content, style = MaterialTheme.typography.bodyMedium, color = if (tutor) cs.onSurface else cs.onPrimary)
                turn?.source?.let { SourceChip(it) }
            }
        }
    }
}
