package com.bookmind.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.SearchField
import com.bookmind.app.ui.theme.QuoteStyle
import com.bookmind.app.ui.theme.extended
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Scaffold(
        containerColor = cs.background,
        topBar = { BackTopBar(stringResource(R.string.notes_title), onBack) },
        floatingActionButton = {
            val target = bookId ?: books.firstOrNull()?.id
            if (target != null) FloatingActionButton({ onEdit(target, null) }, containerColor = cs.primary, contentColor = cs.onPrimary, shape = MaterialTheme.shapes.large) {
                Icon(Icons.Filled.Add, stringResource(R.string.add_note))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(query, { vm.query.value = it }, stringResource(R.string.search_notes), Modifier.padding(horizontal = Gutter, vertical = 4.dp))
            if (notes.isEmpty()) EmptyState(stringResource(R.string.notes_empty_title), stringResource(R.string.notes_empty_body), icon = Icons.AutoMirrored.Filled.StickyNote2)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 12.dp, bottom = 96.dp)) {
                items(notes, key = { it.id }) { n ->
                    AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = { onEdit(n.bookId, n.id) }) {
                        Text(titles[n.bookId].orEmpty().uppercase(com.bookmind.app.data.settings.AppLanguage.locale), style = MaterialTheme.typography.labelSmall, color = cs.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        n.highlight?.takeIf { it.isNotBlank() }?.let {
                            Row(Modifier.padding(top = 8.dp)) {
                                Box(Modifier.width(3.dp).height(40.dp).clip(CircleShape).background(x.streak))
                                Spacer(Modifier.width(10.dp))
                                Text("“$it”", style = QuoteStyle, maxLines = 3, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
                            }
                        }
                        if (n.content.isNotBlank()) Text(n.content, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                            IconButton({ vm.favorite(n) }) { Icon(if (n.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(R.string.favorite),
                                tint = if (n.isFavorite) cs.primary else cs.onSurfaceVariant) }
                            if (!n.inReviewQueue) IconButton({ vm.review(n) }) { Icon(Icons.Filled.Replay, stringResource(R.string.add_to_review), tint = cs.onSurfaceVariant) }
                            IconButton({ vm.delete(n) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = cs.onSurfaceVariant) }
                        }
                    }
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
    val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.extended.card, focusedContainerColor = MaterialTheme.extended.card,
        unfocusedBorderColor = MaterialTheme.extended.cardBorder)
    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(if (noteId == null) R.string.add_note else R.string.edit_note), onDone)
        Column(Modifier.weight(1f).padding(horizontal = Gutter).verticalScroll(rememberScrollState())) {
            OutlinedTextField(quote, { quote = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.highlight)) }, shape = MaterialTheme.shapes.large,
                textStyle = QuoteStyle, colors = fieldColors)
            OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(top = 12.dp), label = { Text(stringResource(R.string.note_text)) },
                shape = MaterialTheme.shapes.large, colors = fieldColors)
            AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth().toggleable(review, role = Role.Switch) { review = it }, verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Replay, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 34.dp, iconSize = 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.add_to_review), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(review, null)
                }
            }
        }
        PrimaryButton(stringResource(R.string.save), { vm.save(noteId, bookId, chapter, content, quote, review, onDone) }, Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
            enabled = content.isNotBlank() || quote.isNotBlank())
    }
}
