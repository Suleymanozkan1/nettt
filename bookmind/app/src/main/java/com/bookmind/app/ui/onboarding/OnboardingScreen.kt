package com.bookmind.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.bookmind.app.R
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.notifications.ReminderManager
import com.bookmind.app.notifications.ReminderPrefs
import com.bookmind.app.notifications.ReminderType
import dagger.hilt.android.lifecycle.HiltViewModel
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
    suspend fun finish(goalMinutes: Int, remindersOn: Boolean, hour: Int, addDemo: Boolean) {
        users.update { it.copy(dailyGoalMinutes = goalMinutes) }
        reminders.save(
            ReminderPrefs(remindersOn, LocalTime.of(hour, 0), DayOfWeek.entries.toSet(),
                setOf(ReminderType.DUE_REVIEWS, ReminderType.OVERDUE, ReminderType.DAILY_GOAL), 1, null, false),
        )
        if (addDemo) runCatching { library.addDemoBook() }
        settings.update { it.copy(onboardingDone = true) }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    var page by remember { mutableIntStateOf(0) }
    var goal by remember { mutableIntStateOf(10) }
    var remindersOn by remember { mutableStateOf(true) }
    var hour by remember { mutableIntStateOf(19) }
    var addDemo by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val finish = { scope.launch { busy = true; vm.finish(goal, remindersOn, hour, addDemo); onDone() }; Unit }
    // Ask for notification permission at the moment it is needed; the app works the same if it is denied.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) remindersOn = false
        finish()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState())) {
        when (page) {
            0 -> IntroPage(Icons.Filled.AutoStories, R.string.onb_title_1, R.string.onb_body_1)
            1 -> IntroPage(Icons.Filled.Psychology, R.string.onb_title_2, R.string.onb_body_2)
            2 -> IntroPage(Icons.Filled.Lock, R.string.onb_title_3, R.string.onb_body_3)
            else -> {
                Text(stringResource(R.string.onb_setup_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.onb_goal_question))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 20, 30).forEach { m -> FilterChip(goal == m, { goal = m }, { Text(stringResource(R.string.minutes_short, m)) }) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().toggleable(remindersOn, role = Role.Switch) { remindersOn = it }, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.onb_reminders), Modifier.weight(1f))
                    Switch(remindersOn, null)
                }
                if (remindersOn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8, 12, 19, 21).forEach { h -> FilterChip(hour == h, { hour = h }, { Text("%02d:00".format(h)) }) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().toggleable(addDemo, role = Role.Checkbox) { addDemo = it }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(addDemo, null)
                    Text(stringResource(R.string.onb_add_demo))
                }
            }
        }
        Spacer(Modifier.weight(1f).height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (page > 0) TextButton({ page-- }) { Text(stringResource(R.string.back)) } else Spacer(Modifier)
            Button(
                enabled = !busy,
                onClick = {
                    if (page < 3) page++
                    else if (remindersOn && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else finish()
                },
            ) { Text(stringResource(if (page < 3) R.string.next else R.string.onb_start)) }
        }
    }
}

@Composable
private fun IntroPage(icon: ImageVector, title: Int, body: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
