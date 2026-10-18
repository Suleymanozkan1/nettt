package com.bookmind.app.ui.book

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.OriginBadge
import com.bookmind.app.util.parseInfo
import com.bookmind.core.text.TextNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    Column(Modifier.fillMaxSize()) {
        BackTopBar(ch.title, onBack) {
            IconButton({ onSummary(ch.bookId, ch.id) }) { Icon(Icons.Filled.Summarize, stringResource(R.string.summary_chapter)) }
            IconButton({ onAddNote(ch.bookId, ch.id, null) }) { Icon(Icons.Filled.NoteAdd, stringResource(R.string.add_note)) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            ch.summary?.let { summary ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.chapter_summary), style = MaterialTheme.typography.titleSmall)
                            Text(summary, style = MaterialTheme.typography.bodyMedium)
                            OriginBadge(parseInfo(ch.generatedBy))
                        }
                    }
                }
            }
            if (s.concepts.isNotEmpty()) item {
                Text(stringResource(R.string.concepts), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                androidx.compose.foundation.layout.FlowRow {
                    s.concepts.forEach { c -> AssistChip({ onOpenConcept(c.id) }, { Text(c.title) }, Modifier.padding(end = 6.dp)) }
                }
            }
            item {
                Text(listOfNotNull(ch.startLocation, ch.endLocation).joinToString(" – "), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.long_press_note_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(s.paragraphs) { p ->
                val noteLabel = stringResource(R.string.add_note)
                SelectionContainer {
                    Text(
                        p,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 8.dp)
                            .combinedClickable(onClick = {}, onLongClick = { onAddNote(ch.bookId, ch.id, p) })
                            .semantics { onLongClick(noteLabel) { onAddNote(ch.bookId, ch.id, p); true } },
                    )
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
