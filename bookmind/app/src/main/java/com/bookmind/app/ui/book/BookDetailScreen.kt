package com.bookmind.app.ui.book

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.ProcessingStatus
import com.bookmind.app.data.files.ImportException
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.repo.StudyRepository
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.BookCover
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.components.SecondaryButton
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.theme.QuoteStyle
import com.bookmind.app.ui.theme.extended
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
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended

    Column(Modifier.fillMaxSize()) {
        BackTopBar("", onBack) {
            IconButton({ editing = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
            IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete_book)) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 40.dp)) {
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    BookCover(b.title, b.author, b.id, Modifier.width(118.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.headlineSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text(b.author ?: stringResource(R.string.unknown_author), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            analysis?.let { Pill(genreLabel(it.genre.name)) }
                            if (b.chapterCount > 0) Pill(pluralStringResource(R.plurals.chapters_n, b.chapterCount, b.chapterCount), color = cs.onSurfaceVariant, container = cs.surfaceContainerHigh)
                            if (b.completedAt != null) Pill(stringResource(R.string.completed), color = x.onSuccessContainer, container = x.successContainer, icon = Icons.Filled.CheckCircle)
                        }
                    }
                }
                analysis?.mainMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                    Row(Modifier.padding(top = 18.dp)) {
                        Box(Modifier.width(3.dp).height(48.dp).clip(CircleShape).background(cs.primary))
                        Spacer(Modifier.width(12.dp))
                        Text("“$msg”", style = QuoteStyle, color = cs.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                ProcessingCard(b.processingStatus, b.processingProgress, b.processingMessage, onRetry = { vm.retry() }, onOcr = { vm.retry(ocr = true) })
            }
            if (usable) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(stringResource(R.string.review), onReview, Modifier.weight(1f), icon = Icons.Filled.Style)
                        SecondaryButton(stringResource(R.string.quiz), onQuiz, Modifier.weight(1f), icon = Icons.Filled.Quiz)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionTile(Icons.Filled.ViewTimeline, stringResource(R.string.plan), cs.primary, cs.primaryContainer, Modifier.weight(1f), onPlan)
                        ActionTile(Icons.AutoMirrored.Filled.StickyNote2, stringResource(R.string.notes_short), x.streak, x.warningContainer, Modifier.weight(1f), onNotes)
                        ActionTile(
                            if (b.completedAt == null) Icons.Outlined.CheckCircle else Icons.Filled.CheckCircle,
                            stringResource(if (b.completedAt == null) R.string.mark_completed else R.string.mark_not_completed),
                            x.success, x.successContainer, Modifier.weight(1f),
                        ) { vm.complete(b.completedAt == null) }
                    }
                }
                item { SectionHeader(stringResource(R.string.summaries)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(summaryKinds, key = { it.type.name }) { k ->
                            SummaryCard(k) { onOpenSummary(k.type.name, null) }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 12.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
                    Segment(stringResource(R.string.chapters) + " · ${chapters.size}", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    Segment(stringResource(R.string.concepts) + " · ${concepts.size}", tab == 1, Modifier.weight(1f)) { tab = 1 }
                }
            }
            if (tab == 0) {
                if (chapters.isEmpty()) item { EmptyState(stringResource(R.string.no_chapters_yet), stringResource(R.string.no_chapters_yet_body)) }
                items(chapters, key = { it.id }) { ch ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onOpenChapter(ch.id) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                            Text("${ch.orderIndex + 1}", style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(stringResource(R.string.words_count, ch.wordCount), stringResource(R.string.read_minutes, (ch.wordCount / 200).coerceAtLeast(1)),
                                    if (ch.processingStatus != ProcessingStatus.READY) statusLabel(ch.processingStatus) else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                if (concepts.isEmpty()) item { EmptyState(stringResource(R.string.no_concepts_yet), stringResource(R.string.no_concepts_yet_body), icon = Icons.Filled.Lightbulb) }
                items(concepts, key = { it.id }) { c ->
                    AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = { onOpenConcept(c.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (c.isFavorite) Icon(Icons.Filled.Favorite, stringResource(R.string.favorite), tint = cs.primary, modifier = Modifier.size(18.dp))
                        }
                        Text(c.explanation, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_book)) },
        text = { Text(stringResource(R.string.delete_book_confirm)) },
        confirmButton = { TextButton({ confirmDelete = false; vm.delete(onDeleted) }) { Text(stringResource(R.string.delete), color = cs.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (editing) {
        var title by remember { mutableStateOf(b.title) }
        var author by remember { mutableStateOf(b.author.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.book_title)) }, singleLine = true, shape = MaterialTheme.shapes.medium)
                    OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.book_author)) }, singleLine = true, shape = MaterialTheme.shapes.medium)
                }
            },
            confirmButton = { TextButton({ editing = false; vm.rename(title, author) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private data class SummaryKind(val type: SummaryType, val title: Int, val hint: Int, val icon: ImageVector)

private val summaryKinds = listOf(
    SummaryKind(SummaryType.QUICK, R.string.summary_quick, R.string.summary_quick_hint, Icons.Filled.Bolt),
    SummaryKind(SummaryType.KEY_IDEAS, R.string.summary_key_ideas, R.string.summary_key_ideas_hint, Icons.Filled.Lightbulb),
    SummaryKind(SummaryType.DETAILED, R.string.summary_detailed, R.string.summary_detailed_hint, Icons.Filled.Notes),
    SummaryKind(SummaryType.EXECUTIVE, R.string.summary_executive, R.string.summary_executive_hint, Icons.Filled.BusinessCenter),
    SummaryKind(SummaryType.CONCEPT_MAP, R.string.summary_concept_map, R.string.summary_concept_map_hint, Icons.Filled.AccountTree),
    SummaryKind(SummaryType.CHAPTER_COMPARISON, R.string.summary_comparison, R.string.summary_comparison_hint, Icons.Filled.CompareArrows),
)

@Composable
private fun SummaryCard(k: SummaryKind, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.width(156.dp).height(132.dp), onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        IconBadge(k.icon, cs.primary, cs.primaryContainer, size = 34.dp, iconSize = 18.dp)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(k.title), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(k.hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, tint: Color, container: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppCard(modifier.height(92.dp), onClick = onClick, contentPadding = PaddingValues(10.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            IconBadge(icon, tint, container, size = 36.dp, iconSize = 20.dp)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.clip(CircleShape).background(if (selected) MaterialTheme.extended.card else Color.Transparent)
            .selectable(selected, role = Role.Tab, onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onSurface else cs.onSurfaceVariant)
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
private fun processingMessage(message: String?): String? = when (message) {
    null, "" -> null
    ImportException.Reason.DRM.name -> stringResource(R.string.import_drm)
    ImportException.Reason.NO_TEXT.name -> stringResource(R.string.import_no_text)
    ImportException.Reason.UNREADABLE.name -> stringResource(R.string.import_unreadable)
    ImportException.Reason.UNSUPPORTED.name -> stringResource(R.string.import_unsupported)
    else -> message
}

@Composable
private fun ProcessingCard(status: ProcessingStatus, progress: Int, message: String?, onRetry: () -> Unit, onOcr: () -> Unit) {
    val text = processingMessage(message)
    if (status == ProcessingStatus.READY) {
        text?.let { WarningBanner(it.lines(), Modifier.padding(top = 12.dp)) }
        return
    }
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statusLabel(status), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (status in setOf(ProcessingStatus.QUEUED, ProcessingStatus.EXTRACTING, ProcessingStatus.ANALYZING)) {
                Text("%$progress", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            }
        }
        when (status) {
            ProcessingStatus.QUEUED, ProcessingStatus.EXTRACTING, ProcessingStatus.ANALYZING -> {
                ProgressBar(progress / 100f, Modifier.padding(vertical = 10.dp))
                text?.let { Text(stringResource(R.string.processing_now, it), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                Text(stringResource(R.string.processing_partial_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            ProcessingStatus.NEEDS_OCR -> {
                Text(stringResource(R.string.ocr_explain), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                PrimaryButton(stringResource(R.string.run_ocr), onOcr, Modifier.padding(top = 12.dp))
            }
            ProcessingStatus.PARTIAL, ProcessingStatus.FAILED -> {
                text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                SecondaryButton(stringResource(R.string.retry), onRetry, Modifier.padding(top = 12.dp))
            }
            ProcessingStatus.READY -> Unit
        }
    }
}
