package com.bookmind.app.ui.book

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.files.ImportException
import com.bookmind.app.data.repo.DuplicateBookException
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.ErrorState
import com.bookmind.app.ui.components.GhostButton
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.ListRow
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.theme.extended
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AddState {
    data object Idle : AddState
    data object Working : AddState
    data class Done(val bookId: String) : AddState
    data class Error(val messageRes: Int, val existingBookId: String? = null) : AddState
}

@HiltViewModel
class AddBookViewModel @Inject constructor(private val library: LibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow<AddState>(AddState.Idle)
    val state = _state.asStateFlow()

    private fun run(block: suspend () -> String) = viewModelScope.launch {
        _state.value = AddState.Working
        _state.value = try {
            AddState.Done(block())
        } catch (e: DuplicateBookException) {
            AddState.Error(R.string.import_duplicate, e.existingBookId)
        } catch (e: ImportException) {
            AddState.Error(
                when (e.reason) {
                    ImportException.Reason.TOO_LARGE -> R.string.import_too_large
                    ImportException.Reason.EMPTY -> R.string.import_empty
                    ImportException.Reason.UNSUPPORTED -> R.string.import_unsupported
                    ImportException.Reason.DRM -> R.string.import_drm
                    ImportException.Reason.NO_TEXT -> R.string.import_no_text
                    else -> R.string.import_unreadable
                },
            )
        } catch (e: Exception) {
            AddState.Error(R.string.import_unreadable)
        }
    }

    fun importFile(uri: Uri) = run { library.importFile(uri) }
    fun importText(title: String, author: String, text: String) = run { library.importText(title, author, text, markdown = text.trimStart().startsWith("#")) }
    fun demo() = run { library.addDemoBook() }
    fun reset() { _state.value = AddState.Idle }
}

@Composable
fun AddBookScreen(onBack: () -> Unit, onAdded: (String) -> Unit, vm: AddBookViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Read access is used once to copy the file into app storage; no persistent permission is kept.
        if (uri != null) vm.importFile(uri)
    }
    (state as? AddState.Done)?.let { done -> LaunchedEffect(done) { onAdded(done.bookId) } }
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    val working = state is AddState.Working
    val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = x.card, focusedContainerColor = x.card, unfocusedBorderColor = x.cardBorder)

    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(R.string.add_book), onBack)
        ContentWidth(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter)) {
                when (val s = state) {
                    AddState.Working -> LoadingState(message = stringResource(R.string.importing))
                    is AddState.Error -> {
                        ErrorState(stringResource(s.messageRes), onRetry = { vm.reset() })
                        s.existingBookId?.let { id -> GhostButton(stringResource(R.string.open_existing), { onAdded(id) }, Modifier.fillMaxWidth()) }
                    }
                    else -> Unit
                }
                // Primary path: a file.
                HeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(22.dp)) {
                        Icon(Icons.Filled.UploadFile, null, Modifier.size(36.dp))
                        Text(stringResource(R.string.add_from_file), style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(top = 10.dp))
                        Text(stringResource(R.string.add_from_file_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("PDF", "EPUB", "TXT", "MD").forEach { Pill(it, color = Color.White, container = Color.White.copy(alpha = 0.18f)) }
                        }
                        Spacer(Modifier.height(16.dp))
                        com.bookmind.app.ui.components.HeroButton(stringResource(R.string.choose_file), {
                            if (!working) picker.launch(arrayOf("application/pdf", "application/epub+zip", "text/plain", "text/markdown", "text/x-markdown", "application/octet-stream"))
                        }, icon = Icons.Filled.FileOpen)
                    }
                }
                Spacer(Modifier.height(12.dp))
                AppCard(Modifier.fillMaxWidth()) {
                    ListRow(stringResource(R.string.add_manual), subtitle = stringResource(R.string.add_manual_hint), icon = Icons.Filled.EditNote,
                        iconTint = x.streak, iconContainer = x.warningContainer, onClick = { manual = !manual }) {
                        Icon(if (manual) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = cs.onSurfaceVariant)
                    }
                    if (manual) {
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.book_title)) }, singleLine = true, shape = MaterialTheme.shapes.medium, colors = fieldColors)
                            OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.book_author)) }, singleLine = true, shape = MaterialTheme.shapes.medium, colors = fieldColors)
                            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp), label = { Text(stringResource(R.string.book_text)) }, shape = MaterialTheme.shapes.medium, colors = fieldColors)
                            if (text.isNotEmpty() && text.length < 200) Text(stringResource(R.string.text_too_short), style = MaterialTheme.typography.bodySmall, color = cs.error)
                            PrimaryButton(stringResource(R.string.save), { vm.importText(title, author, text) }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && text.length >= 200 && !working)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                AppCard(Modifier.fillMaxWidth()) {
                    ListRow(stringResource(R.string.add_demo), subtitle = stringResource(R.string.onb_add_demo_hint), icon = Icons.Filled.School,
                        iconTint = x.success, iconContainer = x.successContainer, onClick = { if (!working) vm.demo() })
                }
                Text(stringResource(R.string.copyright_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
            }
        }
    }
}
