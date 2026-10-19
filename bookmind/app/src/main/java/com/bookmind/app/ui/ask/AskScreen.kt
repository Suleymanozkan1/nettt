package com.bookmind.app.ui.ask

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.QuestionAnswer
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.processing.BookProcessor
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.OriginBadge
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.theme.extended
import com.bookmind.core.ai.AiResult
import com.bookmind.core.ai.GenerationInfo
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.search.Bm25Index
import com.bookmind.core.search.Passage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AskViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val library: LibraryRepository,
    private val ai: LearningAi,
) : ViewModel() {
    val bookId: String = handle["bookId"]!!

    data class Exchange(
        val question: String,
        val answer: String? = null,
        val sources: List<Passage> = emptyList(),
        val info: GenerationInfo? = null,
        val warnings: List<String> = emptyList(),
        val inBook: Boolean = true,
    )

    private val _exchanges = MutableStateFlow<List<Exchange>>(emptyList())
    val exchanges = _exchanges.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var index: Bm25Index? = null

    private suspend fun index(): Bm25Index = index ?: run {
        val chapters = library.chapters(bookId).first()
        Bm25Index(Bm25Index.passages(chapters.map { Triple(it.orderIndex, it.title, library.chapterText(it)) })).also { index = it }
    }

    /** Keyword retrieval (BM25, no AI) picks the passages; the chosen model, or the extractive fallback, answers from them. */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _busy.value) return
        _exchanges.value = _exchanges.value + Exchange(q)
        _busy.value = true
        viewModelScope.launch {
            val passages = index().search(q, 4).map { it.passage }
            val result = ai.answer(q, passages, AppLanguage.tag)
            val exchange = when (result) {
                is AiResult.Ok -> Exchange(q, result.value.answer, result.value.citations.mapNotNull { passages.getOrNull(it) }, result.info, result.warnings, result.value.inBook)
                is AiResult.Failed -> Exchange(q, AppLanguage.text.aiError(result.error), emptyList(), null, emptyList(), false)
            }
            _exchanges.value = _exchanges.value.dropLast(1) + exchange
            _busy.value = false
        }
    }
}

@Composable
fun AskScreen(bookId: String, onBack: () -> Unit, onOpenChapter: (String) -> Unit, vm: AskViewModel = hiltViewModel()) {
    val exchanges by vm.exchanges.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    // Close the keyboard once a question is sent so the answer is not hidden behind it.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val send = { q: String -> focus.clearFocus(); vm.ask(q) }
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(exchanges.size, busy) { if (exchanges.isNotEmpty()) list.animateScrollToItem(exchanges.lastIndex) }
    val examples = listOf(stringResource(R.string.ask_example_1), stringResource(R.string.ask_example_2))

    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(R.string.ask_title), onBack)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp)) {
            if (exchanges.isEmpty()) item {
                EmptyState(stringResource(R.string.ask_empty_title), stringResource(R.string.ask_empty_body), icon = Icons.Filled.QuestionAnswer)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    examples.forEach { e -> ChoiceChip(false, e, { send(e) }) }
                }
            }
            items(exchanges) { ex ->
                // Question bubble
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                    Surface(color = cs.primary, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                        Text(ex.question, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = cs.onPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                // Answer bubble
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Surface(
                        color = MaterialTheme.extended.card, shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
                        border = BorderStroke(1.dp, MaterialTheme.extended.cardBorder), modifier = Modifier.widthIn(max = 340.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            if (ex.answer == null) Text(stringResource(R.string.ask_thinking), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            else {
                                Text(ex.answer, style = MaterialTheme.typography.bodyMedium)
                                if (ex.info != null) Row(Modifier.padding(top = 10.dp)) { OriginBadge(ex.info) }
                                WarningBanner(ex.warnings)
                                if (ex.sources.isNotEmpty()) {
                                    Text(stringResource(R.string.ask_sources), style = MaterialTheme.typography.labelSmall, color = cs.primary, modifier = Modifier.padding(top = 10.dp))
                                    ex.sources.distinctBy { it.chapterIndex }.forEach { p ->
                                        Row(
                                            Modifier.padding(top = 6.dp).clip(CircleShape).background(cs.surfaceContainerHigh)
                                                .clickable { onOpenChapter(BookProcessor.chapterId(bookId, p.chapterIndex)) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(14.dp), tint = cs.primary)
                                            Spacer(Modifier.width(6.dp))
                                            Text(AppLanguage.text.chapter(p.chapterIndex + 1) + " – " + p.chapterTitle, style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.extended.card) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.ask_placeholder)) }, maxLines = 4,
                    shape = RoundedCornerShape(26.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.extended.cardBorder, unfocusedContainerColor = cs.surfaceContainer, focusedContainerColor = cs.surfaceContainer))
                Spacer(Modifier.width(8.dp))
                val canSend = input.isNotBlank() && !busy
                Box(Modifier.size(50.dp).clip(CircleShape).background(if (canSend) cs.primary else cs.surfaceContainerHighest)
                    .clickable(enabled = canSend) { send(input); input = "" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send), tint = if (canSend) cs.onPrimary else cs.onSurfaceVariant)
                }
            }
        }
    }
}
