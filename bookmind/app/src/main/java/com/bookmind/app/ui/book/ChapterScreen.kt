package com.bookmind.app.ui.book

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.ChapterEntity
import com.bookmind.app.data.db.ConceptEntity
import com.bookmind.app.data.db.SessionType
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.repo.SessionTracker
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.OriginBadge
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.theme.ReadingStyle
import com.bookmind.app.ui.theme.extended
import com.bookmind.app.util.parseInfo
import com.bookmind.core.text.TextNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ChapterViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val library: LibraryRepository,
    private val db: com.bookmind.app.data.db.BookMindDatabase,
    private val sessions: SessionTracker,
) : ViewModel() {
    data class State(val chapter: ChapterEntity, val paragraphs: List<String>, val concepts: List<ConceptEntity>)

    private val chapterId: String = handle["chapterId"]!!
    private val _state = MutableStateFlow<State?>(null)
    val state = _state.asStateFlow()
    private var sessionId: String? = null

    init {
        viewModelScope.launch {
            val ch = library.chapter(chapterId) ?: return@launch
            _state.value = State(ch, TextNormalizer.paragraphs(library.chapterText(ch)), db.concepts().forChapter(ch.id))
            sessionId = sessions.start(SessionType.READ, ch.bookId)
        }
    }

    fun stopReading() { sessionId?.let(sessions::finishDetached); sessionId = null }
    override fun onCleared() = stopReading()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChapterScreen(
    chapterId: String,
    onBack: () -> Unit,
    onOpenConcept: (String) -> Unit,
    onSummary: (String, String) -> Unit,
    onAddNote: (String, String, String?) -> Unit,
    vm: ChapterViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.stopReading() } }
    val s = state ?: run { LoadingState(); return }
    val ch = s.chapter
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        val speaker = com.bookmind.app.ui.components.rememberSpeaker()
        BackTopBar("", onBack) {
            com.bookmind.app.ui.components.ListenButton(speaker) { ch.title + ".\n" + s.paragraphs.joinToString("\n") }
            IconButton({ onSummary(ch.bookId, ch.id) }) { Icon(Icons.Filled.Summarize, stringResource(R.string.summary_chapter)) }
            IconButton({ onAddNote(ch.bookId, ch.id, null) }) { Icon(Icons.Filled.NoteAdd, stringResource(R.string.add_note)) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 48.dp)) {
            item {
                Text(stringResource(R.string.chapter_n, ch.orderIndex + 1).uppercase(com.bookmind.app.data.settings.AppLanguage.locale), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                Text(ch.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp))
                Text(listOfNotNull(stringResource(R.string.words_count, ch.wordCount), listOfNotNull(ch.startLocation, ch.endLocation).joinToString(" – ").ifBlank { null })
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            ch.summary?.let { summary ->
                item {
                    AppCard(Modifier.fillMaxWidth().padding(top = 18.dp), color = cs.primaryContainer, border = false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.chapter_summary), style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
                            OriginBadge(parseInfo(ch.generatedBy))
                        }
                        Text(summary, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (s.concepts.isNotEmpty()) item {
                SectionHeader(stringResource(R.string.concepts))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.concepts.forEach { c -> ChoiceChip(false, c.title, { onOpenConcept(c.id) }, icon = Icons.Filled.Lightbulb) }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 22.dp, bottom = 10.dp), color = MaterialTheme.extended.cardBorder)
                Text(stringResource(R.string.long_press_note_hint), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            items(s.paragraphs) { p ->
                val noteLabel = stringResource(R.string.add_note)
                SelectionContainer {
                    Text(
                        p,
                        style = ReadingStyle,
                        modifier = Modifier.padding(vertical = 9.dp)
                            .combinedClickable(onClick = {}, onLongClick = { onAddNote(ch.bookId, ch.id, p) })
                            .semantics { onLongClick(noteLabel) { onAddNote(ch.bookId, ch.id, p); true } },
                    )
                }
            }
        }
    }
}
