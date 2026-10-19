package com.bookmind.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.notifications.ReminderManager
import com.bookmind.app.notifications.ReminderPrefs
import com.bookmind.app.notifications.ReminderType
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.theme.Lora
import com.bookmind.app.ui.theme.extended
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val users: UserRepository,
    private val reminders: ReminderManager,
    private val library: LibraryRepository,
) : ViewModel() {
    private val _done = MutableStateFlow(false)
    val done = _done.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val language = settings.settings.map { it.language }.stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.tag)

    /** Saves the language and switches the UI to it; the activity is recreated with the new locale. */
    fun setLanguage(tag: String) = viewModelScope.launch {
        settings.update { it.copy(language = tag) }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun finish(goalMinutes: Int, remindersOn: Boolean, hour: Int, addDemo: Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            save(goalMinutes, remindersOn, hour, addDemo)
            _done.value = true
        }
    }

    private suspend fun save(goalMinutes: Int, remindersOn: Boolean, hour: Int, addDemo: Boolean) {
        users.update { it.copy(dailyGoalMinutes = goalMinutes, language = AppLanguage.tag) }
        reminders.save(
            ReminderPrefs(remindersOn, LocalTime.of(hour, 0), DayOfWeek.entries.toSet(),
                setOf(ReminderType.DUE_REVIEWS, ReminderType.OVERDUE, ReminderType.DAILY_GOAL), 1, null, false),
        )
        if (addDemo) runCatching { library.addDemoBook() }
        settings.update { it.copy(onboardingDone = true) }
    }
}

private const val LAST_PAGE = 4

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    // Saveable: choosing a language recreates the activity and the user must land on the same step.
    var page by rememberSaveable { mutableIntStateOf(0) }
    var goal by rememberSaveable { mutableIntStateOf(10) }
    var remindersOn by rememberSaveable { mutableStateOf(true) }
    var hour by rememberSaveable { mutableIntStateOf(19) }
    var addDemo by rememberSaveable { mutableStateOf(true) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    // Navigation happens on the main thread once the view model has saved everything.
    LaunchedEffect(done) { if (done) onDone() }
    val finish = { vm.finish(goal, remindersOn, hour, addDemo) }
    // Ask for notification permission at the moment it is needed; the app works the same if it is denied.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) remindersOn = false
        finish()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Top bar: back + step dots.
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) TextButton({ page-- }) { Text(stringResource(R.string.back)) } else Spacer(Modifier.width(64.dp))
            Spacer(Modifier.weight(1f))
            StepDots(page, LAST_PAGE + 1)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        AnimatedContent(page, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "onboarding") { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                when (p) {
                    0 -> LanguagePage(language, vm::setLanguage)
                    1 -> IntroPage(Icons.Filled.AutoStories, listOf(Icons.AutoMirrored.Filled.MenuBook, Icons.Filled.Style), R.string.onb_title_1, R.string.onb_body_1)
                    2 -> IntroPage(Icons.Filled.Psychology, listOf(Icons.Filled.Quiz, Icons.Filled.Schedule), R.string.onb_title_2, R.string.onb_body_2)
                    3 -> IntroPage(Icons.Filled.Lock, listOf(Icons.Filled.Shield, Icons.Filled.CheckCircle), R.string.onb_title_3, R.string.onb_body_3)
                    else -> SetupPage(goal, { goal = it }, remindersOn, { remindersOn = it }, hour, { hour = it }, addDemo, { addDemo = it })
                }
            }
        }
        PrimaryButton(
            text = stringResource(if (page < LAST_PAGE) R.string.next else R.string.onb_start),
            icon = if (page < LAST_PAGE) Icons.AutoMirrored.Filled.ArrowForward else null,
            loading = busy,
            onClick = {
                if (page < LAST_PAGE) page++
                else if (remindersOn && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else finish()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun StepDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 22.dp else 8.dp, label = "dot")
            Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest))
        }
    }
}

@Composable
private fun LanguagePage(selected: String, onSelect: (String) -> Unit) {
    // Kept compact so both options are visible without scrolling on small (320×640 dp) phones.
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HeroCard(Modifier.size(80.dp)) {
            Icon(Icons.Filled.Translate, null, Modifier.size(38.dp).align(Alignment.Center))
        }
        Spacer(Modifier.height(18.dp))
        // Shown in both languages: the user has not picked one yet.
        Text("Hoş geldin · Welcome", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onb_language_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        listOf("tr" to "Türkçe", "en" to "English").forEach { (tag, name) ->
            LanguageOption(name, tag.uppercase(), selected == tag) { if (selected != tag) onSelect(tag) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LanguageOption(name: String, code: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(if (selected) cs.primaryContainer else MaterialTheme.extended.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else MaterialTheme.extended.cardBorder, MaterialTheme.shapes.large)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) cs.primary else cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text(code, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onPrimary else cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (selected) cs.primary else cs.outline)
    }
}

@Composable
private fun IntroPage(icon: ImageVector, satellites: List<ImageVector>, title: Int, body: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(220.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)))
            HeroCard(Modifier.size(132.dp)) { Icon(icon, null, Modifier.size(64.dp).align(Alignment.Center)) }
            val x = MaterialTheme.extended
            satellites.getOrNull(0)?.let { IconBadge(it, x.streak, x.warningContainer, Modifier.align(Alignment.TopEnd).offset((-14).dp, 22.dp), size = 52.dp, iconSize = 26.dp) }
            satellites.getOrNull(1)?.let { IconBadge(it, x.success, x.successContainer, Modifier.align(Alignment.BottomStart).offset(14.dp, (-22).dp), size = 52.dp, iconSize = 26.dp) }
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetupPage(
    goal: Int, onGoal: (Int) -> Unit,
    remindersOn: Boolean, onReminders: (Boolean) -> Unit,
    hour: Int, onHour: (Int) -> Unit,
    addDemo: Boolean, onDemo: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Text(stringResource(R.string.onb_setup_title), fontFamily = Lora, style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.onb_setup_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))

        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Schedule, cs.primary, cs.primaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.onb_goal_question), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 20, 30).forEach { m -> ChoiceChip(goal == m, stringResource(R.string.minutes_short, m), { onGoal(m) }) }
            }
        }
        Spacer(Modifier.height(12.dp))
        AppCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().toggleable(remindersOn, role = Role.Switch, onValueChange = onReminders), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.NotificationsActive, x.streak, x.warningContainer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.onb_reminders), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.onb_reminders_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Switch(remindersOn, null)
            }
            if (remindersOn) {
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8, 12, 19, 21).forEach { h -> ChoiceChip(hour == h, "%02d:00".format(h), { onHour(h) }) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AppCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().toggleable(addDemo, role = Role.Switch, onValueChange = onDemo), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.AutoMirrored.Filled.MenuBook, x.success, x.successContainer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.onb_add_demo), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.onb_add_demo_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Switch(addDemo, null)
            }
        }
    }
}
