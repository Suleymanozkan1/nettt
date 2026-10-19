package com.bookmind.app.ui.concept

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.ConceptEntity
import com.bookmind.app.data.db.StoryEntity
import com.bookmind.app.data.repo.StudyRepository
import com.bookmind.app.processing.BookProcessor
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.ErrorState
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.OriginBadge
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.components.SourceChip
import com.bookmind.app.ui.theme.QuoteStyle
import com.bookmind.app.ui.theme.ReadingStyle
import com.bookmind.app.ui.theme.extended
import com.bookmind.app.util.AppJson
import com.bookmind.app.util.parseInfo
import com.bookmind.app.util.parseRefs
import com.bookmind.core.text.SourceRef
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ConceptViewModel @Inject constructor(handle: SavedStateHandle, private val study: StudyRepository) : ViewModel() {
    private val conceptId: String = handle["conceptId"]!!
    private val _concept = MutableStateFlow<ConceptEntity?>(null)
    val concept = _concept.asStateFlow()
    val stories = study.stories(conceptId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init { reload() }
    private fun reload() = viewModelScope.launch { _concept.value = study.concept(conceptId) }

    fun favorite() = viewModelScope.launch { _concept.value?.let { study.toggleFavorite(it) }; reload() }
    fun edit(title: String, explanation: String) = viewModelScope.launch { _concept.value?.let { study.editConcept(it, title, explanation) }; reload() }
    fun story(kind: String) = viewModelScope.launch {
        val c = _concept.value ?: return@launch
        _busy.value = true; _error.value = null
        runCatching { study.generateStory(c.bookId, c.id, kind) }.onFailure { _error.value = it.message }
        _busy.value = false
    }
}

@Composable
fun ConceptScreen(conceptId: String, onBack: () -> Unit, onTeach: (String, String) -> Unit, onOpenChapter: (String) -> Unit, vm: ConceptViewModel = hiltViewModel()) {
    val concept by vm.concept.collectAsStateWithLifecycle()
    val stories by vm.stories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    val c = concept ?: run { LoadingState(); return }
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Column(Modifier.fillMaxSize()) {
        BackTopBar("", onBack) {
            IconButton(vm::favorite) {
                Icon(if (c.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(R.string.favorite), tint = if (c.isFavorite) cs.primary else cs.onSurface)
            }
            IconButton({ editing = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
        }
        ContentWidth(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter)) {
                IconBadge(Icons.Filled.Lightbulb, x.streak, x.warningContainer, size = 48.dp, iconSize = 26.dp)
                Text(c.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OriginBadge(if (c.userEdited) null else parseInfo(c.generatedBy))
                    Pill(stringResource(R.string.importance_difficulty, c.importance, c.difficulty), color = cs.onSurfaceVariant, container = cs.surfaceContainerHigh)
                }
                Text(c.explanation, style = ReadingStyle, modifier = Modifier.padding(top = 16.dp))
                parseRefs(c.sourceReferences).forEach { ref ->
                    AppCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        ref.quote?.let { Text("“$it”", style = QuoteStyle) }
                        SourceChip(ref) { onOpenChapter(BookProcessor.chapterId(c.bookId, ref.chapterIndex)) }
                    }
                }
                PrimaryButton(stringResource(R.string.teach_me), { onTeach(c.bookId, c.id) }, Modifier.fillMaxWidth().padding(top = 20.dp), icon = Icons.Filled.School)

                SectionHeader(stringResource(R.string.stories_title))
                Text(stringResource(R.string.stories_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("short story" to R.string.story_short, "daily life scenario" to R.string.story_daily, "business example" to R.string.story_business,
                        "dialogue" to R.string.story_dialogue, "analogy" to R.string.story_analogy, "problem-solving scenario" to R.string.story_problem)
                        .forEach { (kind, label) -> ChoiceChip(false, stringResource(label), { vm.story(kind) }, enabled = !busy) }
                }
                if (busy) LoadingState()
                error?.let { ErrorState(it) }
                stories.forEach { StoryCard(it) { idx -> onOpenChapter(BookProcessor.chapterId(c.bookId, idx)) } }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (editing) {
        var title by remember { mutableStateOf(c.title) }
        var text by remember { mutableStateOf(c.explanation) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, singleLine = true, shape = MaterialTheme.shapes.medium)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                }
            },
            confirmButton = { TextButton({ editing = false; vm.edit(title, text) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StoryCard(story: StoryEntity, openChapter: (Int) -> Unit) {
    val x = MaterialTheme.extended
    AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(story.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(stringResource(if (story.fromBook) R.string.story_from_book else R.string.story_generated),
                color = if (story.fromBook) x.onSuccessContainer else x.onWarningContainer, container = if (story.fromBook) x.successContainer else x.warningContainer)
            OriginBadge(parseInfo(story.generatedBy))
        }
        Text(story.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
        story.sourceReference?.let { json -> runCatching { AppJson.decodeFromString(SourceRef.serializer(), json) }.getOrNull() }?.let { ref ->
            SourceChip(ref) { openChapter(ref.chapterIndex) }
        }
    }
}
