package com.bookmind.app.ui.summary

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.SummaryEntity
import com.bookmind.app.data.repo.GenerationException
import com.bookmind.app.data.repo.StudyRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.processing.BookProcessor
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ErrorState
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.OriginBadge
import com.bookmind.app.ui.components.SectionTitle
import com.bookmind.app.ui.components.SourceChip
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.util.AppJson
import com.bookmind.app.util.parseInfo
import com.bookmind.app.util.parseStringList
import com.bookmind.core.ai.SummaryDocument
import com.bookmind.core.ai.SummaryLength
import com.bookmind.core.ai.SummaryType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SummaryViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val study: StudyRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val bookId: String = handle["bookId"]!!
    val type: SummaryType = SummaryType.valueOf(handle["type"]!!)
    val chapterId: String? = handle["chapterId"]
    val length = MutableStateFlow(SummaryLength.MEDIUM)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val summary = length.flatMapLatest { study.summary(bookId, type, it, chapterId) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { length.value = runCatching { SummaryLength.valueOf(settings.current().summaryLength) }.getOrDefault(SummaryLength.MEDIUM) }
    }

    fun generate() = viewModelScope.launch {
        _busy.value = true; _error.value = null
        try { study.generateSummary(bookId, type, length.value, chapterId) } catch (e: GenerationException) { _error.value = e.message } catch (e: Exception) { _error.value = e.message ?: "error" }
        _busy.value = false
    }

    fun saveEdit(s: SummaryEntity, text: String) = viewModelScope.launch { study.editSummary(s.id, text) }
}

@Composable
fun summaryTitle(type: SummaryType) = stringResource(
    when (type) {
        SummaryType.QUICK -> R.string.summary_quick
        SummaryType.DETAILED -> R.string.summary_detailed
        SummaryType.EXECUTIVE -> R.string.summary_executive
        SummaryType.CONCEPT_MAP -> R.string.summary_concept_map
        SummaryType.KEY_IDEAS -> R.string.summary_key_ideas
        SummaryType.CHAPTER_COMPARISON -> R.string.summary_comparison
        SummaryType.CHAPTER -> R.string.summary_chapter
    },
)

@Composable
fun SummaryScreen(bookId: String, type: String, chapterId: String?, onBack: () -> Unit, onOpenChapter: (String) -> Unit, vm: SummaryViewModel = hiltViewModel()) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val length by vm.length.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    val doc = remember(summary?.content) { summary?.content?.let { runCatching { AppJson.decodeFromString(SummaryDocument.serializer(), it) }.getOrNull() } }
    val openRef = { index: Int -> onOpenChapter(BookProcessor.chapterId(bookId, index)) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(summaryTitle(vm.type), onBack) {
            if (summary != null) {
                IconButton({ editing = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                IconButton(vm::generate, enabled = !busy) { Icon(Icons.Filled.Refresh, stringResource(R.string.regenerate)) }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SummaryLength.SHORT to R.string.length_short, SummaryLength.MEDIUM to R.string.length_medium, SummaryLength.LONG to R.string.length_long)
                    .forEach { (l, label) -> FilterChip(length == l, { vm.length.value = l }, { Text(stringResource(label)) }) }
            }
            error?.let { ErrorState(it, onRetry = vm::generate) }
            when {
                busy -> LoadingState(message = stringResource(R.string.generating))
                summary == null -> Column(Modifier.padding(vertical = 24.dp)) {
                    Text(stringResource(R.string.summary_not_generated))
                    Spacer(Modifier.height(12.dp))
                    Button(vm::generate) { Text(stringResource(R.string.generate)) }
                }
                else -> {
                    val s = summary!!
                    Row { OriginBadge(parseInfo(s.modelMetadata)) }
                    WarningBanner(parseStringList(s.warnings))
                    s.userEditedText?.let { edited ->
                        SectionTitle(stringResource(R.string.your_version))
                        SelectionContainer { Text(edited, style = MaterialTheme.typography.bodyLarge) }
                        SectionTitle(stringResource(R.string.generated_version))
                    }
                    doc?.let { RenderDocument(it, openRef) } ?: Text(stringResource(R.string.summary_unreadable))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (editing && summary != null) {
        var text by remember { mutableStateOf(summary!!.userEditedText ?: doc?.plainText().orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.edit_summary)) },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp)) },
            confirmButton = { TextButton({ editing = false; vm.saveEdit(summary!!, text) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RenderDocument(doc: SummaryDocument, openRef: (Int) -> Unit) {
    if (doc.title.isNotBlank()) Text(doc.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
    doc.sections.forEach { s ->
        SectionTitle(s.heading)
        if (s.body.isNotBlank()) SelectionContainer { Text(s.body, style = MaterialTheme.typography.bodyLarge) }
        s.bullets.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp, top = 2.dp)) }
        if (s.sources.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { s.sources.forEach { r -> SourceChip(r) { openRef(r.chapterIndex) } } }
    }
    doc.ideas.forEach { idea ->
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(idea.idea, style = MaterialTheme.typography.titleMedium)
                listOf(
                    R.string.idea_explanation to idea.explanation, R.string.idea_why to idea.whyItMatters, R.string.idea_support to idea.support,
                    R.string.idea_example to idea.example, R.string.idea_daily to idea.dailyUse, R.string.idea_tip to idea.memoryTip,
                ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
                idea.source?.let { SourceChip(it) { openRef(it.chapterIndex) } }
            }
        }
    }
    doc.conceptMap?.let { map ->
        // Rendered as an accessible outline: parents with their child concepts, then the relations.
        val roots = map.nodes.filter { n -> n.parent == null || map.nodes.none { it.id == n.parent } }
        roots.forEach { root ->
            Text(root.label + if (root.prerequisite) " ★" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
            map.nodes.filter { it.parent == root.id }.forEach { child ->
                Text("   └ " + child.label + if (child.prerequisite) " ★" else "", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (map.edges.isNotEmpty()) {
            SectionTitle(stringResource(R.string.relations))
            val label = map.nodes.associate { it.id to it.label }
            map.edges.forEach { e ->
                Text("${label[e.from]} → ${label[e.to]}: ${e.relation}" + if (e.causal) " (${stringResource(R.string.causal)})" else "", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.prerequisite_legend), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
    }
    doc.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
}
