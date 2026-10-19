package com.bookmind.app.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.LinkRow
import com.bookmind.app.data.repo.GraphRepository
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.WarningBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ConnectionsViewModel @Inject constructor(private val graph: GraphRepository) : ViewModel() {
    val links = graph.links().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings = _warnings.asStateFlow()
    fun rebuild() = viewModelScope.launch {
        _busy.value = true
        _warnings.value = runCatching { graph.rebuild().warnings }.getOrElse { listOf(it.message ?: "error") }
        _busy.value = false
    }
}

@Composable
private fun relationLabel(r: String) = when (r) {
    "same_concept" -> stringResource(R.string.rel_same)
    "supports" -> stringResource(R.string.rel_supports)
    "contradicts" -> stringResource(R.string.rel_contradicts)
    "extends" -> stringResource(R.string.rel_extends)
    "applies" -> stringResource(R.string.rel_applies)
    "example_of" -> stringResource(R.string.rel_example)
    else -> stringResource(R.string.rel_related)
}

@Composable
fun ConnectionsScreen(onBack: () -> Unit, onOpenConcept: (String) -> Unit, vm: ConnectionsViewModel = hiltViewModel()) {
    val links by vm.links.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val warnings by vm.warnings.collectAsStateWithLifecycle()
    var compare by remember { mutableStateOf<LinkRow?>(null) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.connections_title), onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 40.dp)) {
            item {
                HeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Icon(Icons.Filled.Hub, null, Modifier.size(32.dp))
                        Text(stringResource(R.string.connections_hint), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 10.dp))
                        Spacer(Modifier.height(14.dp))
                        com.bookmind.app.ui.components.HeroButton(stringResource(R.string.find_connections), vm::rebuild, icon = Icons.Filled.AutoAwesome)
                    }
                }
                if (busy) LoadingState()
                WarningBanner(warnings, Modifier.padding(top = 8.dp))
            }
            if (links.isEmpty() && !busy) item { EmptyState(stringResource(R.string.connections_empty_title), stringResource(R.string.connections_empty_body), icon = Icons.Filled.Hub) }
            items(links, key = { it.id }) { l ->
                AppCard(Modifier.fillMaxWidth().padding(top = 10.dp), onClick = { compare = l }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.fromTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2)
                        Icon(Icons.Filled.SyncAlt, null, tint = cs.primary, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
                        Text(l.toTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, textAlign = TextAlign.End)
                    }
                    Text("${l.fromBookTitle} · ${l.toBookTitle}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(relationLabel(l.relation))
                        Pill(stringResource(R.string.confidence_value, (l.confidence * 100).toInt()), color = cs.onSurfaceVariant, container = cs.surfaceContainerHigh)
                        Pill(stringResource(if (l.aiDerived) R.string.ai_derived else R.string.locally_derived), color = cs.onSurfaceVariant, container = cs.surfaceContainerHigh)
                    }
                    if (l.explanation.isNotBlank()) Text(l.explanation, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    compare?.let { l ->
        // Side by side, each in its own book's words; differences are shown, not resolved.
        AlertDialog(
            onDismissRequest = { compare = null },
            title = { Text(stringResource(R.string.compare_title)) },
            text = {
                Column {
                    Text("${l.fromBookTitle}: ${l.fromTitle}", style = MaterialTheme.typography.titleSmall)
                    Text(l.fromExplanation, style = MaterialTheme.typography.bodySmall)
                    Text("${l.toBookTitle}: ${l.toTitle}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Text(l.toExplanation, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.compare_disclaimer), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton({ onOpenConcept(l.fromConceptId); compare = null }) { Text(l.fromTitle) } },
            dismissButton = { TextButton({ onOpenConcept(l.toConceptId); compare = null }) { Text(l.toTitle) } },
        )
    }
}
