package com.bookmind.app.ui.analytics

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.repo.InsightsRepository
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BarChart
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.CurveChart
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeaderAction
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.PageHeader
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.components.StatTile
import com.bookmind.app.ui.theme.extended
import com.bookmind.core.ai.RecallLevel
import com.bookmind.core.learning.LearningAnalytics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

@Composable
private fun RateRow(label: String, r: LearningAnalytics.Rate) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(rateText(r), style = MaterialTheme.typography.labelLarge, color = if (r.enoughData) cs.primary else cs.onSurfaceVariant)
        }
        if (r.enoughData && r.value != null) ProgressBar(r.value!!.toFloat(), Modifier.padding(top = 6.dp), height = 5.dp)
    }
}

private val badgeText = mapOf(
    "first_review" to (R.string.badge_first_review to R.string.badge_first_review_desc),
    "streak_7" to (R.string.badge_streak_7 to R.string.badge_streak_7_desc),
    "streak_30" to (R.string.badge_streak_30 to R.string.badge_streak_30_desc),
    "reviews_100" to (R.string.badge_reviews_100 to R.string.badge_reviews_100_desc),
    "book_1" to (R.string.badge_book_1 to R.string.badge_book_1_desc),
    "retained_10" to (R.string.badge_retained_10 to R.string.badge_retained_10_desc),
    "explainer_10" to (R.string.badge_explainer_10 to R.string.badge_explainer_10_desc),
    "applier_5" to (R.string.badge_applier_5 to R.string.badge_applier_5_desc),
    "note_taker" to (R.string.badge_note_taker to R.string.badge_note_taker_desc),
)

@Composable
fun AnalyticsScreen(onOpenConnections: () -> Unit, vm: AnalyticsViewModel = hiltViewModel()) {
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val state by vm.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.analytics_title)) {
            HeaderAction(Icons.Filled.Hub, stringResource(R.string.connections_title), onOpenConnections)
        }
        val s = state ?: run { LoadingState(); return@Column }
        val a = s.analytics
        ContentWidth(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Icons.Filled.Style, a.dueToday.toString(), stringResource(R.string.due_today), cs.primary, cs.primaryContainer, Modifier.weight(1f))
                    StatTile(Icons.Filled.WarningAmber, a.overdue.toString(), stringResource(R.string.overdue), x.warning, x.warningContainer, Modifier.weight(1f))
                    StatTile(Icons.Filled.Psychology, a.retainedConcepts.toString(), stringResource(R.string.retained), x.success, x.successContainer, Modifier.weight(1f))
                }
                Text(stringResource(R.string.retained_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp))

                SectionHeader(stringResource(R.string.forecast))
                AppCard(Modifier.fillMaxWidth()) {
                    BarChart(a.forecast.map { (d, n) -> d.dayOfMonth.toString() to n }, stringResource(R.string.forecast_a11y, a.forecast.joinToString { "${it.first.dayOfMonth}: ${it.second}" }), highlightIndex = 0)
                }

                SectionHeader(stringResource(R.string.measured_title))
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.measured_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                    RateRow(stringResource(R.string.success_rate), a.report.overall)
                    RateRow(stringResource(R.string.first_attempt_rate), a.report.firstAttempt)
                    RateRow(stringResource(R.string.delayed_recall_rate), a.report.delayedRecall)
                    RateRow(stringResource(R.string.unaided_recall_rate), a.report.unaidedRecall)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = x.cardBorder)
                    RecallLevel.entries.forEach { l -> RateRow(levelName(l), a.report.byLevel.getValue(l)) }
                    a.suggestion?.let {
                        Text(stringResource(R.string.recall_gap_suggestion, levelName(it)), style = MaterialTheme.typography.bodyMedium, color = x.onWarningContainer,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }

                SectionHeader(stringResource(R.string.predicted_title))
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.predicted_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    a.predictedRetention?.let { Text(stringResource(R.string.predicted_retention, (it * 100).toInt(), a.studiedItems), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(12.dp))
                    CurveChart(s.curve, emptyList(), stringResource(R.string.curve_a11y, s.stability?.let { "%.1f".format(it) } ?: "—"))
                    Text(stringResource(R.string.curve_legend), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }

                a.report.selfReportedEasyShare?.let {
                    SectionHeader(stringResource(R.string.self_reported_title))
                    AppCard(Modifier.fillMaxWidth()) { Text(stringResource(R.string.self_reported_easy, (it * 100).toInt()), style = MaterialTheme.typography.bodyMedium) }
                }

                if (a.weakConcepts.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.home_weak))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.weakConcepts.forEach { Pill(it, color = x.onWarningContainer, container = x.warningContainer) }
                    }
                }
                if (a.recallByBook.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.by_book))
                    AppCard(Modifier.fillMaxWidth()) {
                        a.recallByBook.forEach { b ->
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(b.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (b.reviews < LearningAnalytics.MIN_SAMPLE) "${b.correct}/${b.reviews}" else "%${b.correct * 100 / b.reviews}", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                            }
                        }
                    }
                }
                SectionHeader(stringResource(R.string.time_spent))
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.Schedule, cs.primary, cs.primaryContainer, size = 34.dp, iconSize = 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.goal_days, a.goalDaysReached, a.goalDays), style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(14.dp))
                    BarChart(a.minutesByDay.takeLast(14).map { (d, m) -> d.dayOfMonth.toString() to m }, stringResource(R.string.time_spent_a11y, a.totalStudyMinutes),
                        highlightIndex = a.minutesByDay.takeLast(14).lastIndex)
                }

                SectionHeader(stringResource(R.string.achievements))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Icons.Filled.LocalFireDepartment, a.streak.current.toString(), stringResource(R.string.streak_longest_short, a.streak.longest), x.streak, x.warningContainer, Modifier.weight(1f))
                    StatTile(Icons.Filled.EmojiEvents, stringResource(R.string.level_short, a.level), stringResource(R.string.xp_value, a.xp), cs.primary, cs.primaryContainer, Modifier.weight(1f))
                    StatTile(Icons.Filled.AcUnit, a.streak.freezesLeft.toString(), stringResource(R.string.freezes_short), x.info, x.infoContainer, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                val earned = a.badges.map { it.id }.toSet()
                badgeText.entries.chunked(3).forEach { row ->
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (id, text) ->
                            val got = id in earned
                            AppCard(Modifier.weight(1f).height(128.dp), color = if (got) x.warningContainer else x.card, contentPadding = PaddingValues(10.dp)) {
                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    IconBadge(if (got) Icons.Filled.EmojiEvents else Icons.Filled.Lock, if (got) x.streak else cs.outline,
                                        if (got) x.card else cs.surfaceContainerHigh, size = 40.dp)
                                    Text(stringResource(text.first), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp), color = if (got) x.onWarningContainer else cs.onSurface)
                                    Text(stringResource(text.second), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 3,
                                        overflow = TextOverflow.Ellipsis, color = if (got) x.onWarningContainer else cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.analytics_disclaimer), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
