package com.bookmind.app.ui.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.BookEntity
import com.bookmind.app.data.db.ProcessingStatus
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.ui.components.BookCover
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeaderAction
import com.bookmind.app.ui.components.PageHeader
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.components.SearchField
import com.bookmind.app.ui.theme.extended
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(library: LibraryRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val books = query.debounce(200).flatMapLatest { library.books(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun statusLabel(status: ProcessingStatus): String = stringResource(
    when (status) {
        ProcessingStatus.QUEUED -> R.string.status_queued
        ProcessingStatus.EXTRACTING -> R.string.status_extracting
        ProcessingStatus.NEEDS_OCR -> R.string.status_needs_ocr
        ProcessingStatus.ANALYZING -> R.string.status_analyzing
        ProcessingStatus.PARTIAL -> R.string.status_partial
        ProcessingStatus.READY -> R.string.status_ready
        ProcessingStatus.FAILED -> R.string.status_failed
    },
)

@Composable
fun LibraryScreen(onOpenBook: (String) -> Unit, onAddBook: () -> Unit, onOpenNotes: () -> Unit, onOpenConnections: () -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val books by vm.books.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            Column {
                PageHeader(stringResource(R.string.library_title)) {
                    HeaderAction(Icons.AutoMirrored.Filled.StickyNote2, stringResource(R.string.notes_title), onOpenNotes)
                    HeaderAction(Icons.Filled.Hub, stringResource(R.string.connections_title), onOpenConnections)
                }
                SearchField(query, { vm.query.value = it }, stringResource(R.string.library_search), Modifier.padding(horizontal = Gutter, vertical = 8.dp))
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddBook, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add_book), style = MaterialTheme.typography.labelLarge) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.large,
            )
        },
    ) { padding ->
        val list = books
        when {
            list == null -> Unit
            list.isEmpty() && query.isBlank() -> EmptyState(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body),
                Modifier.padding(padding), actionLabel = stringResource(R.string.add_book), onAction = onAddBook)
            list.isEmpty() -> EmptyState(stringResource(R.string.no_results), stringResource(R.string.no_results_body), Modifier.padding(padding), icon = Icons.Filled.SearchOff)
            else -> LazyVerticalGrid(
                GridCells.Adaptive(150.dp),
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(pluralStringCount(list.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(list, key = { it.id }) { BookTile(it) { onOpenBook(it.id) } }
            }
        }
    }
}

@Composable
private fun pluralStringCount(n: Int) = androidx.compose.ui.res.pluralStringResource(R.plurals.books_count, n, n)

@Composable
private fun BookTile(book: BookEntity, onClick: () -> Unit) {
    val x = MaterialTheme.extended
    Column(Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(bottom = 12.dp)) {
        BookCover(book.title, book.author, book.id, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(book.author ?: stringResource(R.string.unknown_author), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            book.completedAt != null -> Pill(stringResource(R.string.completed), Modifier.padding(top = 6.dp), x.onSuccessContainer, x.successContainer, Icons.Filled.CheckCircle)
            book.processingStatus in setOf(ProcessingStatus.EXTRACTING, ProcessingStatus.ANALYZING, ProcessingStatus.QUEUED) -> {
                Text(statusLabel(book.processingStatus), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                ProgressBar(book.processingProgress / 100f, Modifier.padding(top = 4.dp), height = 4.dp)
            }
            book.processingStatus != ProcessingStatus.READY -> Pill(statusLabel(book.processingStatus), Modifier.padding(top = 6.dp), x.onWarningContainer, x.warningContainer)
        }
    }
}
