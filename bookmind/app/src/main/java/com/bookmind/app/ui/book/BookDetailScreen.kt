package com.bookmind.app.ui.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.ProcessingStatus
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.repo.StudyRepository
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.util.AppJson
import com.bookmind.core.ai.BookAnalysis
import com.bookmind.core.ai.SummaryType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val library: LibraryRepository,
    study: StudyRepository,
) : ViewModel() {
    val bookId: String = handle["bookId"]!!
    val book = library.book(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val chapters = library.chapters(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val concepts = study.concepts(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { viewModelScope.launch { library.opened(bookId) } }
    fun retry(ocr: Boolean = false) = library.retry(bookId, ocr)
    fun delete(onDone: () -> Unit) = viewModelScope.launch { library.delete(bookId); onDone() }
    fun rename(title: String, author: String) = viewModelScope.launch { library.updateMetadata(bookId, title, author) }
    fun complete(done: Boolean) = viewModelScope.launch { library.markCompleted(bookId, done) }
}

@Composable
fun BookDetailScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenSummary: (String, String?) -> Unit,
    onOpenConcept: (String) -> Unit,
    onQuiz: () -> Unit,
    onReview: () -> Unit,
    onNotes: () -> Unit,
    onPlan: () -> Unit,
    onDeleted: () -> Unit,
    vm: BookDetailViewModel = hiltViewModel(),
) {
    val book by vm.book.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val concepts by vm.concepts.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val b = book ?: run { LoadingState(); return }
    if (b.deletedAt != null) { LaunchedEffect(Unit) { onDeleted() }; return }
    val analysis = remember(b.analysisJson) { b.analysisJson?.let { runCatching { AppJson.decodeFromString(BookAnalysis.serializer(), it) }.getOrNull() } }
    val usable = b.processingStatus == ProcessingStatus.READY || b.processingStatus == ProcessingStatus.PARTIAL || chapters.any { it.processingStatus == ProcessingStatus.READY }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(b.title, onBack) {
            IconButton({ editing = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
            IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete_book)) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(b.author ?: stringResource(R.string.unknown_author), style = MaterialTheme.typography.bodyMedium)
                analysis?.let { a ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({}, { Text(genreLabel(a.genre.name)) })
                        a.language?.let { AssistChip({}, { Text(it.uppercase()) }) }
                        AssistChip({}, { Text(stringResource(R.string.chapters_count, b.chapterCount)) })
                    }
                    if (a.mainMessage.isNotBlank()) Text(a.mainMessage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
                ProcessingCard(b.processingStatus, b.processingProgress, b.processingMessage, onRetry = { vm.retry() }, onOcr = { vm.retry(ocr = true) })
            }
            if (usable) item {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onReview) { Icon(Icons.Filled.Replay, null); Text(" " + stringResource(R.string.review)) }
                    FilledTonalButton(onQuiz) { Icon(Icons.Filled.Quiz, null); Text(" " + stringResource(R.string.quiz)) }
                    FilledTonalButton(onPlan) { Icon(Icons.Filled.ViewTimeline, null); Text(" " + stringResource(R.string.plan)) }
                    FilledTonalButton(onNotes) { Icon(Icons.Filled.StickyNote2, null); Text(" " + stringResource(R.string.notes_title)) }
                    FilledTonalButton({ vm.complete(b.completedAt == null) }) {
                        Icon(Icons.Filled.CheckCircle, null); Text(" " + stringResource(if (b.completedAt == null) R.string.mark_completed else R.string.mark_not_completed))
                    }
                }
                Text(stringResource(R.string.summaries), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SummaryType.QUICK to R.string.summary_quick, SummaryType.DETAILED to R.string.summary_detailed,
                        SummaryType.EXECUTIVE to R.string.summary_executive, SummaryType.KEY_IDEAS to R.string.summary_key_ideas,
                        SummaryType.CONCEPT_MAP to R.string.summary_concept_map, SummaryType.CHAPTER_COMPARISON to R.string.summary_comparison,
                    ).forEach { (t, label) -> AssistChip({ onOpenSummary(t.name, null) }, { Text(stringResource(label)) }) }
                }
            }
            item {
                PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 12.dp)) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.chapters)) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.concepts) + " (${concepts.size})") })
                }
            }
            if (tab == 0) {
                if (chapters.isEmpty()) item { EmptyState(stringResource(R.string.no_chapters_yet), stringResource(R.string.no_chapters_yet_body)) }
                items(chapters, key = { it.id }) { ch ->
                    ListItem(
                        headlineContent = { Text("${ch.orderIndex + 1}. ${ch.title}") },
                        supportingContent = {
                            Text(listOfNotNull(ch.startLocation, stringResource(R.string.words_count, ch.wordCount),
                                if (ch.processingStatus != ProcessingStatus.READY) statusLabel(ch.processingStatus) else null).joinToString(" · "))
                        },
                        modifier = Modifier.clickable { onOpenChapter(ch.id) },
                    )
                }
            } else {
                if (concepts.isEmpty()) item { EmptyState(stringResource(R.string.no_concepts_yet), stringResource(R.string.no_concepts_yet_body)) }
                items(concepts, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.title) },
                        supportingContent = { Text(c.explanation, maxLines = 2) },
                        trailingContent = { if (c.isFavorite) Icon(Icons.Filled.Favorite, stringResource(R.string.favorite), tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenConcept(c.id) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_book)) },
        text = { Text(stringResource(R.string.delete_book_confirm)) },
        confirmButton = { TextButton({ confirmDelete = false; vm.delete(onDeleted) }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (editing) {
        var title by remember { mutableStateOf(b.title) }
        var author by remember { mutableStateOf(b.author.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.edit)) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.book_title)) }, singleLine = true)
                    OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.book_author)) }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ editing = false; vm.rename(title, author) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun genreLabel(name: String): String = stringResource(
    when (name) {
        "FICTION" -> R.string.genre_fiction
        "SELF_DEVELOPMENT" -> R.string.genre_self
        "BIOGRAPHY" -> R.string.genre_biography
        "TECHNICAL" -> R.string.genre_technical
        "MIXED" -> R.string.genre_mixed
        else -> R.string.genre_informational
    },
)

@Composable
private fun ProcessingCard(status: ProcessingStatus, progress: Int, message: String?, onRetry: () -> Unit, onOcr: () -> Unit) {
    if (status == ProcessingStatus.READY) {
        message?.takeIf { it.isNotBlank() }?.let { WarningBanner(it.lines(), Modifier.padding(top = 8.dp)) }
        return
    }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(statusLabel(status), style = MaterialTheme.typography.titleSmall)
            when (status) {
                ProcessingStatus.QUEUED, ProcessingStatus.EXTRACTING, ProcessingStatus.ANALYZING -> {
                    LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    message?.let { Text(stringResource(R.string.processing_now, it), style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.processing_partial_hint), style = MaterialTheme.typography.bodySmall)
                }
                ProcessingStatus.NEEDS_OCR -> {
                    Text(stringResource(R.string.ocr_explain), style = MaterialTheme.typography.bodySmall)
                    Button(onOcr, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.run_ocr)) }
                }
                ProcessingStatus.PARTIAL, ProcessingStatus.FAILED -> {
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(onRetry, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.retry)) }
                }
                ProcessingStatus.READY -> Unit
            }
        }
    }
}
