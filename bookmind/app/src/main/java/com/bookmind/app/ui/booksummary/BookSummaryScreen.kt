package com.bookmind.app.ui.booksummary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.bookmind.app.data.summary.BookSummaryRepository
import com.bookmind.app.data.summary.BookSummaryRepository.Phase
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.GhostButton
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.MarkdownText
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.theme.extended
import com.bookmind.core.ai.summary.BookSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class BookSummaryViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: BookSummaryRepository,
    library: LibraryRepository,
) : ViewModel() {
    val bookId: String = handle["bookId"]!!
    val model: String get() = repo.modelName
    val ratio: Double get() = repo.settingsInfo.chapterRatio
    val book = library.book(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val status: StateFlow<BookSummaryRepository.Status> = repo.observeStatus(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, repo.status(bookId))

    private val _unavailable = MutableStateFlow<BookSummaryRepository.Unavailable?>(null)
    val unavailable = _unavailable.asStateFlow()
    private val _summary = MutableStateFlow<BookSummary?>(null)
    val summary = _summary.asStateFlow()

    init {
        viewModelScope.launch { _unavailable.value = repo.unavailable() }
        // Reload the stored summary whenever a request finishes.
        viewModelScope.launch { status.distinctUntilChangedBy { it.phase to it.done }.collect { _summary.value = repo.summary(bookId) } }
    }

    fun start() = viewModelScope.launch { repo.request(bookId) }
    fun regenerate() = viewModelScope.launch { repo.request(bookId, force = true) }
    fun refreshAvailability() = viewModelScope.launch { _unavailable.value = repo.unavailable() }
}

/** The book page's card: start the summary, follow its progress, or open it. */
@Composable
fun BookSummaryCard(onOpen: () -> Unit, onOpenAi: () -> Unit, modifier: Modifier = Modifier, vm: BookSummaryViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val unavailable by vm.unavailable.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshAvailability() }
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    AppCard(modifier.fillMaxWidth().testTag("book_summary_card"), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.AutoAwesome, cs.onPrimary, cs.primary, size = 40.dp, iconSize = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.book_summary_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.book_summary_subtitle, vm.model), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(12.dp))
        when {
            unavailable == BookSummaryRepository.Unavailable.NO_API_KEY -> Text(stringResource(R.string.book_summary_no_key), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            unavailable == BookSummaryRepository.Unavailable.AI_OFF -> {
                Text(stringResource(R.string.book_summary_ai_off), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                GhostButton(stringResource(R.string.book_summary_ai_settings), onOpenAi, Modifier.padding(top = 10.dp))
            }
            else -> when (status.phase) {
                Phase.NOT_STARTED -> {
                    Text(stringResource(R.string.book_summary_intro, (vm.ratio * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.book_summary_privacy), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    PrimaryButton(stringResource(R.string.book_summary_start), { vm.start() }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = Icons.Filled.AutoAwesome)
                }
                Phase.QUEUED -> Progress(stringResource(R.string.book_summary_queued), null)
                Phase.RUNNING -> Progress(stringResource(R.string.book_summary_running, status.done, status.total), if (status.total > 0) status.done / status.total.toFloat() else null)
                Phase.MERGING -> Progress(stringResource(R.string.book_summary_merging), null)
                Phase.WAITING_FOR_LIMIT -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, null, tint = x.warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.book_summary_waiting, time(status.retryAt)), style = MaterialTheme.typography.bodyMedium, color = x.onWarningContainer)
                    }
                    if (status.total > 0) ProgressBar(status.done / status.total.toFloat(), Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                Phase.FAILED -> {
                    Text(status.error ?: stringResource(R.string.book_summary_failed), style = MaterialTheme.typography.bodyMedium, color = cs.error)
                    PrimaryButton(stringResource(R.string.retry), { vm.start() }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = Icons.Filled.Refresh)
                }
                Phase.DONE -> {
                    summary?.let { s ->
                        Text(stringResource(R.string.book_summary_ready, s.chapters.size, s.overallWords), style = MaterialTheme.typography.bodyMedium)
                    }
                    PrimaryButton(stringResource(R.string.book_summary_open), onOpen, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("open_book_summary"), icon = Icons.AutoMirrored.Filled.MenuBook)
                }
            }
        }
        // Chapter summaries can be read while the rest is still being made.
        if (status.phase != Phase.DONE && (summary?.chapters?.isNotEmpty() == true)) {
            GhostButton(stringResource(R.string.book_summary_read_partial, summary!!.chapters.size), onOpen, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun Progress(text: String, fraction: Float?) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    if (fraction != null) ProgressBar(fraction, Modifier.fillMaxWidth().padding(top = 10.dp))
    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp).clip(CircleShape))
}

private fun time(at: Long?): String {
    if (at == null) return "—"
    val zoned = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val sameDay = zoned.toLocalDate() == java.time.LocalDate.now()
    val pattern = if (sameDay) DateTimeFormatter.ofPattern("HH:mm", AppLanguage.locale) else DateTimeFormatter.ofPattern("d MMM HH:mm", AppLanguage.locale)
    return pattern.format(zoned)
}

/** Reader: the whole-book summary and the chapter summaries. */
@Composable
fun BookSummaryScreen(onBack: () -> Unit, vm: BookSummaryViewModel = hiltViewModel()) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val book by vm.book.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        BackTopBar(book?.title ?: stringResource(R.string.book_summary_title), onBack) {
            if (summary?.complete == true) IconButton({ confirm = true }) { Icon(Icons.Filled.Refresh, stringResource(R.string.book_summary_regenerate)) }
        }
        val s = summary ?: run { LoadingState(); return@Column }
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(bottom = 8.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
            listOf(R.string.book_summary_tab_overall, R.string.book_summary_tab_chapters).forEachIndexed { i, label ->
                val on = tab == i
                Box(
                    Modifier.weight(1f).clip(CircleShape).background(if (on) MaterialTheme.extended.card else Color.Transparent)
                        .selectable(on, role = Role.Tab) { tab = i }.heightIn(min = 44.dp).padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) cs.primary else cs.onSurfaceVariant)
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("book_summary"), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 40.dp)) {
            if (tab == 0) {
                item {
                    val overall = s.overall
                    if (overall == null) EmptyState(stringResource(R.string.book_summary_overall_pending), stringResource(R.string.book_summary_overall_pending_body))
                    else {
                        Pill(stringResource(R.string.book_summary_words, s.overallWords, s.bookWords), Modifier.padding(bottom = 14.dp))
                        MarkdownText(overall)
                    }
                    Footer(s)
                }
            } else {
                itemsIndexed(s.chapters, key = { _, c -> c.chapterIndex }) { i, c -> ChapterCard(c, startOpen = i == 0) }
                item { Footer(s) }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.book_summary_regenerate)) },
        text = { Text(stringResource(R.string.book_summary_regenerate_body)) },
        confirmButton = { TextButton({ confirm = false; vm.regenerate(); onBack() }) { Text(stringResource(R.string.book_summary_regenerate)) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChapterCard(c: com.bookmind.core.ai.summary.ChapterSummary, startOpen: Boolean) {
    var open by rememberSaveable(c.chapterIndex) { mutableStateOf(startOpen) }
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Text("${c.chapterIndex + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.book_summary_chapter_words, c.words, c.sourceWords), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = cs.onSurfaceVariant)
        }
        if (open) MarkdownText(c.summary, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Footer(s: BookSummary) {
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(AppLanguage.locale).format(Instant.ofEpochMilli(s.updatedAt).atZone(ZoneId.systemDefault()))
    Text(
        stringResource(R.string.book_summary_footer, s.model, date),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
}
