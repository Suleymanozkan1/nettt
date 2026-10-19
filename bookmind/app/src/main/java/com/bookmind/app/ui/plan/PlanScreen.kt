package com.bookmind.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.PlanTaskEntity
import com.bookmind.app.data.repo.PlanRepository
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.theme.extended
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanViewModel @Inject constructor(handle: SavedStateHandle, private val plans: PlanRepository) : ViewModel() {
    private val bookId: String = handle["bookId"]!!
    val plan = plans.plan(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val tasks = plans.plan(bookId).flatMapLatest { p -> p?.let { plans.tasks(it.id) } ?: emptyFlow() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    init { viewModelScope.launch { plans.refresh(bookId) } }
    fun create(minutes: Int) = viewModelScope.launch { plans.create(bookId, minutes) }
    fun toggle(t: PlanTaskEntity) = viewModelScope.launch { plans.setDone(t, !t.done) }
}

@Composable
fun PlanScreen(bookId: String, onBack: () -> Unit, vm: PlanViewModel = hiltViewModel()) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var minutes by remember { mutableIntStateOf(15) }
    LaunchedEffect(plan?.dailyMinutes) { plan?.let { minutes = it.dailyMinutes } }
    val formatter = remember { DateTimeFormatter.ofPattern("d MMMM, EEEE", com.bookmind.app.data.settings.AppLanguage.locale) }
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.plan_title), onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 40.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.daily_minutes), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.plan_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 20, 30).forEach { m -> ChoiceChip(minutes == m, stringResource(R.string.minutes_short, m), { minutes = m }) }
                    }
                    PrimaryButton(stringResource(if (plan == null) R.string.create_plan else R.string.rebuild_plan), { vm.create(minutes) }, Modifier.fillMaxWidth().padding(top = 14.dp),
                        icon = Icons.Filled.ViewTimeline)
                }
                if (plan != null) {
                    val done = tasks.count { it.done }
                    Column(Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.plan_progress, done, tasks.size, tasks.lastOrNull()?.let { formatter.format(LocalDate.ofEpochDay(it.epochDay)) } ?: "—"),
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        com.bookmind.app.ui.components.ProgressBar(if (tasks.isEmpty()) 0f else done.toFloat() / tasks.size, Modifier.padding(top = 8.dp), color = x.success)
                    }
                }
            }
            tasks.groupBy { it.epochDay }.forEach { (day, dayTasks) ->
                item(key = "d$day") { SectionHeader(formatter.format(LocalDate.ofEpochDay(day))) }
                item(key = "c$day") {
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        dayTasks.forEach { t ->
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).toggleable(t.done, role = Role.Checkbox) { vm.toggle(t) }.padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (t.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (t.done) x.success else cs.outline)
                                Spacer(Modifier.width(12.dp))
                                Text(t.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = if (t.done) cs.onSurfaceVariant else cs.onSurface)
                                Text(stringResource(R.string.minutes_short, t.minutes), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
