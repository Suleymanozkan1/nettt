package com.bookmind.app.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.UserNoteEntity
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.repo.NoteRepository
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesViewModel @Inject constructor(handle: SavedStateHandle, private val notes: NoteRepository, library: LibraryRepository) : ViewModel() {
    val bookId: String? = handle["bookId"]
    val query = MutableStateFlow("")
    val list = query.flatMapLatest { notes.notes(bookId, it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val books = library.books().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun favorite(n: UserNoteEntity) = viewModelScope.launch { notes.toggleFavorite(n) }
    fun delete(n: UserNoteEntity) = viewModelScope.launch { notes.delete(n) }
    fun review(n: UserNoteEntity) = viewModelScope.launch { notes.addToReview(n) }
}

@Composable
fun NotesScreen(bookId: String?, onBack: () -> Unit, onEdit: (String, String?) -> Unit, vm: NotesViewModel = hiltViewModel()) {
    val notes by vm.list.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val titles = books.associate { it.id to it.title }
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.notes_title), onBack) },
        floatingActionButton = {
            val target = bookId ?: books.firstOrNull()?.id
            if (target != null) FloatingActionButton({ onEdit(target, null) }) { Icon(Icons.Filled.Add, stringResource(R.string.add_note)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(query, { vm.query.value = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_notes)) }, singleLine = true)
            if (notes.isEmpty()) EmptyState(stringResource(R.string.notes_empty_title), stringResource(R.string.notes_empty_body))
            LazyColumn(Modifier.fillMaxSize()) {
                items(notes, key = { it.id }) { n ->
                    ListItem(
                        headlineContent = { Text(n.content.ifBlank { n.highlight.orEmpty() }, maxLines = 3) },
                        overlineContent = { Text(titles[n.bookId].orEmpty()) },
                        supportingContent = { n.highlight?.takeIf { n.content.isNotBlank() }?.let { Text("“$it”", maxLines = 2, style = MaterialTheme.typography.bodySmall) } },
                        trailingContent = {
                            Row {
                                IconButton({ vm.favorite(n) }) { Icon(if (n.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(R.string.favorite)) }
                                if (!n.inReviewQueue) IconButton({ vm.review(n) }) { Icon(Icons.Filled.Replay, stringResource(R.string.add_to_review)) }
                                IconButton({ vm.delete(n) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                            }
                        },
                        modifier = Modifier.clickable { onEdit(n.bookId, n.id) },
                    )
                }
            }
        }
    }
}

@HiltViewModel
class NoteEditViewModel @Inject constructor(private val notes: NoteRepository) : ViewModel() {
    suspend fun load(id: String) = notes.get(id)
    fun save(id: String?, bookId: String, chapterId: String?, content: String, highlight: String?, review: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        notes.save(id, bookId, chapterId, content, highlight, null, review)
        onDone()
    }
}

@Composable
fun NoteEditScreen(bookId: String, noteId: String?, chapterId: String?, highlight: String?, onDone: () -> Unit, vm: NoteEditViewModel = hiltViewModel()) {
    var content by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf(highlight.orEmpty()) }
    var review by remember { mutableStateOf(false) }
    var chapter by remember { mutableStateOf(chapterId) }
    LaunchedEffect(noteId) {
        noteId?.let { vm.load(it) }?.let { n -> content = n.content; quote = n.highlight.orEmpty(); review = n.inReviewQueue; chapter = n.chapterId }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(if (noteId == null) R.string.add_note else R.string.edit_note), onDone)
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(quote, { quote = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.highlight)) })
            OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(top = 8.dp), label = { Text(stringResource(R.string.note_text)) })
            Row(Modifier.fillMaxWidth().toggleable(review, role = Role.Checkbox) { review = it }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(review, null); Text(stringResource(R.string.add_to_review))
            }
            Button({ vm.save(noteId, bookId, chapter, content, quote, review, onDone) }, enabled = content.isNotBlank() || quote.isNotBlank()) { Text(stringResource(R.string.save)) }
        }
    }
}
