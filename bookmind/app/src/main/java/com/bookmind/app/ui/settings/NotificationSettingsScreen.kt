package com.bookmind.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.notifications.Notifier
import com.bookmind.app.notifications.ReminderManager
import com.bookmind.app.notifications.ReminderPrefs
import com.bookmind.app.notifications.ReminderType
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.util.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(private val reminders: ReminderManager, private val clock: AppClock) : ViewModel() {
    val prefs = reminders.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun save(p: ReminderPrefs) = viewModelScope.launch { reminders.save(p) }
    fun today() = clock.today()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit, vm: NotificationSettingsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(!Notifier.canPost(context)) }
    var pickTime by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> permissionDenied = !granted }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.notifications_title), onBack)
        val p = prefs ?: run { LoadingState(); return@Column }
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().toggleable(p.enabled, role = Role.Switch) { on ->
                vm.save(p.copy(enabled = on))
                if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reminders_enabled), Modifier.weight(1f)); Switch(p.enabled, null)
            }
            if (p.enabled && permissionDenied) WarningBanner(listOf(stringResource(R.string.notifications_permission_denied)))
            Text(stringResource(R.string.reminder_time), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            OutlinedButton({ pickTime = true }, enabled = p.enabled) { Text("%02d:%02d".format(p.time.hour, p.time.minute)) }
            Text(stringResource(R.string.reminder_days), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(d in p.days, { vm.save(p.copy(days = if (d in p.days) p.days - d else p.days + d)) },
                        { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) }, enabled = p.enabled)
                }
            }
            Text(stringResource(R.string.reminder_types), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            listOf(ReminderType.DUE_REVIEWS to R.string.type_due, ReminderType.OVERDUE to R.string.type_overdue, ReminderType.DAILY_GOAL to R.string.type_goal,
                ReminderType.CONTINUE_BOOK to R.string.type_continue).forEach { (t, label) ->
                Row(Modifier.fillMaxWidth().toggleable(t in p.types, enabled = p.enabled, role = Role.Switch) { vm.save(p.copy(types = if (it) p.types + t else p.types - t)) },
                    verticalAlignment = Alignment.CenterVertically) { Text(stringResource(label), Modifier.weight(1f)); Switch(t in p.types, null, enabled = p.enabled) }
            }
            Text(stringResource(R.string.intensity), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(p.intensity == 1, { vm.save(p.copy(intensity = 1)) }, { Text(stringResource(R.string.intensity_once)) }, enabled = p.enabled)
                FilterChip(p.intensity == 2, { vm.save(p.copy(intensity = 2)) }, { Text(stringResource(R.string.intensity_evening)) }, enabled = p.enabled)
            }
            Text(stringResource(R.string.vacation_mode), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = vm.today()
                FilterChip(p.pausedUntil == null, { vm.save(p.copy(pausedUntil = null)) }, { Text(stringResource(R.string.vacation_off)) })
                listOf(3L, 7L, 14L).forEach { n ->
                    val until = today.plusDays(n - 1)
                    FilterChip(p.pausedUntil == until, { vm.save(p.copy(pausedUntil = until)) }, { Text(stringResource(R.string.vacation_days, n.toInt())) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).toggleable(p.showContent, role = Role.Switch) { vm.save(p.copy(showContent = it)) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.show_content))
                    Text(stringResource(R.string.show_content_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(p.showContent, null)
            }
            Text(stringResource(R.string.notifications_disclaimer), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 16.dp))
        }
        if (pickTime) {
            val state = rememberTimePickerState(p.time.hour, p.time.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { pickTime = false },
                text = { TimePicker(state) },
                confirmButton = { TextButton({ pickTime = false; vm.save(p.copy(time = LocalTime.of(state.hour, state.minute))) }) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton({ pickTime = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
