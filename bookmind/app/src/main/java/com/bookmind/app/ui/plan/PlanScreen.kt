package com.bookmind.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.bookmind.app.data.db.PlanTaskEntity
import com.bookmind.app.data.repo.PlanRepository
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

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
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.plan_title), onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(stringResource(R.string.plan_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.daily_minutes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 20, 30).forEach { m -> FilterChip(minutes == m, { minutes = m }, { Text(stringResource(R.string.minutes_short, m)) }) }
                }
                Button({ vm.create(minutes) }, Modifier.padding(vertical = 8.dp)) { Text(stringResource(if (plan == null) R.string.create_plan else R.string.rebuild_plan)) }
                if (plan != null) {
                    val done = tasks.count { it.done }
                    Text(stringResource(R.string.plan_progress, done, tasks.size, tasks.lastOrNull()?.let { formatter.format(LocalDate.ofEpochDay(it.epochDay)) } ?: "—"))
                }
            }
            tasks.groupBy { it.epochDay }.forEach { (day, dayTasks) ->
                item(key = "d$day") { SectionTitle(formatter.format(LocalDate.ofEpochDay(day))) }
                items(dayTasks, key = { it.id }) { t ->
                    ListItem(
                        leadingContent = { Checkbox(t.done, { vm.toggle(t) }) },
                        headlineContent = { Text(t.title) },
                        supportingContent = { Text(stringResource(R.string.minutes_short, t.minutes)) },
                    )
                }
            }
        }
    }
}
