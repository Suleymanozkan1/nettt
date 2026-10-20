package com.bookmind.app.ui.study

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookmind.app.R
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.audio.SpeechInput
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ErrorState
import com.bookmind.app.ui.components.GhostButton
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.OriginBadge
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.components.ProgressRing
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.components.Speaker
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.components.rememberSpeaker
import com.bookmind.app.ui.theme.extended
import com.bookmind.core.ai.study.ExamQuestion
import com.bookmind.core.ai.study.WrittenGrade

@Composable
private fun scoreColors(score: Int): Pair<Color, Color> {
    val x = MaterialTheme.extended
    val cs = MaterialTheme.colorScheme
    return when {
        score >= 70 -> x.onSuccessContainer to x.successContainer
        score >= 45 -> x.onWarningContainer to x.warningContainer
        else -> cs.onErrorContainer to cs.errorContainer
    }
}

@Composable
private fun gradeLabel(score: Int): String = stringResource(
    when {
        score >= 85 -> R.string.study_grade_excellent
        score >= 70 -> R.string.study_grade_good
        score >= 50 -> R.string.study_grade_fair
        else -> R.string.study_grade_weak
    },
)

/** Big score ring on the brand gradient, used by both exam reports. */
@Composable
private fun ScoreHero(score: Int, title: String, subtitle: String) {
    HeroCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(score / 100f, size = 104.dp, color = Color.White, track = Color.White.copy(alpha = 0.25f)) {
                Text("$score", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(AppLanguage.locale), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                Text(gradeLabel(score), style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(top = 2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---------- Yazılı deneme sınavı ----------

@Composable
fun WrittenExamScreen(onBack: () -> Unit, onReview: () -> Unit, vm: WrittenExamViewModel = hiltViewModel()) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val answers by vm.answers.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(R.string.study_written), onBack)
        when (val p = phase) {
            WrittenExamViewModel.Phase.Preparing -> LoadingState(message = stringResource(R.string.study_exam_preparing))
            is WrittenExamViewModel.Phase.Error -> ErrorState(p.message, onRetry = { vm.prepare() })
            is WrittenExamViewModel.Phase.Grading -> Column(Modifier.fillMaxWidth().padding(Gutter)) {
                LoadingState(message = stringResource(R.string.study_grading, p.done + 1, p.total))
                ProgressBar(p.done / p.total.toFloat())
            }
            is WrittenExamViewModel.Phase.Answering -> LazyColumn(Modifier.fillMaxSize().testTag("written_exam"), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 40.dp)) {
                item {
                    OriginBadge(p.info)
                    Text(stringResource(R.string.study_exam_intro, p.questions.size), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    if (p.warnings.isNotEmpty()) WarningBanner(p.warnings, Modifier.padding(top = 10.dp))
                }
                itemsIndexed(p.questions) { i, q ->
                    AppCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        Text(stringResource(R.string.study_question_label, i + 1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(q.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                        OutlinedTextField(
                            answers.getOrElse(i) { "" }, { vm.answer(i, it) },
                            Modifier.fillMaxWidth().padding(top = 12.dp).testTag("answer_$i"),
                            placeholder = { Text(stringResource(R.string.answer_in_own_words)) },
                            minLines = 3, shape = MaterialTheme.shapes.medium,
                        )
                    }
                }
                item {
                    Text(stringResource(R.string.study_blank_zero), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp))
                    PrimaryButton(stringResource(R.string.study_submit_exam), { vm.submit() }, Modifier.fillMaxWidth().padding(top = 10.dp),
                        enabled = answers.any { it.isNotBlank() })
                }
            }
            is WrittenExamViewModel.Phase.Report -> WrittenReport(p, onRestart = { vm.prepare() }, onReview = onReview)
        }
    }
}

@Composable
private fun WrittenReport(r: WrittenExamViewModel.Phase.Report, onRestart: () -> Unit, onReview: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("written_report"), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 40.dp)) {
        item {
            ScoreHero(r.score, stringResource(R.string.study_report_card),
                stringResource(if (r.aiGraded) R.string.study_graded_ai else R.string.study_graded_local))
            SectionHeader(stringResource(R.string.study_per_question))
        }
        itemsIndexed(r.questions) { i, q -> GradeCard(i, q, r.answers.getOrElse(i) { "" }, r.grades[i]) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(stringResource(R.string.study_new_exam), onRestart, Modifier.weight(1f), icon = Icons.Filled.Refresh)
                if (r.grades.any { it.score < 60 }) PrimaryButton(stringResource(R.string.study_go_review), onReview, Modifier.weight(1f), icon = Icons.Filled.Replay)
            }
        }
    }
}

@Composable
private fun GradeCard(index: Int, q: ExamQuestion, answer: String, g: WrittenGrade) {
    var open by rememberSaveable { mutableStateOf(index == 0) }
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    val score = g.score.toInt()
    val (fg, bg) = scoreColors(score)
    AppCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.study_question_label, index + 1), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.weight(1f))
            Pill("$score / 100", color = fg, container = bg)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        }
        Text(q.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        if (open) {
            Label(R.string.study_your_answer)
            Text(answer.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            if (g.feedback.isNotBlank()) {
                Label(R.string.study_feedback)
                Text(g.feedback, style = MaterialTheme.typography.bodyMedium)
            }
            if (g.missing.isNotEmpty()) {
                Label(R.string.study_missing_points)
                g.missing.forEach { m ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(x.warning))
                        Spacer(Modifier.width(10.dp))
                        Text(m, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(MaterialTheme.shapes.medium).background(x.successContainer).padding(12.dp)) {
                Text(stringResource(R.string.study_ideal_answer), style = MaterialTheme.typography.labelMedium, color = x.onSuccessContainer)
                Text(g.idealAnswer.ifBlank { q.idealAnswer }, style = MaterialTheme.typography.bodyMedium, color = x.onSuccessContainer, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun Label(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

// ---------- Sözlü deneme sınavı ----------

@Composable
fun OralExamScreen(onBack: () -> Unit, onReview: () -> Unit, vm: OralExamViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val toSpeak by vm.toSpeak.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val speaker = rememberSpeaker()
    val speech = remember { SpeechInput(context) }
    DisposableEffect(speech) { onDispose { speech.release() } }
    var micAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var listenAfterSpeech by remember { mutableStateOf(false) }
    // Id of the examiner line spoken last, so a recomposition or rotation never repeats a line.
    var spokenId by rememberSaveable { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micAllowed = it }
    val canListen = micAllowed && speech.state != SpeechInput.State.UNAVAILABLE

    fun listen() {
        if (!canListen || state.thinking || state.finished) return
        speaker.stop()
        speech.start { heard -> if (heard.isNotBlank()) { vm.answer(heard); draft = "" } }
    }

    // Ask for the microphone once, when the exam starts; typing works without it.
    LaunchedEffect(state.preparing) {
        if (!state.preparing && !micAllowed && !asked && speech.state != SpeechInput.State.UNAVAILABLE) { asked = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    // The examiner speaks each new line; when it has finished, the microphone opens for the answer.
    LaunchedEffect(toSpeak, speaker.state) {
        val line = toSpeak ?: return@LaunchedEffect
        if (speaker.state == Speaker.State.LOADING) return@LaunchedEffect
        if (line.first != spokenId) {
            spokenId = line.first
            speech.cancel()
            if (speaker.state == Speaker.State.UNAVAILABLE) { if (!state.finished) listen() } else { speaker.speak(line.second); listenAfterSpeech = !state.finished }
        } else if (listenAfterSpeech && speaker.state == Speaker.State.READY) {
            listenAfterSpeech = false
            listen()
        }
    }
    LaunchedEffect(speech.text) { if (speech.state == SpeechInput.State.LISTENING && speech.text.isNotBlank()) draft = speech.text }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar(stringResource(R.string.study_oral), onBack)
        when {
            state.preparing -> LoadingState(message = stringResource(R.string.study_exam_preparing))
            state.error != null -> ErrorState(state.error!!, onRetry = { vm.prepare() })
            state.finished -> OralReport(state, onRestart = { vm.prepare() }, onReview = onReview)
            else -> {
                val list = rememberLazyListState()
                LaunchedEffect(state.messages.size, state.thinking) { list.animateScrollToItem((state.messages.size + if (state.thinking) 1 else 0).coerceAtLeast(0)) }
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.study_question_n, state.index + 1, state.questions.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    OriginBadge(state.info)
                }
                ProgressBar(state.index / state.questions.size.coerceAtLeast(1).toFloat(), Modifier.padding(horizontal = Gutter, vertical = 8.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("oral_chat"), state = list, contentPadding = PaddingValues(horizontal = Gutter, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        val note = when {
                            speech.state == SpeechInput.State.UNAVAILABLE -> R.string.study_stt_missing
                            !micAllowed -> R.string.study_mic_denied
                            !speech.onDevice -> R.string.study_stt_service
                            else -> null
                        }
                        note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    itemsIndexed(state.messages) { _, m -> Bubble(m) }
                    if (state.thinking) item { Bubble(OralExamViewModel.Message(true, stringResource(R.string.study_examiner_thinking)), faded = true) }
                }
                OralInput(
                    draft = draft, onDraft = { draft = it },
                    listening = speech.state == SpeechInput.State.LISTENING || speech.state == SpeechInput.State.PROCESSING,
                    level = speech.level, canListen = canListen, busy = state.thinking,
                    onMic = { if (speech.state == SpeechInput.State.LISTENING) speech.stop() else if (canListen) listen() else permission.launch(Manifest.permission.RECORD_AUDIO) },
                    onSend = { speech.cancel(); vm.answer(draft); draft = "" },
                    onSkip = { speech.cancel(); vm.skip(context.getString(R.string.study_skipped)) },
                )
            }
        }
    }
}

@Composable
private fun Bubble(m: OralExamViewModel.Message, faded: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromExaminer) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (m.fromExaminer) 4.dp else 18.dp, bottomEnd = if (m.fromExaminer) 18.dp else 4.dp))
                .background(if (m.fromExaminer) x.card else cs.primary).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (m.fromExaminer) Text(stringResource(R.string.study_examiner), style = MaterialTheme.typography.labelSmall, color = cs.primary)
            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = when {
                faded -> cs.onSurfaceVariant
                m.fromExaminer -> cs.onSurface
                else -> cs.onPrimary
            })
            m.score?.let { s -> val (fg, bg) = scoreColors(s); Pill("$s / 100", Modifier.padding(top = 6.dp), fg, bg) }
        }
    }
}

@Composable
private fun OralInput(
    draft: String, onDraft: (String) -> Unit, listening: Boolean, level: Float, canListen: Boolean, busy: Boolean,
    onMic: () -> Unit, onSend: () -> Unit, onSkip: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pulse by animateFloatAsState(if (listening) 1f + level * 0.35f else 1f, label = "mic")
    Column(Modifier.fillMaxWidth().background(MaterialTheme.extended.card).padding(horizontal = Gutter, vertical = 12.dp)) {
        if (listening) Text(stringResource(R.string.study_listening), style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.padding(bottom = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                if (listening) Box(Modifier.size(56.dp).scale(pulse).clip(CircleShape).background(cs.primary.copy(alpha = 0.18f)))
                FilledIconButton(onMic, Modifier.size(52.dp).testTag("mic"), enabled = !busy,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (listening) cs.error else cs.primary)) {
                    Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, stringResource(if (listening) R.string.study_stop_listening else R.string.study_speak))
                }
            }
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(draft, onDraft, Modifier.weight(1f).testTag("oral_answer"), placeholder = { Text(stringResource(if (canListen) R.string.study_speak_or_type else R.string.study_type_answer)) },
                maxLines = 4, shape = MaterialTheme.shapes.medium, enabled = !busy)
            IconButton(onSend, enabled = draft.isNotBlank() && !busy) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send)) }
        }
        Text(stringResource(R.string.study_skip_question), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp).clip(CircleShape).clickable(enabled = !busy, onClick = onSkip).padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

@Composable
private fun OralReport(state: OralExamViewModel.State, onRestart: () -> Unit, onReview: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("oral_report"), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 40.dp)) {
        item {
            ScoreHero(state.score, stringResource(R.string.study_oral_score), stringResource(R.string.study_oral_answered, state.results.size))
            SectionHeader(stringResource(R.string.study_per_question))
        }
        itemsIndexed(state.results) { i, r ->
            val score = r.score.toInt()
            val (fg, bg) = scoreColors(score)
            AppCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.study_question_label, i + 1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Pill("$score / 100", color = fg, container = bg)
                }
                Text(r.question.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                Text(r.feedback, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.study_ideal_answer) + ": " + r.question.idealAnswer, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(stringResource(R.string.study_new_exam), onRestart, Modifier.weight(1f), icon = Icons.Filled.Refresh)
                if (state.results.any { it.score < 60 }) PrimaryButton(stringResource(R.string.study_go_review), onReview, Modifier.weight(1f), icon = Icons.Filled.Replay)
            }
            Text(stringResource(R.string.study_skip_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}
