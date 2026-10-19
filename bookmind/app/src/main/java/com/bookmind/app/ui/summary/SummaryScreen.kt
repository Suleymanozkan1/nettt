package com.bookmind.app.ui.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.ErrorState
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.OriginBadge
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.SourceChip
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.theme.QuoteStyle
import com.bookmind.app.ui.theme.ReadingStyle
import com.bookmind.app.ui.theme.extended
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
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        BackTopBar(summaryTitle(vm.type), onBack) {
            if (summary != null) {
                IconButton({ editing = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                IconButton(vm::generate, enabled = !busy) { Icon(Icons.Filled.Refresh, stringResource(R.string.regenerate)) }
            }
        }
        ContentWidth(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter)) {
                // Length selector as a segmented control.
                Row(Modifier.fillMaxWidth().clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
                    listOf(SummaryLength.SHORT to R.string.length_short, SummaryLength.MEDIUM to R.string.length_medium, SummaryLength.LONG to R.string.length_long)
                        .forEach { (l, label) ->
                            val sel = length == l
                            Box(
                                Modifier.weight(1f).clip(CircleShape).background(if (sel) MaterialTheme.extended.card else Color.Transparent)
                                    .selectable(sel, role = Role.Tab) { vm.length.value = l }.padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = if (sel) cs.onSurface else cs.onSurfaceVariant) }
                        }
                }
                Spacer(Modifier.height(12.dp))
                error?.let { ErrorState(it, onRetry = vm::generate) }
                when {
                    busy -> LoadingState(message = stringResource(R.string.generating))
                    summary == null -> HeroCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(40.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(summaryTitle(vm.type), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.summary_not_generated), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                                color = Color.White.copy(alpha = 0.85f))
                            Spacer(Modifier.height(18.dp))
                            com.bookmind.app.ui.components.HeroButton(stringResource(R.string.generate), vm::generate, icon = Icons.Filled.AutoAwesome)
                        }
                    }
                    else -> {
                        val s = summary!!
                        Row(Modifier.padding(bottom = 4.dp)) { OriginBadge(parseInfo(s.modelMetadata)) }
                        WarningBanner(parseStringList(s.warnings))
                        s.userEditedText?.let { edited ->
                            Heading(stringResource(R.string.your_version))
                            SelectionContainer { Text(edited, style = ReadingStyle) }
                            Heading(stringResource(R.string.generated_version))
                        }
                        doc?.let { RenderDocument(it, openRef) } ?: Text(stringResource(R.string.summary_unreadable))
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (editing && summary != null) {
        var text by remember { mutableStateOf(summary!!.userEditedText ?: doc?.plainText().orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.edit_summary)) },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp), shape = MaterialTheme.shapes.medium) },
            confirmButton = { TextButton({ editing = false; vm.saveEdit(summary!!, text) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
}

@Composable
private fun Bullet(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.padding(top = 10.dp).size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(text, style = ReadingStyle)
    }
}

@Composable
private fun RenderDocument(doc: SummaryDocument, openRef: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (doc.title.isNotBlank()) Text(doc.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    doc.sections.forEachIndexed { i, s ->
        if (i == 0 && s.body.isNotBlank() && s.bullets.isEmpty()) {
            // The first section with a body (the key idea) is set as a highlighted lead.
            AppCard(Modifier.fillMaxWidth().padding(top = 14.dp), color = cs.primaryContainer, border = false) {
                Text(s.heading, style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                Spacer(Modifier.height(6.dp))
                SelectionContainer { Text(s.body, style = ReadingStyle, color = cs.onPrimaryContainer) }
                if (s.sources.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { s.sources.forEach { r -> SourceChip(r) { openRef(r.chapterIndex) } } }
            }
            return@forEachIndexed
        }
        Heading(s.heading)
        if (s.body.isNotBlank()) SelectionContainer { Text(s.body, style = ReadingStyle) }
        s.bullets.forEach { Bullet(it) }
        if (s.sources.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { s.sources.forEach { r -> SourceChip(r) { openRef(r.chapterIndex) } } }
    }
    doc.ideas.forEachIndexed { n, idea ->
        AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                    Text("${n + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Text(idea.idea, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            listOf(
                R.string.idea_explanation to idea.explanation, R.string.idea_why to idea.whyItMatters, R.string.idea_support to idea.support,
                R.string.idea_example to idea.example, R.string.idea_daily to idea.dailyUse, R.string.idea_tip to idea.memoryTip,
            ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                Text(stringResource(label).uppercase(com.bookmind.app.data.settings.AppLanguage.locale), style = MaterialTheme.typography.labelSmall, color = cs.primary,
                    modifier = Modifier.padding(top = 12.dp))
                Text(value, style = if (label == R.string.idea_support) QuoteStyle else MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            }
            idea.source?.let { SourceChip(it) { openRef(it.chapterIndex) } }
        }
    }
    doc.conceptMap?.let { map ->
        // Rendered as an accessible outline: parents with their child concepts, then the relations.
        val roots = map.nodes.filter { n -> n.parent == null || map.nodes.none { it.id == n.parent } }
        roots.forEach { root ->
            AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(root.label + if (root.prerequisite) " ★" else "", style = MaterialTheme.typography.titleMedium)
                val children = map.nodes.filter { it.parent == root.id }
                if (children.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    children.forEach { child -> com.bookmind.app.ui.components.Pill(child.label + if (child.prerequisite) " ★" else "") }
                }
            }
        }
        if (map.edges.isNotEmpty()) {
            Heading(stringResource(R.string.relations))
            val label = map.nodes.associate { it.id to it.label }
            AppCard(Modifier.fillMaxWidth()) {
                map.edges.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.extended.cardBorder)
                    Text("${label[e.from]}  →  ${label[e.to]}", style = MaterialTheme.typography.titleSmall)
                    Text(e.relation + if (e.causal) " (${stringResource(R.string.causal)})" else "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        Text(stringResource(R.string.prerequisite_legend), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
    if (doc.notes.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 12.dp), color = MaterialTheme.extended.cardBorder)
        doc.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp)) }
    }
}
