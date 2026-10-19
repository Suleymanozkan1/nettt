package com.bookmind.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import com.bookmind.app.ui.ai.aiStatusText
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.bookmind.app.ui.components.AppSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.GhostButton
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.ListRow
import com.bookmind.app.ui.components.PageHeader
import com.bookmind.app.ui.components.PrimaryButton
import com.bookmind.app.ui.components.SecondaryButton
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.theme.extended
import com.bookmind.app.util.AppClock
import com.bookmind.app.work.WorkScheduler
import com.bookmind.core.ai.SummaryLength
import com.bookmind.core.ai.TeachingLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
    private val users: UserRepository,
    private val account: AccountRepository,
    private val scheduler: WorkScheduler,
    private val syncEngine: com.bookmind.app.sync.SyncEngine,
    db: BookMindDatabase,
    clock: AppClock,
    models: com.bookmind.app.ai.ModelManager,
) : ViewModel() {
    val cloudAvailable = com.bookmind.app.BuildConfig.CLOUD_AI_ENABLED && auth.isBackendConfigured
    val ai = models.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        if (auth.account.value != null) {
            // First sync after sign-in uploads this device's whole library, then keeps it in step.
            syncEngine.enqueueSnapshot()
            scheduler.schedulePeriodicSync()
            scheduler.syncNow()
        }
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

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(com.bookmind.app.data.settings.AppLanguage.locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
    AppCard(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier.padding(bottom = 10.dp))
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.extended.cardBorder)

@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun SettingsScreen(onNotifications: () -> Unit, onAi: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
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
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended

    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.settings_title))
        ContentWidth(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter)) {
                message?.let { m ->
                    val text = when {
                        m == "confirm" -> stringResource(R.string.signup_confirm)
                        m == "exported" -> stringResource(R.string.export_done)
                        m.startsWith("error:") -> stringResource(R.string.error_generic, m.removePrefix("error:"))
                        else -> m
                    }
                    WarningBanner(listOf(text), Modifier.clickable { vm.clearMessage() })
                }

                // Profile card
                HeroCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Person, null, Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(acct?.email ?: stringResource(R.string.profile_local), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                            Text(stringResource(R.string.profile_goal, user?.dailyGoalMinutes ?: 0), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }

                Group(stringResource(R.string.learning_settings)) {
                    Label(stringResource(R.string.daily_goal))
                    Chips { listOf(5, 10, 20, 30).forEach { m -> ChoiceChip(user?.dailyGoalMinutes == m, stringResource(R.string.minutes_short, m), { vm.goal(m) }) } }
                    Divider()
                    ListRow(stringResource(R.string.notifications_title), subtitle = stringResource(R.string.notifications_summary), icon = Icons.Filled.Notifications,
                        iconTint = x.streak, iconContainer = x.warningContainer, onClick = onNotifications) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
                    }
                    Divider()
                    Label(stringResource(R.string.srs_algorithm))
                    Chips {
                        ChoiceChip(s.algorithm == SrsAlgorithm.FSRS, "FSRS", { vm.update { it.copy(algorithm = SrsAlgorithm.FSRS) } })
                        ChoiceChip(s.algorithm == SrsAlgorithm.SM2, "SM-2", { vm.update { it.copy(algorithm = SrsAlgorithm.SM2) } })
                    }
                    if (s.algorithm == SrsAlgorithm.FSRS) {
                        Text(stringResource(R.string.desired_retention, (s.desiredRetention * 100).toInt()), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                        AppSlider(s.desiredRetention, { v -> vm.update { it.copy(desiredRetention = v) } }, valueRange = 0.75f..0.95f, steps = 3)
                        Hint(stringResource(R.string.desired_retention_hint))
                    }
                    Text(stringResource(R.string.new_per_day, s.newItemsPerDay), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    AppSlider(s.newItemsPerDay.toFloat(), { v -> vm.update { it.copy(newItemsPerDay = v.toInt()) } }, valueRange = 0f..50f, steps = 9)
                    Divider()
                    Label(stringResource(R.string.default_teaching_level))
                    Chips { TeachingLevel.entries.forEach { l -> ChoiceChip(s.teachingLevel == l.name, com.bookmind.app.ui.teach.levelLabel(l), { vm.update { it.copy(teachingLevel = l.name) } }) } }
                    Divider()
                    Label(stringResource(R.string.default_summary_length))
                    Chips {
                        listOf(SummaryLength.SHORT to R.string.length_short, SummaryLength.MEDIUM to R.string.length_medium, SummaryLength.LONG to R.string.length_long).forEach { (l, label) ->
                            ChoiceChip(s.summaryLength == l.name, stringResource(label), { vm.update { it.copy(summaryLength = l.name) } })
                        }
                    }
                    Divider()
                    Row(Modifier.fillMaxWidth().toggleable(s.spoilerFree, role = Role.Switch) { v -> vm.update { it.copy(spoilerFree = v) } }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.VisibilityOff, null, tint = cs.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.spoiler_free), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(s.spoilerFree, null)
                    }
                }

                Group(stringResource(R.string.ai_settings)) {
                    val ai by vm.ai.collectAsStateWithLifecycle()
                    ListRow(
                        stringResource(R.string.ai_title),
                        subtitle = ai?.let { aiStatusText(it) },
                        icon = Icons.Filled.AutoAwesome,
                        onClick = onAi,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant) }
                    if (vm.cloudAvailable) {
                        Divider()
                        Text(stringResource(R.string.ai_daily_limit, s.dailyAiRequestLimit), style = MaterialTheme.typography.titleSmall)
                        AppSlider(s.dailyAiRequestLimit.toFloat(), { v -> vm.update { it.copy(dailyAiRequestLimit = v.toInt()) } }, valueRange = 0f..300f, steps = 11)
                        val tokens = usage.sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }
                        val cost = usage.sumOf { it.costMicros ?: 0 } / 1_000_000.0
                        Hint(stringResource(R.string.ai_usage, usage.size, tokens, "%.3f".format(cost)))
                    }
                }

                Group(stringResource(R.string.appearance)) {
                    Label(stringResource(R.string.theme))
                    Chips {
                        listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark).forEach { (t, label) ->
                            ChoiceChip(s.theme == t, stringResource(label), { vm.update { it.copy(theme = t) } })
                        }
                    }
                    Divider()
                    Label(stringResource(R.string.language))
                    Chips {
                        listOf("tr" to "Türkçe", "en" to "English").forEach { (tag, label) ->
                            ChoiceChip(s.language == tag, label, {
                                if (s.language != tag) {
                                    vm.update { it.copy(language = tag) }
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                                }
                            })
                        }
                    }
                    Hint(stringResource(R.string.language_hint), Modifier.padding(top = 10.dp))
                }

                Group(stringResource(R.string.account)) {
                    if (!vm.backendConfigured) {
                        ListRow(stringResource(R.string.account_device_only), subtitle = stringResource(R.string.account_local_only), icon = Icons.Filled.CloudSync,
                            iconTint = cs.onSurfaceVariant, iconContainer = cs.surfaceContainerHigh)
                    } else if (acct == null) {
                        Hint(stringResource(R.string.account_hint), Modifier.padding(bottom = 10.dp))
                        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.email)) }, singleLine = true,
                            shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) }, singleLine = true,
                            shape = MaterialTheme.shapes.medium, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(stringResource(R.string.sign_in), { vm.signIn(email, password, false) }, Modifier.weight(1f), enabled = email.contains('@') && password.length >= 8)
                            SecondaryButton(stringResource(R.string.sign_up), { vm.signIn(email, password, true) }, Modifier.weight(1f), enabled = email.contains('@') && password.length >= 8)
                        }
                    } else {
                        Text(stringResource(R.string.signed_in_as, acct?.email ?: acct!!.userId), style = MaterialTheme.typography.titleSmall)
                        Hint(stringResource(R.string.sync_pending, pending))
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(stringResource(R.string.sync_now), vm::syncNow, Modifier.weight(1f), icon = Icons.Filled.CloudSync)
                            GhostButton(stringResource(R.string.sign_out), vm::signOut, Modifier.weight(1f))
                        }
                    }
                }

                Group(stringResource(R.string.your_data)) {
                    ListRow(stringResource(R.string.export_data), subtitle = stringResource(R.string.export_hint), icon = Icons.Filled.Download,
                        onClick = { exporter.launch("bookmind-export.zip") })
                    Divider()
                    ListRow(stringResource(R.string.delete_everything), icon = Icons.Filled.DeleteForever, iconTint = cs.error, iconContainer = cs.errorContainer,
                        onClick = { confirmDelete = true })
                }

                Group(stringResource(R.string.about)) {
                    Hint(stringResource(R.string.about_body))
                    Hint(stringResource(R.string.licenses_body), Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(32.dp))
            }
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
