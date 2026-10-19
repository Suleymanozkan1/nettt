package com.bookmind.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
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
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.theme.extended
import com.bookmind.app.util.AppClock
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(private val reminders: ReminderManager, private val clock: AppClock) : ViewModel() {
    val prefs = reminders.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun save(p: ReminderPrefs) = viewModelScope.launch { reminders.save(p) }
    fun today() = clock.today()
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, enabled: Boolean = true, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, null, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit, vm: NotificationSettingsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(!Notifier.canPost(context)) }
    var pickTime by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> permissionDenied = !granted }
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.notifications_title), onBack)
        val p = prefs ?: run { LoadingState(); return@Column }
        ContentWidth(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter)) {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.NotificationsActive, x.streak, x.warningContainer)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) {
                            SwitchLine(stringResource(R.string.reminders_enabled), p.enabled) { on ->
                                vm.save(p.copy(enabled = on))
                                if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
                }
                if (p.enabled && permissionDenied) WarningBanner(listOf(stringResource(R.string.notifications_permission_denied)))

                AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.reminder_time), style = MaterialTheme.typography.titleMedium)
                    Text("%02d:%02d".format(p.time.hour, p.time.minute), style = MaterialTheme.typography.displaySmall, color = if (p.enabled) cs.primary else cs.outline,
                        modifier = Modifier.padding(top = 4.dp).clip(MaterialTheme.shapes.medium).clickable(enabled = p.enabled) { pickTime = true }.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.reminder_days), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { d ->
                            ChoiceChip(d in p.days, d.getDisplayName(TextStyle.SHORT, com.bookmind.app.data.settings.AppLanguage.locale),
                                { vm.save(p.copy(days = if (d in p.days) p.days - d else p.days + d)) }, enabled = p.enabled)
                        }
                    }
                }

                AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.reminder_types), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                    listOf(ReminderType.DUE_REVIEWS to R.string.type_due, ReminderType.OVERDUE to R.string.type_overdue, ReminderType.DAILY_GOAL to R.string.type_goal,
                        ReminderType.CONTINUE_BOOK to R.string.type_continue).forEach { (t, label) ->
                        SwitchLine(stringResource(label), t in p.types, enabled = p.enabled) { vm.save(p.copy(types = if (it) p.types + t else p.types - t)) }
                    }
                }

                AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.intensity), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip(p.intensity == 1, stringResource(R.string.intensity_once), { vm.save(p.copy(intensity = 1)) }, enabled = p.enabled)
                        ChoiceChip(p.intensity == 2, stringResource(R.string.intensity_evening), { vm.save(p.copy(intensity = 2)) }, enabled = p.enabled)
                    }
                    Text(stringResource(R.string.vacation_mode), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val today = vm.today()
                        ChoiceChip(p.pausedUntil == null, stringResource(R.string.vacation_off), { vm.save(p.copy(pausedUntil = null)) })
                        listOf(3L, 7L, 14L).forEach { n ->
                            val until = today.plusDays(n - 1)
                            ChoiceChip(p.pausedUntil == until, stringResource(R.string.vacation_days, n.toInt()), { vm.save(p.copy(pausedUntil = until)) })
                        }
                    }
                }

                AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    SwitchLine(stringResource(R.string.show_content), p.showContent, subtitle = stringResource(R.string.show_content_hint)) { vm.save(p.copy(showContent = it)) }
                }
                Text(stringResource(R.string.notifications_disclaimer), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
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
