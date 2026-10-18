package com.bookmind.app.ui.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.LinkRow
import com.bookmind.app.data.repo.GraphRepository
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.WarningBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.connections_title), onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.connections_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(vertical = 8.dp)) {
                Button(vm::rebuild, enabled = !busy) { Text(stringResource(R.string.find_connections)) }
                if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator() }
            }
            WarningBanner(warnings)
        }
        if (links.isEmpty() && !busy) EmptyState(stringResource(R.string.connections_empty_title), stringResource(R.string.connections_empty_body))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(links, key = { it.id }) { l ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { compare = l }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${l.fromTitle}  ⟷  ${l.toTitle}", style = MaterialTheme.typography.titleSmall)
                        Text("${l.fromBookTitle} · ${l.toBookTitle}", style = MaterialTheme.typography.labelSmall)
                        Text(relationLabel(l.relation) + " · " + stringResource(R.string.confidence_value, (l.confidence * 100).toInt()) + " · " +
                            stringResource(if (l.aiDerived) R.string.ai_derived else R.string.locally_derived), style = MaterialTheme.typography.labelMedium)
                        if (l.explanation.isNotBlank()) Text(l.explanation, style = MaterialTheme.typography.bodySmall)
                    }
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
                    Spacer(Modifier.width(8.dp))
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
