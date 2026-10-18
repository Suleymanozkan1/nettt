package com.bookmind.app.ui.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.BookEntity
import com.bookmind.app.data.db.ProcessingStatus
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.ui.components.EmptyState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenBook: (String) -> Unit, onAddBook: () -> Unit, onOpenNotes: () -> Unit, onOpenConnections: () -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val books by vm.books.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onOpenNotes) { Icon(Icons.Filled.StickyNote2, stringResource(R.string.notes_title)) }
                    IconButton(onOpenConnections) { Icon(Icons.Filled.Hub, stringResource(R.string.connections_title)) }
                },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAddBook, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add_book)) }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                query, { vm.query.value = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.library_search)) }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
            )
            val list = books
            when {
                list == null -> Unit
                list.isEmpty() && query.isBlank() -> EmptyState(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body), stringResource(R.string.add_book), onAddBook)
                list.isEmpty() -> EmptyState(stringResource(R.string.no_results), stringResource(R.string.no_results_body))
                else -> LazyColumn(Modifier.fillMaxSize()) { items(list, key = { it.id }) { BookRow(it) { onOpenBook(it.id) } } }
            }
        }
    }
}

@Composable
private fun BookRow(book: BookEntity, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(book.title, maxLines = 2) },
        supportingContent = {
            Column {
                Text(listOfNotNull(book.author, book.sourceType.name).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (book.processingStatus != ProcessingStatus.READY) {
                    Text(statusLabel(book.processingStatus), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (book.processingStatus in setOf(ProcessingStatus.EXTRACTING, ProcessingStatus.ANALYZING, ProcessingStatus.QUEUED)) {
                        LinearProgressIndicator({ book.processingProgress / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
