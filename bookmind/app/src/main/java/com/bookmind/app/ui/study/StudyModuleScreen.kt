package com.bookmind.app.ui.study

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookmind.app.R
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.audio.PodcastPlayer
import com.bookmind.app.data.repo.StudyModule
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.EmptyState
import com.bookmind.app.ui.components.ErrorState
import com.bookmind.app.ui.components.GhostButton
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.ListenButton
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.OriginBadge
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.components.ProgressRing
import com.bookmind.app.ui.components.SecondaryButton
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.components.rememberSpeaker
import com.bookmind.app.ui.theme.QuoteStyle
import com.bookmind.app.ui.theme.extended
import com.bookmind.core.ai.study.ClozeItem
import com.bookmind.core.ai.study.ExerciseSet
import com.bookmind.core.ai.study.Flashcard
import com.bookmind.core.ai.study.FlashcardDeck
import com.bookmind.core.ai.study.GapDeck
import com.bookmind.core.ai.study.McqQuestion
import com.bookmind.core.ai.study.McqSet
import com.bookmind.core.ai.study.PodcastScript
import com.bookmind.core.ai.study.SmartNotes
import com.bookmind.core.ai.study.TrueFalseItem
import com.bookmind.core.ai.study.TrueFalseSet
import kotlin.math.abs
import kotlin.math.roundToInt

fun moduleTitle(module: StudyModule): Int = when (module) {
    StudyModule.NOTES -> R.string.study_notes
    StudyModule.PODCAST -> R.string.study_podcast
    StudyModule.FLASHCARDS -> R.string.study_flashcards
    StudyModule.GAPS -> R.string.study_gaps
    StudyModule.EXERCISES -> R.string.study_exercises
    StudyModule.MCQ -> R.string.study_mcq
    StudyModule.TRUE_FALSE -> R.string.study_true_false
    StudyModule.WRITTEN_EXAM -> R.string.study_written
    StudyModule.ORAL_EXAM -> R.string.study_oral
}

@Composable
fun StudyModuleScreen(onBack: () -> Unit, onReview: () -> Unit, vm: StudyModuleViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()
    val notes = state.content?.value as? SmartNotes
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(moduleTitle(vm.module)), onBack) {
            if (notes != null) ListenButton(speaker) { notesText(notes) }
        }
        val content = state.content
        when {
            state.error != null -> ErrorState(state.error!!, onRetry = { vm.load() })
            content == null -> LoadingState(message = stringResource(R.string.study_preparing, state.lesson?.title.orEmpty()))
            else -> {
                val header: @Composable ColumnScope.() -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.lesson?.title.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        OriginBadge(content.info)
                    }
                    if (content.warnings.isNotEmpty()) WarningBanner(content.warnings, Modifier.padding(top = 10.dp))
                }
                when (val v = content.value) {
                    is SmartNotes -> NotesView(v, header)
                    is PodcastScript -> PodcastView(v, header, onFinished = vm::done)
                    is FlashcardDeck -> FlashcardsView(v, header, onAgain = vm::flashcardAgain, onFinished = vm::done, onReview = onReview)
                    is GapDeck -> GapsView(v, state.fromMistakes, header)
                    is ExerciseSet -> ExercisesView(v, header, onWrongCloze = vm::wrongCloze, onWrongMatch = vm::wrongMatch, onFinished = vm::done)
                    is McqSet -> McqView(v, header, onWrong = vm::wrongMcq, onFinished = vm::done, onReview = onReview)
                    is TrueFalseSet -> TrueFalseView(v, header, onWrong = vm::wrongTrueFalse, onFinished = vm::done, onReview = onReview)
                }
            }
        }
    }
}

private fun notesText(n: SmartNotes) = buildString {
    append(n.title).append(". ").append(n.mainIdea).append(' ')
    n.concepts.forEach { append(it.term).append(": ").append(it.explanation).append(' ') }
    n.keyPoints.forEach { append(it).append(' ') }
    append(n.takeaway)
}

@Composable
private fun ScrollPage(header: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = Gutter, end = Gutter, bottom = 40.dp)) {
        header()
        content()
    }
}

// ---------- Akıllı metin ----------

@Composable
private fun NotesView(n: SmartNotes, header: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    ScrollPage(header) {
        if (n.mainIdea.isNotBlank()) {
            HeroCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.study_main_idea).uppercase(AppLanguage.locale), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                    Text(n.mainIdea, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (n.concepts.isNotEmpty()) {
            SectionHeader(stringResource(R.string.study_key_concepts))
            n.concepts.forEach { c ->
                AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Text(c.term, style = MaterialTheme.typography.titleSmall, color = cs.primary)
                    Text(c.explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (n.keyPoints.isNotEmpty()) {
            SectionHeader(stringResource(R.string.study_key_points))
            AppCard(Modifier.fillMaxWidth()) {
                n.keyPoints.forEachIndexed { i, p ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(cs.primary))
                        Spacer(Modifier.width(12.dp))
                        Text(p, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (i < n.keyPoints.lastIndex) Spacer(Modifier.height(2.dp))
                }
            }
        }
        if (n.quotes.isNotEmpty()) {
            SectionHeader(stringResource(R.string.study_quotes))
            n.quotes.forEach { q ->
                Row(Modifier.padding(bottom = 12.dp)) {
                    Box(Modifier.width(3.dp).height(44.dp).clip(CircleShape).background(cs.primary))
                    Spacer(Modifier.width(12.dp))
                    Text("“$q”", style = QuoteStyle, color = cs.onSurfaceVariant)
                }
            }
        }
        if (n.takeaway.isNotBlank()) {
            AppCard(Modifier.fillMaxWidth().padding(top = 12.dp), color = x.successContainer, border = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lightbulb, null, tint = x.success)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.study_takeaway), style = MaterialTheme.typography.labelLarge, color = x.onSuccessContainer)
                }
                Text(n.takeaway, style = MaterialTheme.typography.bodyMedium, color = x.onSuccessContainer, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

// ---------- Podcast ----------

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)

@Composable
private fun PodcastView(script: PodcastScript, header: @Composable ColumnScope.() -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val player = remember(script) { PodcastPlayer(context, script.lines) }
    DisposableEffect(player) { onDispose { player.release() } }
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    val list = rememberLazyListState()
    val finished = player.line >= script.lines.size
    LaunchedEffect(finished) { if (finished) onFinished() }
    LaunchedEffect(player.line) { if (player.playing && player.line < script.lines.size) list.animateScrollToItem(player.line + 1) }

    LazyColumn(Modifier.fillMaxSize().testTag("podcast"), state = list, contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 40.dp)) {
        item {
            Column {
                header()
                HeroCard(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(script.title, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.study_podcast_voices), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.height(16.dp))
                        val total = player.timeline.totalMs
                        ProgressBar((player.positionMs.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f), color = Color.White, track = Color.White.copy(alpha = 0.25f), height = 6.dp)
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(clock((player.positionMs / player.speed).toLong()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                            Spacer(Modifier.weight(1f))
                            Text(clock((total / player.speed).toLong()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                        }
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            val enabled = player.state == PodcastPlayer.State.READY
                            IconButton({ player.seekBy(-10_000) }, enabled = enabled) { Icon(Icons.Filled.Replay10, stringResource(R.string.study_back_10), tint = Color.White, modifier = Modifier.size(30.dp)) }
                            FilledIconButton(player::toggle, Modifier.size(64.dp), enabled = enabled,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = cs.primary)) {
                                Icon(if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    stringResource(if (player.playing) R.string.study_pause else R.string.study_play), Modifier.size(34.dp))
                            }
                            IconButton({ player.seekBy(10_000) }, enabled = enabled) { Icon(Icons.Filled.Forward10, stringResource(R.string.study_forward_10), tint = Color.White, modifier = Modifier.size(30.dp)) }
                        }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PodcastPlayer.SPEEDS.forEach { s ->
                                val on = player.speed == s
                                Box(
                                    Modifier.clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.15f))
                                        .clickable(role = Role.RadioButton) { player.changeSpeed(s) }.padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Text((if (s == 1f) "1" else s.toString()) + "×", style = MaterialTheme.typography.labelLarge, color = if (on) cs.primary else Color.White)
                                }
                            }
                        }
                    }
                }
                when (player.state) {
                    PodcastPlayer.State.UNAVAILABLE -> WarningBanner(listOf(stringResource(R.string.study_podcast_no_voice)), Modifier.padding(top = 4.dp))
                    PodcastPlayer.State.READY -> if (!player.distinctVoices) Text(stringResource(R.string.study_podcast_one_voice), style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    PodcastPlayer.State.LOADING -> Unit
                }
                SectionHeader(stringResource(R.string.study_transcript))
            }
        }
        itemsIndexed(script.lines) { i, line ->
            val current = i == player.line
            val host = line.speaker == "A"
            val bg by animateColorAsState(if (current) cs.primaryContainer else Color.Transparent, label = "line")
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).clickable { player.jumpTo(i) }.padding(10.dp)) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (host) cs.primary else x.streak), contentAlignment = Alignment.Center) {
                    Text(if (host) "A" else "B", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (host) R.string.study_host else R.string.study_expert), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    val text = if (current && player.playing) buildAnnotatedString {
                        val split = player.char.coerceIn(0, line.text.length)
                        withStyle(SpanStyle(color = cs.onSurface, fontWeight = FontWeight.SemiBold)) { append(line.text.substring(0, split)) }
                        append(line.text.substring(split))
                    } else buildAnnotatedString { append(line.text) }
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = if (current) cs.onSurface else cs.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------- Hafıza kartları ----------

@Composable
private fun FlashcardsView(deck: FlashcardDeck, header: @Composable ColumnScope.() -> Unit, onAgain: (Flashcard) -> Unit, onFinished: () -> Unit, onReview: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var known by rememberSaveable { mutableIntStateOf(0) }
    var again by rememberSaveable { mutableIntStateOf(0) }
    val cards = deck.cards
    if (cards.isEmpty()) { EmptyState(stringResource(R.string.study_nothing), stringResource(R.string.study_nothing_body)); return }
    ScrollPage(header) {
        if (index >= cards.size) {
            LaunchedEffect(Unit) { onFinished() }
            SessionSummary(known, cards.size, stringResource(R.string.study_cards_summary, known, again),
                onRestart = { index = 0; known = 0; again = 0 }, onReview = if (again > 0) onReview else null)
            return@ScrollPage
        }
        val card = cards[index]
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.study_card_n, index + 1, cards.size), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Pill(stringResource(R.string.study_known_n, known), color = MaterialTheme.extended.onSuccessContainer, container = MaterialTheme.extended.successContainer)
            Spacer(Modifier.width(6.dp))
            Pill(stringResource(R.string.study_again_n, again), color = MaterialTheme.extended.onWarningContainer, container = MaterialTheme.extended.warningContainer)
        }
        ProgressBar(index / cards.size.toFloat(), Modifier.padding(top = 10.dp))
        val next = { knew: Boolean -> if (knew) known++ else { again++; onAgain(card) }; index++; Unit }
        SwipeCard(card, index, onSwipe = next)
        Text(stringResource(R.string.study_swipe_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton(stringResource(R.string.study_again), { next(false) }, Modifier.weight(1f), icon = Icons.Filled.Replay, color = MaterialTheme.extended.warning)
            PrimaryButton(stringResource(R.string.study_know), { next(true) }, Modifier.weight(1f), icon = Icons.Filled.Done)
        }
    }
}

/** A card that flips in 3D when tapped and can be swiped right (know) or left (again). */
@Composable
private fun SwipeCard(card: Flashcard, key: Int, onSwipe: (knew: Boolean) -> Unit) {
    var flipped by remember(key) { mutableStateOf(false) }
    var dx by remember(key) { mutableFloatStateOf(0f) }
    var dragging by remember(key) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(450), label = "flip")
    val offset by animateFloatAsState(dx, if (dragging) tween(0) else tween(250), label = "swipe")
    val density = LocalDensity.current.density
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    val flipLabel = stringResource(R.string.study_flip)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        val threshold = constraints.maxWidth * 0.28f
        val tint = when {
            offset > threshold / 2 -> x.successContainer
            offset < -threshold / 2 -> x.warningContainer
            else -> x.card
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 260.dp)
                .offset { IntOffset(offset.roundToInt(), 0) }
                .graphicsLayer { rotationY = rotation; rotationZ = offset / 60f; cameraDistance = 14f * density }
                .clip(RoundedCornerShape(24.dp)).background(tint).border(1.dp, x.cardBorder, RoundedCornerShape(24.dp))
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            when {
                                dx > threshold -> onSwipe(true)
                                dx < -threshold -> onSwipe(false)
                                else -> dx = 0f
                            }
                        },
                        onDragCancel = { dragging = false; dx = 0f },
                    ) { _, amount -> dx += amount }
                }
                .clickable(role = Role.Button) { flipped = !flipped }
                .semantics { contentDescription = flipLabel }
                .testTag("flashcard"),
            contentAlignment = Alignment.Center,
        ) {
            if (rotation <= 90f) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.study_front).uppercase(AppLanguage.locale), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                    Text(card.front, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.TouchApp, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(flipLabel, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
            } else {
                Column(Modifier.graphicsLayer { rotationY = 180f }.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.study_back).uppercase(AppLanguage.locale), style = MaterialTheme.typography.labelMedium, color = x.success)
                    Text(card.back, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

/** End-of-session card: score ring, a short line and what to do next. */
@Composable
private fun SessionSummary(correct: Int, total: Int, line: String, onRestart: () -> Unit, onReview: (() -> Unit)?) {
    AppCard(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("session_summary")) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ProgressRing(correct / total.coerceAtLeast(1).toFloat(), size = 120.dp) {
                Text("$correct/$total", style = MaterialTheme.typography.headlineSmall)
            }
            Text(stringResource(R.string.study_session_done), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(stringResource(R.string.study_restart), onRestart, Modifier.weight(1f), icon = Icons.Filled.Refresh)
            if (onReview != null) PrimaryButton(stringResource(R.string.study_go_review), onReview, Modifier.weight(1f), icon = Icons.Filled.Replay)
        }
    }
}

// ---------- Bilgi boşlukları ----------

@Composable
private fun GapsView(deck: GapDeck, fromMistakes: Boolean, header: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    ScrollPage(header) {
        Text(stringResource(if (fromMistakes) R.string.study_gaps_mistakes else R.string.study_gaps_prereq), style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        if (deck.gaps.isEmpty()) EmptyState(stringResource(R.string.study_nothing), stringResource(R.string.study_nothing_body))
        deck.gaps.forEachIndexed { i, g ->
            AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(g.topic, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                if (g.why.isNotBlank()) Text(g.why, style = MaterialTheme.typography.bodySmall, color = x.warning, modifier = Modifier.padding(top = 8.dp))
                Text(g.explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                if (g.example.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(MaterialTheme.shapes.medium).background(x.infoContainer).padding(12.dp)) {
                        Text(stringResource(R.string.study_example), style = MaterialTheme.typography.labelMedium, color = x.onInfoContainer)
                        Text(g.example, style = MaterialTheme.typography.bodySmall, color = x.onInfoContainer, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

// ---------- Alıştırma ----------

@Composable
private fun ExercisesView(set: ExerciseSet, header: @Composable ColumnScope.() -> Unit, onWrongCloze: (ClozeItem) -> Unit, onWrongMatch: (String, String) -> Unit, onFinished: () -> Unit) {
    val chosen = remember(set) { mutableStateMapOf<Int, String>() }
    val matched = remember(set) { mutableStateListOf<String>() }
    val missed = remember(set) { mutableStateListOf<String>() }
    var pickedTerm by remember(set) { mutableStateOf<String?>(null) }
    var wrongFlash by remember(set) { mutableStateOf<String?>(null) }
    val definitions = remember(set) { set.matching.shuffled(java.util.Random(set.hashCode().toLong())) }
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    val complete = chosen.size == set.cloze.size && matched.size == set.matching.size
    LaunchedEffect(complete) { if (complete) onFinished() }

    ScrollPage(header) {
        if (set.cloze.isNotEmpty()) {
            SectionHeader(stringResource(R.string.study_cloze))
            set.cloze.forEachIndexed { i, item ->
                val answer = chosen[i]
                AppCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    val shown = if (answer == null) item.sentence else item.sentence.replace("_____", "[$answer]")
                    Text(shown, style = MaterialTheme.typography.bodyLarge)
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.options.forEach { o ->
                            val right = o.equals(item.answer, ignoreCase = true)
                            val state = when {
                                answer == null -> null
                                right -> true
                                o == answer -> false
                                else -> null
                            }
                            OptionChip(o, state, enabled = answer == null) {
                                chosen[i] = o
                                if (!right) onWrongCloze(item)
                            }
                        }
                    }
                    if (answer != null && !answer.equals(item.answer, ignoreCase = true)) {
                        Text(stringResource(R.string.study_correct_answer, item.answer), style = MaterialTheme.typography.bodySmall, color = x.success,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        if (set.matching.isNotEmpty()) {
            SectionHeader(stringResource(R.string.study_matching))
            Text(stringResource(R.string.study_matching_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    set.matching.forEach { m ->
                        val done = m.term in matched
                        MatchTile(m.term, selected = pickedTerm == m.term, done = done, wrong = false) { if (!done) pickedTerm = m.term }
                    }
                }
                Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    definitions.forEach { d ->
                        val done = d.term in matched
                        MatchTile(d.definition, selected = false, done = done, wrong = wrongFlash == d.definition, small = true) {
                            val term = pickedTerm ?: return@MatchTile
                            if (done) return@MatchTile
                            if (term == d.term) { matched += term; pickedTerm = null; wrongFlash = null }
                            else {
                                wrongFlash = d.definition
                                if (term !in missed) { missed += term; onWrongMatch(term, set.matching.first { it.term == term }.definition) }
                            }
                        }
                    }
                }
            }
        }
        if (complete) {
            val right = set.cloze.indices.count { chosen[it].equals(set.cloze[it].answer, ignoreCase = true) } + (set.matching.size - missed.size)
            SessionSummary(right, set.cloze.size + set.matching.size, stringResource(R.string.study_exercises_done),
                onRestart = { chosen.clear(); matched.clear(); missed.clear(); pickedTerm = null; wrongFlash = null }, onReview = null)
        }
    }
}

/** Option chip that turns green (right) or red (wrong) once answered. */
@Composable
private fun OptionChip(text: String, correct: Boolean?, enabled: Boolean, onClick: () -> Unit) {
    val x = MaterialTheme.extended
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (correct) {
        true -> x.successContainer to x.onSuccessContainer
        false -> cs.errorContainer to cs.onErrorContainer
        null -> x.card to cs.onSurface
    }
    Row(
        Modifier.heightIn(min = 40.dp).clip(CircleShape).background(bg).border(1.dp, x.cardBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        correct?.let { Icon(if (it) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null, Modifier.size(16.dp), tint = fg); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun MatchTile(text: String, selected: Boolean, done: Boolean, wrong: Boolean, small: Boolean = false, onClick: () -> Unit) {
    val x = MaterialTheme.extended
    val cs = MaterialTheme.colorScheme
    val bg = when {
        done -> x.successContainer
        selected -> cs.primaryContainer
        wrong -> cs.errorContainer
        else -> x.card
    }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium).background(bg)
            .border(1.dp, if (selected) cs.primary else x.cardBorder, MaterialTheme.shapes.medium)
            .clickable(enabled = !done, role = Role.Button, onClick = onClick).padding(10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelLarge,
            color = if (done) x.onSuccessContainer else cs.onSurface)
    }
}

// ---------- Test (A–D) ----------

@Composable
private fun McqView(set: McqSet, header: @Composable ColumnScope.() -> Unit, onWrong: (McqQuestion) -> Unit, onFinished: () -> Unit, onReview: () -> Unit) {
    var index by rememberSaveable(set) { mutableIntStateOf(0) }
    var correct by rememberSaveable(set) { mutableIntStateOf(0) }
    var wrong by rememberSaveable(set) { mutableIntStateOf(0) }
    var picked by rememberSaveable(set, index) { mutableStateOf<Int?>(null) }
    val qs = set.questions
    if (qs.isEmpty()) { EmptyState(stringResource(R.string.study_nothing), stringResource(R.string.study_nothing_body)); return }
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    ScrollPage(header) {
        if (index >= qs.size) {
            LaunchedEffect(Unit) { onFinished() }
            SessionSummary(correct, qs.size, stringResource(if (wrong > 0) R.string.study_wrong_to_review else R.string.study_all_right),
                onRestart = { index = 0; correct = 0; wrong = 0 }, onReview = if (wrong > 0) onReview else null)
            return@ScrollPage
        }
        val q = qs[index]
        Text(stringResource(R.string.study_question_n, index + 1, qs.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
        ProgressBar(index / qs.size.toFloat(), Modifier.padding(top = 8.dp))
        Text(q.question, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp, bottom = 14.dp))
        q.options.forEachIndexed { i, o ->
            val state = when {
                picked == null -> null
                i == q.answer -> true
                i == picked -> false
                else -> null
            }
            val (bg, border) = when (state) {
                true -> x.successContainer to x.success
                false -> cs.errorContainer to cs.error
                null -> x.card to x.cardBorder
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(MaterialTheme.shapes.large).background(bg).border(1.dp, border, MaterialTheme.shapes.large)
                    .clickable(enabled = picked == null, role = Role.Button) {
                        picked = i
                        if (i == q.answer) correct++ else { wrong++; onWrong(q) }
                    }
                    .padding(14.dp).testTag("option_$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (state == null) cs.surfaceContainerHigh else border), contentAlignment = Alignment.Center) {
                    Text(('A' + i).toString(), style = MaterialTheme.typography.labelLarge, color = if (state == null) cs.onSurface else Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text(o, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
        if (picked != null) {
            Explanation(picked == q.answer, q.explanation)
            PrimaryButton(stringResource(if (index + 1 < qs.size) R.string.next else R.string.study_finish), { index++ }, Modifier.fillMaxWidth().padding(top = 14.dp))
        }
    }
}

@Composable
private fun Explanation(right: Boolean, text: String) {
    val x = MaterialTheme.extended
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(MaterialTheme.shapes.medium)
        .background(if (right) x.successContainer else cs.errorContainer).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (right) Icons.Filled.CheckCircle else Icons.Filled.Close, null, tint = if (right) x.success else cs.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (right) R.string.correct else R.string.incorrect), style = MaterialTheme.typography.titleSmall,
                color = if (right) x.onSuccessContainer else cs.onErrorContainer)
        }
        if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium, color = if (right) x.onSuccessContainer else cs.onErrorContainer,
            modifier = Modifier.padding(top = 6.dp))
    }
}

// ---------- Doğru / Yanlış ----------

@Composable
private fun TrueFalseView(set: TrueFalseSet, header: @Composable ColumnScope.() -> Unit, onWrong: (TrueFalseItem) -> Unit, onFinished: () -> Unit, onReview: () -> Unit) {
    var index by rememberSaveable(set) { mutableIntStateOf(0) }
    var correct by rememberSaveable(set) { mutableIntStateOf(0) }
    var wrong by rememberSaveable(set) { mutableIntStateOf(0) }
    var picked by rememberSaveable(set, index) { mutableStateOf<Boolean?>(null) }
    val items = set.items
    if (items.isEmpty()) { EmptyState(stringResource(R.string.study_nothing), stringResource(R.string.study_nothing_body)); return }
    ScrollPage(header) {
        if (index >= items.size) {
            LaunchedEffect(Unit) { onFinished() }
            SessionSummary(correct, items.size, stringResource(if (wrong > 0) R.string.study_wrong_to_review else R.string.study_all_right),
                onRestart = { index = 0; correct = 0; wrong = 0 }, onReview = if (wrong > 0) onReview else null)
            return@ScrollPage
        }
        val item = items[index]
        Text(stringResource(R.string.study_question_n, index + 1, items.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
        ProgressBar(index / items.size.toFloat(), Modifier.padding(top = 8.dp))
        AppCard(Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 140.dp)) {
            Text(item.statement, style = MaterialTheme.typography.titleLarge)
        }
        val answer = { value: Boolean ->
            picked = value
            if (value == item.isTrue) correct++ else { wrong++; onWrong(item) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TfButton(stringResource(R.string.true_label), true, picked, item.isTrue, Modifier.weight(1f)) { answer(true) }
            TfButton(stringResource(R.string.false_label), false, picked, item.isTrue, Modifier.weight(1f)) { answer(false) }
        }
        if (picked != null) {
            Spacer(Modifier.height(12.dp))
            Explanation(picked == item.isTrue, item.explanation)
            PrimaryButton(stringResource(if (index + 1 < items.size) R.string.next else R.string.study_finish), { index++ }, Modifier.fillMaxWidth().padding(top = 14.dp))
        }
    }
}

@Composable
private fun TfButton(label: String, value: Boolean, picked: Boolean?, truth: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val x = MaterialTheme.extended
    val cs = MaterialTheme.colorScheme
    val bg = when {
        picked == null -> if (value) x.successContainer else cs.errorContainer
        value == truth -> x.successContainer
        value == picked -> cs.errorContainer
        else -> cs.surfaceContainerHigh
    }
    val fg = if (value) x.onSuccessContainer else cs.onErrorContainer
    Box(
        modifier.heightIn(min = 64.dp).clip(MaterialTheme.shapes.large).background(bg)
            .clickable(enabled = picked == null, role = Role.Button, onClick = onClick).testTag(if (value) "tf_true" else "tf_false"),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (value) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null, tint = fg)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = fg)
        }
    }
}
