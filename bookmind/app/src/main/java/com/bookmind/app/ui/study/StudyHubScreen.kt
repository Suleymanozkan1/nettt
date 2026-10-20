package com.bookmind.app.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookmind.app.R
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.data.repo.StudyModule
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.components.SecondaryButton
import com.bookmind.app.ui.theme.extended

enum class StudyTab { LEARN, PRACTICE, EXAM }

/** Card text and look of each module. */
private data class ModuleCard(val module: StudyModule?, val icon: ImageVector, val title: Int, val body: Int, val recommended: Boolean = false)

private val learnCards = listOf(
    ModuleCard(StudyModule.NOTES, Icons.AutoMirrored.Filled.MenuBook, R.string.study_notes, R.string.study_notes_body),
    ModuleCard(StudyModule.PODCAST, Icons.Filled.Headphones, R.string.study_podcast, R.string.study_podcast_body),
    ModuleCard(StudyModule.FLASHCARDS, Icons.Filled.Style, R.string.study_flashcards, R.string.study_flashcards_body, recommended = true),
    ModuleCard(StudyModule.GAPS, Icons.Filled.Extension, R.string.study_gaps, R.string.study_gaps_body),
)
private val practiceCards = listOf(
    ModuleCard(StudyModule.EXERCISES, Icons.Filled.EditNote, R.string.study_exercises, R.string.study_exercises_body),
    ModuleCard(StudyModule.MCQ, Icons.Filled.Quiz, R.string.study_mcq, R.string.study_mcq_body),
    ModuleCard(StudyModule.TRUE_FALSE, Icons.Filled.Rule, R.string.study_true_false, R.string.study_true_false_body),
    ModuleCard(null, Icons.Filled.Replay, R.string.study_review, R.string.study_review_body),
)

@Composable
fun StudyHubScreen(
    onBack: () -> Unit,
    onOpenModule: (lesson: Int, module: StudyModule) -> Unit,
    onOpenExam: (lesson: Int, module: StudyModule, count: Int, difficulty: Int) -> Unit,
    onReview: () -> Unit,
    onOpenAi: () -> Unit,
    vm: StudyHubViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(StudyTab.LEARN) }
    var setup by remember { mutableStateOf<StudyModule?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.study_title), onBack)
        if (state.loading) { LoadingState(); return@Column }
        val lesson = state.lesson ?: return@Column
        val listState = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().testTag("study_hub"), state = listState, contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 40.dp)) {
            item { LessonHero(lesson, state.lessons.size) }
            item {
                LazyRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.lessons, key = { it.index }) { l ->
                        ChoiceChip(
                            selected = l.index == state.selected,
                            label = stringResource(R.string.study_lesson_n, l.index + 1),
                            onClick = { vm.select(l.index) },
                            icon = if (l.completed) Icons.Filled.CheckCircle else null,
                        )
                    }
                }
            }
            item { AiStatusRow(ai, onOpenAi) }
            item { StudyTabs(tab) { tab = it } }
            when (tab) {
                StudyTab.LEARN -> item { CardGrid(learnCards, lesson.done) { onOpenModule(lesson.index, it) } }
                StudyTab.PRACTICE -> item {
                    CardGrid(practiceCards, lesson.done, dueForBook = state.dueForBook, onReview = onReview) { onOpenModule(lesson.index, it) }
                }
                StudyTab.EXAM -> {
                    item {
                        ExamCard(Icons.Filled.Draw, R.string.study_written, R.string.study_written_body, R.string.study_written_setup,
                            StudyModule.WRITTEN_EXAM in lesson.done) { setup = StudyModule.WRITTEN_EXAM }
                    }
                    item {
                        ExamCard(Icons.Filled.RecordVoiceOver, R.string.study_oral, R.string.study_oral_body, R.string.study_oral_setup,
                            StudyModule.ORAL_EXAM in lesson.done) { setup = StudyModule.ORAL_EXAM }
                    }
                }
            }
        }
    }

    setup?.let { module ->
        ExamSetupDialog(oral = module == StudyModule.ORAL_EXAM, onDismiss = { setup = null }) { count, difficulty ->
            setup = null
            onOpenExam(state.selected, module, count, difficulty)
        }
    }
}

@Composable
private fun LessonHero(lesson: com.bookmind.app.data.repo.StudyHubRepository.LessonInfo, total: Int) {
    val learnable = StudyModule.entries.size
    HeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.study_next_lesson).uppercase(AppLanguage.locale), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(6.dp))
            Text(lesson.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.study_lesson_of, lesson.index + 1, total), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            ProgressBar(lesson.done.size / learnable.toFloat(), color = Color.White, track = Color.White.copy(alpha = 0.25f), height = 8.dp)
            Text(stringResource(R.string.study_modules_done, lesson.done.size, learnable), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun AiStatusRow(ai: AiStatus, onOpenAi: () -> Unit) {
    val x = MaterialTheme.extended
    val cs = MaterialTheme.colorScheme
    when (ai) {
        AiStatus.READY -> Pill(stringResource(R.string.study_ai_ready), Modifier.padding(top = 14.dp), cs.onPrimaryContainer, cs.primaryContainer, Icons.Filled.AutoAwesome)
        AiStatus.OFF -> Pill(stringResource(R.string.study_ai_off), Modifier.padding(top = 14.dp), x.onInfoContainer, x.infoContainer, Icons.Filled.PhoneAndroid)
        AiStatus.NOT_INSTALLED -> AppCard(Modifier.fillMaxWidth().padding(top = 14.dp), onClick = onOpenAi, contentPadding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.AutoAwesome, cs.primary, cs.primaryContainer, size = 36.dp, iconSize = 18.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.study_ai_missing), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.study_ai_missing_body), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** ÖĞREN / PRATİK YAP / SINAV segmented control. */
@Composable
fun StudyTabs(selected: StudyTab, onSelect: (StudyTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp)) {
        listOf(StudyTab.LEARN to R.string.study_tab_learn, StudyTab.PRACTICE to R.string.study_tab_practice, StudyTab.EXAM to R.string.study_tab_exam).forEach { (t, label) ->
            val on = t == selected
            Box(
                Modifier.weight(1f).clip(CircleShape).background(if (on) MaterialTheme.extended.card else Color.Transparent)
                    .selectable(on, role = Role.Tab) { onSelect(t) }.heightIn(min = 44.dp).padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label).uppercase(AppLanguage.locale), style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) cs.primary else cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CardGrid(cards: List<ModuleCard>, done: Set<StudyModule>, dueForBook: Int = 0, onReview: () -> Unit = {}, onOpen: (StudyModule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { c ->
                    ModuleTile(c, c.module in done, if (c.module == null) dueForBook else null, Modifier.weight(1f).fillMaxHeight()) {
                        c.module?.let(onOpen) ?: onReview()
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModuleTile(card: ModuleCard, done: Boolean, due: Int?, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    AppCard(modifier.testTag("module_${card.module?.name ?: "REVIEW"}"), onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(card.icon, cs.primary, cs.primaryContainer, size = 40.dp, iconSize = 22.dp)
            Spacer(Modifier.weight(1f))
            when {
                done -> Icon(Icons.Filled.CheckCircle, stringResource(R.string.completed), tint = x.success, modifier = Modifier.size(22.dp))
                card.recommended -> Pill(stringResource(R.string.study_recommended).uppercase(AppLanguage.locale), color = x.onWarningContainer, container = x.warningContainer)
                due != null && due > 0 -> Pill(stringResource(R.string.study_due_n, due), color = x.onWarningContainer, container = x.warningContainer)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(card.title), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(card.body), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ExamCard(icon: ImageVector, title: Int, body: Int, action: Int, done: Boolean, onSetup: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AppCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, cs.primary, cs.primaryContainer, size = 44.dp, iconSize = 24.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            if (done) Icon(Icons.Filled.CheckCircle, stringResource(R.string.completed), tint = MaterialTheme.extended.success, modifier = Modifier.size(22.dp))
        }
        SecondaryButton(stringResource(action), onSetup, Modifier.fillMaxWidth().padding(top = 14.dp), icon = Icons.Filled.Tune)
    }
}

/** Number of questions and difficulty for a mock exam. */
@Composable
private fun ExamSetupDialog(oral: Boolean, onDismiss: () -> Unit, onStart: (count: Int, difficulty: Int) -> Unit) {
    val counts = if (oral) listOf(3, 5) else listOf(3, 5, 10)
    var count by remember { mutableIntStateOf(counts[1]) }
    var difficulty by remember { mutableIntStateOf(3) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (oral) R.string.study_oral_setup else R.string.study_written_setup)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.study_question_count), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    counts.forEach { n -> ChoiceChip(count == n, n.toString(), { count = n }) }
                }
                Text(stringResource(R.string.study_difficulty), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2 to R.string.study_easy, 3 to R.string.study_medium, 5 to R.string.study_hard).forEach { (d, label) ->
                        ChoiceChip(difficulty == d, stringResource(label), { difficulty = d })
                    }
                }
                if (oral) Text(stringResource(R.string.study_oral_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { PrimaryButton(stringResource(R.string.study_start_exam), { onStart(count, difficulty) }) },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
