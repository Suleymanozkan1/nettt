package com.bookmind.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.AiUsageEntity
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.remote.AuthRepository
import com.bookmind.app.data.repo.AccountRepository
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.AppSettings
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.data.settings.SrsAlgorithm
import com.bookmind.app.data.settings.ThemeMode
import com.bookmind.app.ui.components.SectionTitle
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.util.AppClock
import com.bookmind.app.work.WorkScheduler
import com.bookmind.core.ai.SummaryLength
import com.bookmind.core.ai.TeachingLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
    private val users: UserRepository,
    private val account: AccountRepository,
    private val scheduler: WorkScheduler,
    db: BookMindDatabase,
    clock: AppClock,
) : ViewModel() {
    val state = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val user = users.user.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val accountState = auth.account
    val backendConfigured = auth.isBackendConfigured
    val usage = db.ai().observeUsageSince(clock.now() - 30L * 86_400_000).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<AiUsageEntity>())
    val pendingSync = db.sync().observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch { settings.update(t) }
    fun goal(minutes: Int) = viewModelScope.launch { users.update { it.copy(dailyGoalMinutes = minutes) } }
    fun signIn(email: String, password: String, create: Boolean) = viewModelScope.launch {
        _message.value = runCatching {
            if (create) { if (auth.signUp(email, password)) null else "confirm" } else { auth.signIn(email, password); null }
        }.fold({ it }, { "error:" + (it.message ?: "") })
        if (auth.account.value != null) { scheduler.schedulePeriodicSync(); scheduler.syncNow() }
    }
    fun signOut() = viewModelScope.launch { auth.signOut(); scheduler.cancelSync() }
    fun syncNow() = scheduler.syncNow()
    fun export(uri: android.net.Uri) = viewModelScope.launch {
        _message.value = runCatching { account.export(uri); "exported" }.getOrElse { "error:" + it.message }
    }
    fun deleteAll(onDone: () -> Unit) = viewModelScope.launch {
        _message.value = runCatching { account.deleteEverything(); null }.getOrElse { "error:" + it.message }
        if (_message.value == null) onDone()
    }
    fun clearMessage() { _message.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNotifications: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val acct by vm.accountState.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val pending by vm.pendingSync.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::export) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            message?.let { m ->
                val text = when {
                    m == "confirm" -> stringResource(R.string.signup_confirm)
                    m == "exported" -> stringResource(R.string.export_done)
                    m.startsWith("error:") -> stringResource(R.string.error_generic, m.removePrefix("error:"))
                    else -> m
                }
                WarningBanner(listOf(text), Modifier.clickable { vm.clearMessage() })
            }

            SectionTitle(stringResource(R.string.learning_settings))
            Text(stringResource(R.string.daily_goal), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 20, 30).forEach { m -> FilterChip(user?.dailyGoalMinutes == m, { vm.goal(m) }, { Text(stringResource(R.string.minutes_short, m)) }) }
            }
            ListItem(headlineContent = { Text(stringResource(R.string.notifications_title)) }, supportingContent = { Text(stringResource(R.string.notifications_summary)) },
                modifier = Modifier.clickable(onClick = onNotifications))
            Text(stringResource(R.string.srs_algorithm), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.algorithm == SrsAlgorithm.FSRS, { vm.update { it.copy(algorithm = SrsAlgorithm.FSRS) } }, { Text("FSRS") })
                FilterChip(s.algorithm == SrsAlgorithm.SM2, { vm.update { it.copy(algorithm = SrsAlgorithm.SM2) } }, { Text("SM-2") })
            }
            if (s.algorithm == SrsAlgorithm.FSRS) {
                Text(stringResource(R.string.desired_retention, (s.desiredRetention * 100).toInt()))
                Slider(s.desiredRetention, { v -> vm.update { it.copy(desiredRetention = v) } }, valueRange = 0.75f..0.95f, steps = 3)
                Text(stringResource(R.string.desired_retention_hint), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.new_per_day, s.newItemsPerDay))
            Slider(s.newItemsPerDay.toFloat(), { v -> vm.update { it.copy(newItemsPerDay = v.toInt()) } }, valueRange = 0f..50f, steps = 9)
            Text(stringResource(R.string.default_teaching_level), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TeachingLevel.entries.forEach { l -> FilterChip(s.teachingLevel == l.name, { vm.update { it.copy(teachingLevel = l.name) } }, { Text(com.bookmind.app.ui.teach.levelLabel(l)) }) }
            }
            Text(stringResource(R.string.default_summary_length), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SummaryLength.SHORT to R.string.length_short, SummaryLength.MEDIUM to R.string.length_medium, SummaryLength.LONG to R.string.length_long).forEach { (l, label) ->
                    FilterChip(s.summaryLength == l.name, { vm.update { it.copy(summaryLength = l.name) } }, { Text(stringResource(label)) })
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.spoiler_free)) },
                trailingContent = { androidx.compose.material3.Switch(s.spoilerFree, { v -> vm.update { it.copy(spoilerFree = v) } }) },
            )

            SectionTitle(stringResource(R.string.ai_settings))
            Text(stringResource(R.string.ai_mode_hint), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.aiMode == AiMode.LOCAL, { vm.update { it.copy(aiMode = AiMode.LOCAL) } }, { Text(stringResource(R.string.ai_local)) })
                FilterChip(s.aiMode == AiMode.REMOTE, { vm.update { it.copy(aiMode = AiMode.REMOTE) } }, { Text(stringResource(R.string.ai_remote)) }, enabled = vm.backendConfigured)
                FilterChip(s.aiMode == AiMode.MOCK, { vm.update { it.copy(aiMode = AiMode.MOCK) } }, { Text(stringResource(R.string.ai_mock)) })
            }
            if (s.aiMode == AiMode.REMOTE && acct == null) WarningBanner(listOf(stringResource(R.string.ai_remote_needs_account)))
            if (!vm.backendConfigured) Text(stringResource(R.string.backend_not_configured), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.ai_daily_limit, s.dailyAiRequestLimit))
            Slider(s.dailyAiRequestLimit.toFloat(), { v -> vm.update { it.copy(dailyAiRequestLimit = v.toInt()) } }, valueRange = 0f..300f, steps = 11)
            val tokens = usage.sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }
            val cost = usage.sumOf { it.costMicros ?: 0 } / 1_000_000.0
            Text(stringResource(R.string.ai_usage, usage.size, tokens, "%.3f".format(cost)), style = MaterialTheme.typography.bodySmall)

            SectionTitle(stringResource(R.string.appearance))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark).forEach { (t, label) ->
                    FilterChip(s.theme == t, { vm.update { it.copy(theme = t) } }, { Text(stringResource(label)) })
                }
            }
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to R.string.lang_system, "tr" to R.string.lang_tr, "en" to R.string.lang_en).forEach { (tag, label) ->
                    FilterChip(s.language == tag, {
                        vm.update { it.copy(language = tag) }
                        AppCompatDelegate.setApplicationLocales(if (tag == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                    }, { Text(stringResource(label)) })
                }
            }

            SectionTitle(stringResource(R.string.account))
            if (!vm.backendConfigured) {
                Text(stringResource(R.string.account_local_only), style = MaterialTheme.typography.bodySmall)
            } else if (acct == null) {
                Text(stringResource(R.string.account_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.email)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.signIn(email, password, false) }, enabled = email.contains('@') && password.length >= 8) { Text(stringResource(R.string.sign_in)) }
                    OutlinedButton({ vm.signIn(email, password, true) }, enabled = email.contains('@') && password.length >= 8) { Text(stringResource(R.string.sign_up)) }
                }
            } else {
                Text(stringResource(R.string.signed_in_as, acct?.email ?: acct!!.userId))
                Text(stringResource(R.string.sync_pending, pending), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(vm::syncNow) { Text(stringResource(R.string.sync_now)) }
                    OutlinedButton(vm::signOut) { Text(stringResource(R.string.sign_out)) }
                }
            }

            SectionTitle(stringResource(R.string.your_data))
            OutlinedButton({ exporter.launch("bookmind-export.zip") }) { Text(stringResource(R.string.export_data)) }
            Text(stringResource(R.string.export_hint), style = MaterialTheme.typography.bodySmall)
            TextButton({ confirmDelete = true }) { Text(stringResource(R.string.delete_everything), color = MaterialTheme.colorScheme.error) }

            SectionTitle(stringResource(R.string.about))
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.licenses_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_everything)) },
        text = { Text(stringResource(R.string.delete_everything_confirm)) },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteAll { } }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
