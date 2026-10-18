package com.bookmind.app.ui.book

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ErrorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Read access is used once to copy the file into app storage; no persistent permission is kept.
        if (uri != null) vm.importFile(uri)
    }
    (state as? AddState.Done)?.let { done -> androidx.compose.runtime.LaunchedEffect(done) { onAdded(done.bookId) } }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.add_book), onBack)
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            when (val s = state) {
                AddState.Working -> { CircularProgressIndicator(); Text(stringResource(R.string.importing)) }
                is AddState.Error -> {
                    ErrorState(stringResource(s.messageRes), onRetry = { vm.reset() })
                    s.existingBookId?.let { id -> OutlinedButton({ onAdded(id) }) { Text(stringResource(R.string.open_existing)) } }
                }
                else -> Unit
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.add_from_file), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.add_from_file_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf", "application/epub+zip", "text/plain", "text/markdown", "text/x-markdown", "application/octet-stream")) },
                        enabled = state !is AddState.Working,
                    ) { Icon(Icons.Filled.FileOpen, null); Text(" " + stringResource(R.string.choose_file)) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.add_manual), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.add_manual_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.book_title)) }, singleLine = true)
                    OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.book_author)) }, singleLine = true)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text(stringResource(R.string.book_text)) })
                    Spacer(Modifier.height(8.dp))
                    Button({ vm.importText(title, author, text) }, enabled = title.isNotBlank() && text.length >= 200 && state !is AddState.Working) { Text(stringResource(R.string.save)) }
                    if (text.isNotEmpty() && text.length < 200) Text(stringResource(R.string.text_too_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            OutlinedButton({ vm.demo() }, Modifier.padding(top = 16.dp), enabled = state !is AddState.Working) {
                Icon(Icons.Filled.School, null); Text(" " + stringResource(R.string.add_demo))
            }
            Text(stringResource(R.string.copyright_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
