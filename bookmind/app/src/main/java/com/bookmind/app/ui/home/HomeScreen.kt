package com.bookmind.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.AttemptSummary
import com.bookmind.app.data.db.PlanTaskEntity
import com.bookmind.app.data.repo.InsightsRepository
import com.bookmind.app.data.repo.PlanRepository
import com.bookmind.app.data.repo.QuizRepository
import com.bookmind.app.ui.components.BarChart
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.GoalRing
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val insights: InsightsRepository,
    private val plans: PlanRepository,
    quizzes: QuizRepository,
) : ViewModel() {
    private val _home = MutableStateFlow<InsightsRepository.Home?>(null)
    val home = _home.asStateFlow()
    val recentQuizzes = quizzes.recent(3).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<AttemptSummary>())
    val todayTasks = plans.today().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PlanTaskEntity>())

    fun refresh() = viewModelScope.launch { _home.value = insights.home() }
    fun toggleTask(t: PlanTaskEntity) = viewModelScope.launch { plans.setDone(t, !t.done); refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartReview: () -> Unit,
    onOpenBook: (String) -> Unit,
    onAddBook: () -> Unit,
    onOpenConcept: (String) -> Unit,
    onOpenQuizResult: (String) -> Unit,
    onOpenPlan: (String) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val home by vm.home.collectAsStateWithLifecycle()
    val quizzes by vm.recentQuizzes.collectAsStateWithLifecycle()
    val tasks by vm.todayTasks.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.home_title)) })
        val h = home ?: run { LoadingState(); return@Column }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                // The single most important action of the day comes first.
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        GoalRing(h.goal.fraction, stringResource(R.string.home_goal_a11y, h.goal.studiedMinutes, h.goal.goalMinutes))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.home_goal, h.goal.studiedMinutes, h.goal.goalMinutes), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (h.dueToday > 0) stringResource(R.string.home_due, h.dueToday) else stringResource(R.string.home_nothing_due),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (h.overdue > 0) Text(stringResource(R.string.home_overdue, h.overdue), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.height(8.dp))
                            if (h.dueToday > 0 || h.newAvailable > 0) Button(onStartReview) {
                                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.home_start_reviews))
                            }
                        }
                    }
                    Text(stringResource(R.string.home_suggested, h.suggestedMinutes), Modifier.padding(start = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.home_streak, h.streak.current), Modifier.padding(start = 4.dp).weight(1f))
                    Text(stringResource(R.string.level_xp, h.level, h.xp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (tasks.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_plan_today)) }
                items(tasks, key = { it.id }) { t ->
                    ListItem(
                        headlineContent = { Text(t.title) },
                        supportingContent = { Text(stringResource(R.string.minutes_short, t.minutes)) },
                        leadingContent = { Checkbox(t.done, { vm.toggleTask(t) }) },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.home_continue)) }
            if (h.continueBooks.isEmpty()) item {
                EmptyState(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body), stringResource(R.string.add_book), onAddBook)
            }
            items(h.continueBooks, key = { it.id }) { b ->
                ListItem(
                    headlineContent = { Text(b.title) },
                    supportingContent = { Text(b.author ?: stringResource(R.string.unknown_author)) },
                    trailingContent = { OutlinedButton({ onOpenPlan(b.id) }) { Text(stringResource(R.string.plan)) } },
                    modifier = Modifier.clickable { onOpenBook(b.id) },
                )
            }
            if (h.weakConcepts.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_weak)) }
                item { Text(h.weakConcepts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium) }
            }
            if (h.recentConcepts.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_recent_concepts)) }
                items(h.recentConcepts, key = { "c" + it.id }) { c ->
                    ListItem(headlineContent = { Text(c.title) }, supportingContent = { Text(c.explanation, maxLines = 2) }, modifier = Modifier.clickable { onOpenConcept(c.id) })
                }
            }
            if (quizzes.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_recent_quizzes)) }
                items(quizzes, key = { it.attemptId }) { q ->
                    ListItem(headlineContent = { Text(q.title) }, trailingContent = { Text("%${(q.score * 100).toInt()}") }, modifier = Modifier.clickable { onOpenQuizResult(q.attemptId) })
                }
            }
            item {
                SectionTitle(stringResource(R.string.home_week))
                val labels = h.weekMinutes.map { (d, m) -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) to m }
                BarChart(labels, stringResource(R.string.home_week_a11y, h.weekMinutes.joinToString { "${it.second}" }), highlightIndex = labels.lastIndex)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    LaunchedEffect(Unit) { vm.refresh() }
}
