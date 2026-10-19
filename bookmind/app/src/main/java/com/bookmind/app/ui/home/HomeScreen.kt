package com.bookmind.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.AttemptSummary
import com.bookmind.app.data.db.BookEntity
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.PlanTaskEntity
import com.bookmind.app.data.repo.InsightsRepository
import com.bookmind.app.data.repo.PlanRepository
import com.bookmind.app.data.repo.QuizRepository
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BarChart
import com.bookmind.app.ui.components.BookCover
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroButton
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.PageHeader
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.ProgressRing
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.components.StatTile
import com.bookmind.app.ui.theme.extended
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val insights: InsightsRepository,
    private val plans: PlanRepository,
    quizzes: QuizRepository,
    db: BookMindDatabase,
    models: com.bookmind.app.ai.ModelManager,
    private val settings: com.bookmind.app.data.settings.SettingsRepository,
) : ViewModel() {
    val ai = models.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val aiPromptDismissed = settings.settings.map { it.aiPromptDismissed }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun dismissAiPrompt() = viewModelScope.launch { settings.update { it.copy(aiPromptDismissed = true) } }

    private val _home = MutableStateFlow<InsightsRepository.Home?>(null)
    val home = _home.asStateFlow()

    init {
        // Recompute whenever reviews, books or sessions change (e.g. a book finishes processing in the
        // background), so the counts on this screen never go stale.
        viewModelScope.launch {
            db.invalidationTracker.createFlow("review_states", "learning_items", "books", "study_sessions", "concepts", "xp_events")
                .conflate().collect { _home.value = insights.home() }
        }
    }
    val recentQuizzes = quizzes.recent(3).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<AttemptSummary>())
    val todayTasks = plans.today().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PlanTaskEntity>())

    fun refresh() = viewModelScope.launch { _home.value = insights.home() }
    fun toggleTask(t: PlanTaskEntity) = viewModelScope.launch { plans.setDone(t, !t.done); refresh() }
}

@Composable
private fun greeting(): String {
    val h = LocalDateTime.now().hour
    return stringResource(
        when (h) {
            in 5..11 -> R.string.greet_morning
            in 12..17 -> R.string.greet_afternoon
            in 18..22 -> R.string.greet_evening
            else -> R.string.greet_night
        },
    )
}

@Composable
fun HomeScreen(
    onStartReview: () -> Unit,
    onOpenBook: (String) -> Unit,
    onAddBook: () -> Unit,
    onOpenConcept: (String) -> Unit,
    onOpenQuizResult: (String) -> Unit,
    onOpenPlan: (String) -> Unit,
    onOpenAi: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val home by vm.home.collectAsStateWithLifecycle()
    val quizzes by vm.recentQuizzes.collectAsStateWithLifecycle()
    val tasks by vm.todayTasks.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val aiDismissed by vm.aiPromptDismissed.collectAsStateWithLifecycle()
    val today = DateTimeFormatter.ofPattern("d MMMM, EEEE", AppLanguage.locale).format(LocalDateTime.now())
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended

    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.home_title), eyebrow = "$today · ${greeting()}")
        val h = home ?: run { LoadingState(); return@Column }
        val goalA11y = stringResource(R.string.home_goal_a11y, h.goal.studiedMinutes, h.goal.goalMinutes)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 8.dp, bottom = 32.dp)) {
            item {
                // The single most important action of the day comes first.
                HeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressRing(
                                h.goal.fraction, size = 88.dp, stroke = 9.dp, color = Color.White, track = Color.White.copy(alpha = 0.22f),
                                modifier = Modifier.semantics { contentDescription = goalA11y },
                            ) {
                                Text("${(h.goal.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            }
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.home_goal_label), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.78f))
                                Text(
                                    stringResource(R.string.home_goal, h.goal.studiedMinutes, h.goal.goalMinutes),
                                    style = MaterialTheme.typography.headlineSmall, color = Color.White,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    when {
                                        h.dueToday > 0 -> stringResource(R.string.home_due, h.dueToday)
                                        h.newAvailable > 0 -> stringResource(R.string.home_new_ready, h.newAvailable)
                                        else -> stringResource(R.string.home_nothing_due)
                                    },
                                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f),
                                )
                            }
                        }
                        if (h.overdue > 0) {
                            Spacer(Modifier.height(12.dp))
                            Pill(stringResource(R.string.home_overdue, h.overdue), color = Color.White, container = Color.White.copy(alpha = 0.18f))
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (h.dueToday > 0 || h.newAvailable > 0) {
                                HeroButton(stringResource(R.string.home_start_reviews), onStartReview, icon = Icons.Filled.PlayArrow)
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(stringResource(R.string.home_suggested, h.suggestedMinutes), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.8f))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Icons.Filled.LocalFireDepartment, h.streak.current.toString(), stringResource(R.string.stat_streak), x.streak, x.warningContainer, Modifier.weight(1f))
                    StatTile(Icons.Filled.EmojiEvents, stringResource(R.string.level_short, h.level), stringResource(R.string.xp_value, h.xp), cs.primary, cs.primaryContainer, Modifier.weight(1f))
                    StatTile(Icons.Filled.Style, (h.dueToday + h.newAvailable).toString(), stringResource(R.string.stat_due), x.success, x.successContainer, Modifier.weight(1f))
                }
            }
            ai?.let { a ->
                // Offer the free on-device AI until it is installed; keep showing progress while it downloads.
                if (a.download != null || (!a.ready && a.selected != null && !aiDismissed)) {
                    item { AiPromoCard(a, onOpenAi, onDismiss = vm::dismissAiPrompt) }
                }
            }
            if (tasks.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_plan_today)) }
                item {
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                        tasks.forEach { t -> TaskRow(t) { vm.toggleTask(t) } }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.home_continue), actionLabel = stringResource(R.string.add_book), onAction = onAddBook) }
            if (h.continueBooks.isEmpty()) item {
                AppCard(Modifier.fillMaxWidth()) {
                    EmptyState(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body), actionLabel = stringResource(R.string.add_book), onAction = onAddBook)
                }
            } else items(h.continueBooks.take(3), key = { it.id }) { b ->
                ContinueBook(b, { onOpenBook(b.id) }, { onOpenPlan(b.id) })
            }
            if (h.weakConcepts.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_weak)) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        h.weakConcepts.forEach { Pill(it, color = x.onWarningContainer, container = x.warningContainer) }
                    }
                }
            }
            if (h.recentConcepts.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_recent_concepts)) }
                items(h.recentConcepts, key = { "c" + it.id }) { c ->
                    AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = { onOpenConcept(c.id) }) {
                        Row(verticalAlignment = Alignment.Top) {
                            IconBadge(Icons.Filled.Lightbulb, x.streak, x.warningContainer, size = 36.dp, iconSize = 20.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.titleMedium)
                                Text(c.explanation, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (quizzes.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_recent_quizzes)) }
                item {
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        quizzes.forEach { q ->
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onOpenQuizResult(q.attemptId) }.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Filled.Quiz, cs.primary, cs.primaryContainer, size = 34.dp, iconSize = 18.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(q.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val pct = (q.score * 100).toInt()
                                Pill("%$pct", color = if (pct >= 60) x.onSuccessContainer else x.onWarningContainer, container = if (pct >= 60) x.successContainer else x.warningContainer)
                            }
                        }
                    }
                }
            }
            item {
                SectionHeader(stringResource(R.string.home_week))
                AppCard(Modifier.fillMaxWidth()) {
                    val labels = h.weekMinutes.map { (d, m) -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, AppLanguage.locale) to m }
                    Text(stringResource(R.string.minutes_total, h.weekMinutes.sumOf { it.second }), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(14.dp))
                    BarChart(labels, stringResource(R.string.home_week_a11y, h.weekMinutes.joinToString { "${it.second}" }), highlightIndex = labels.lastIndex)
                }
            }
        }
    }
    LaunchedEffect(Unit) { vm.refresh() }
}

@Composable
private fun TaskRow(t: PlanTaskEntity, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).toggleable(t.done, role = Role.Checkbox) { onToggle() }.padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (t.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (t.done) MaterialTheme.extended.success else cs.outline, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textDecoration = if (t.done) TextDecoration.LineThrough else null,
            color = if (t.done) cs.onSurfaceVariant else cs.onSurface)
        Text(stringResource(R.string.minutes_short, t.minutes), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ContinueBook(b: BookEntity, onOpen: () -> Unit, onPlan: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = onOpen, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover(b.title, b.author, b.id, Modifier.width(64.dp).clip(MaterialTheme.shapes.small))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                b.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (b.chapterCount > 0) Pill(pluralStringResource(R.plurals.chapters_n, b.chapterCount, b.chapterCount), color = cs.onSurfaceVariant, container = cs.surfaceContainerHigh)
                    Row(
                        Modifier.clip(CircleShape).background(cs.primaryContainer).clickable(onClick = onPlan).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.ViewTimeline, null, Modifier.size(14.dp), tint = cs.onPrimaryContainer)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.plan), style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer)
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun AiPromoCard(a: com.bookmind.app.ai.AiState, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    AppCard(Modifier.fillMaxWidth().padding(top = 14.dp), onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            com.bookmind.app.ui.components.IconBadge(Icons.Filled.AutoAwesome, cs.onPrimary, cs.primary, size = 44.dp, iconSize = 24.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_promo_title), style = MaterialTheme.typography.titleMedium)
                val running = a.download as? com.bookmind.app.ai.AiState.Download.Running
                if (a.download != null) {
                    Text(com.bookmind.app.ui.ai.aiStatusText(a), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    com.bookmind.app.ui.components.ProgressBar(running?.fraction ?: 0f, Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.ai_promo_body), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            if (a.download == null) {
                IconButton(onDismiss, Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.ai_not_now), Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                }
            }
        }
        if (a.download == null) {
            Spacer(Modifier.height(12.dp))
            com.bookmind.app.ui.components.PrimaryButton(
                stringResource(R.string.ai_promo_action) + " · " + android.text.format.Formatter.formatShortFileSize(context, a.selected!!.bytes),
                onOpen, Modifier.fillMaxWidth(), icon = Icons.Filled.AutoAwesome,
            )
        }
    }
}
