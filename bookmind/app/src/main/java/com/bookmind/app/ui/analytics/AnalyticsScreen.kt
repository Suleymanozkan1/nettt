package com.bookmind.app.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.repo.InsightsRepository
import com.bookmind.app.ui.components.BarChart
import com.bookmind.app.ui.components.CurveChart
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.SectionTitle
import com.bookmind.app.ui.components.StatCard
import com.bookmind.core.ai.RecallLevel
import com.bookmind.core.learning.LearningAnalytics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnalyticsViewModel @Inject constructor(private val insights: InsightsRepository) : ViewModel() {
    data class State(val analytics: InsightsRepository.Analytics, val curve: List<Pair<Int, Double>>, val stability: Double?)
    private val _state = MutableStateFlow<State?>(null)
    val state = _state.asStateFlow()
    fun refresh() = viewModelScope.launch {
        val s = insights.averageStability()
        _state.value = State(insights.analytics(), insights.curve(s ?: 3.0), s)
    }
}

@Composable
private fun rateText(r: LearningAnalytics.Rate): String = when {
    r.total == 0 -> "—"
    !r.enoughData -> stringResource(R.string.rate_few, r.correct, r.total)
    else -> "%${((r.value ?: 0.0) * 100).toInt()}"
}

@Composable
private fun levelName(l: RecallLevel) = stringResource(
    when (l) {
        RecallLevel.RECOGNITION -> R.string.recall_recognition
        RecallLevel.RECALL -> R.string.recall_recall
        RecallLevel.EXPLANATION -> R.string.recall_explanation
        RecallLevel.TRANSFER -> R.string.recall_transfer
        RecallLevel.APPLICATION -> R.string.recall_application
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(onOpenConnections: () -> Unit, vm: AnalyticsViewModel = hiltViewModel()) {
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.analytics_title)) },
            actions = { IconButton(onOpenConnections) { Icon(Icons.Filled.Hub, stringResource(R.string.connections_title)) } },
        )
        val s = state ?: run { LoadingState(); return@Column }
        val a = s.analytics
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(stringResource(R.string.due_today), a.dueToday.toString(), Modifier.weight(1f))
                StatCard(stringResource(R.string.overdue), a.overdue.toString(), Modifier.weight(1f))
                StatCard(stringResource(R.string.retained), a.retainedConcepts.toString(), Modifier.weight(1f), stringResource(R.string.retained_hint))
            }
            SectionTitle(stringResource(R.string.forecast))
            BarChart(a.forecast.map { (d, n) -> d.dayOfMonth.toString() to n }, stringResource(R.string.forecast_a11y, a.forecast.joinToString { "${it.first.dayOfMonth}: ${it.second}" }), highlightIndex = 0)

            SectionTitle(stringResource(R.string.measured_title))
            Text(stringResource(R.string.measured_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ListItem(headlineContent = { Text(stringResource(R.string.success_rate)) }, trailingContent = { Text(rateText(a.report.overall)) })
            ListItem(headlineContent = { Text(stringResource(R.string.first_attempt_rate)) }, trailingContent = { Text(rateText(a.report.firstAttempt)) })
            ListItem(headlineContent = { Text(stringResource(R.string.delayed_recall_rate)) }, trailingContent = { Text(rateText(a.report.delayedRecall)) })
            ListItem(headlineContent = { Text(stringResource(R.string.unaided_recall_rate)) }, trailingContent = { Text(rateText(a.report.unaidedRecall)) })
            RecallLevel.entries.forEach { l ->
                ListItem(headlineContent = { Text(levelName(l)) }, trailingContent = { Text(rateText(a.report.byLevel.getValue(l))) })
            }
            a.suggestion?.let { Text(stringResource(R.string.recall_gap_suggestion, levelName(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }

            SectionTitle(stringResource(R.string.predicted_title))
            Text(stringResource(R.string.predicted_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            a.predictedRetention?.let { Text(stringResource(R.string.predicted_retention, (it * 100).toInt(), a.studiedItems)) }
            CurveChart(s.curve, emptyList(), stringResource(R.string.curve_a11y, s.stability?.let { "%.1f".format(it) } ?: "—"))
            Text(stringResource(R.string.curve_legend), style = MaterialTheme.typography.labelSmall)

            a.report.selfReportedEasyShare?.let {
                SectionTitle(stringResource(R.string.self_reported_title))
                Text(stringResource(R.string.self_reported_easy, (it * 100).toInt()))
            }

            if (a.weakConcepts.isNotEmpty()) {
                SectionTitle(stringResource(R.string.home_weak))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { a.weakConcepts.forEach { AssistChip({}, { Text(it) }) } }
            }
            if (a.recallByBook.isNotEmpty()) {
                SectionTitle(stringResource(R.string.by_book))
                a.recallByBook.forEach { b ->
                    ListItem(headlineContent = { Text(b.title) }, trailingContent = { Text(if (b.reviews < LearningAnalytics.MIN_SAMPLE) "${b.correct}/${b.reviews}" else "%${b.correct * 100 / b.reviews}") })
                }
            }
            SectionTitle(stringResource(R.string.time_spent))
            BarChart(a.minutesByDay.takeLast(14).map { (d, m) -> d.dayOfMonth.toString() to m }, stringResource(R.string.time_spent_a11y, a.totalStudyMinutes))
            Text(stringResource(R.string.goal_days, a.goalDaysReached, a.goalDays), style = MaterialTheme.typography.bodyMedium)

            SectionTitle(stringResource(R.string.achievements))
            Text(stringResource(R.string.level_xp, a.level, a.xp) + " · " + stringResource(R.string.streak_longest, a.streak.current, a.streak.longest))
            if (a.streak.freezesLeft > 0) Text(stringResource(R.string.streak_freezes, a.streak.freezesLeft), style = MaterialTheme.typography.bodySmall)
            if (a.badges.isEmpty()) Text(stringResource(R.string.no_badges), style = MaterialTheme.typography.bodySmall)
            a.badges.forEach { ListItem(headlineContent = { Text(it.title) }, supportingContent = { Text(it.description) }) }
            Text(stringResource(R.string.analytics_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}
