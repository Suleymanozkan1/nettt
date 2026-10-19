package com.bookmind.app.ui.ai

import android.content.Context
import android.net.ConnectivityManager
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Money
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.BuildConfig
import com.bookmind.app.R
import com.bookmind.app.ai.AiState
import com.bookmind.app.ai.ModelCatalog
import com.bookmind.app.ai.ModelManager
import com.bookmind.app.ai.ModelSpec
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.GhostButton
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroButton
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.ProgressBar
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.theme.extended
import com.bookmind.app.work.ModelDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiViewModel @Inject constructor(
    private val manager: ModelManager,
    private val settings: SettingsRepository,
    auth: com.bookmind.app.data.remote.AuthRepository,
) : ViewModel() {
    /** The paid cloud model only exists in builds made with bookmind.enableCloudAi=true. */
    val cloudAvailable = BuildConfig.CLOUD_AI_ENABLED && auth.isBackendConfigured
    val state = manager.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val mock = settings.settings.map { it.aiMode == AiMode.MOCK }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun download(spec: ModelSpec) = viewModelScope.launch { manager.download(spec) }
    fun cancel() = manager.cancelDownload()
    fun delete(spec: ModelSpec) = viewModelScope.launch { manager.delete(spec) }
    fun select(spec: ModelSpec) = viewModelScope.launch { manager.select(spec) }
    fun setEnabled(on: Boolean) = viewModelScope.launch { manager.setEnabled(on) }
    fun setMock(on: Boolean) = viewModelScope.launch { settings.update { it.copy(aiMode = if (on) AiMode.MOCK else AiMode.ON_DEVICE) } }
    fun setCloud(on: Boolean) = viewModelScope.launch { settings.update { it.copy(aiMode = if (on) AiMode.REMOTE else AiMode.ON_DEVICE) } }
    val cloud = settings.settings.map { it.aiMode == AiMode.REMOTE }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun hasRoomFor(spec: ModelSpec) = manager.hasRoomFor(spec)
}

private fun Context.bytes(n: Long): String = Formatter.formatShortFileSize(this, n)

private fun Context.isMetered(): Boolean =
    runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered }.getOrDefault(false)

@Composable
fun tierLabel(tier: ModelSpec.Tier): String = stringResource(
    when (tier) {
        ModelSpec.Tier.RECOMMENDED -> R.string.ai_tier_recommended
        ModelSpec.Tier.BEST -> R.string.ai_tier_best
        ModelSpec.Tier.LIGHT -> R.string.ai_tier_light
    },
)

/** One-line status used by the settings row and the home card. */
@Composable
fun aiStatusText(state: AiState): String = when {
    state.download is AiState.Download.Running -> stringResource(R.string.ai_status_downloading, (state.download.fraction * 100).toInt())
    state.download is AiState.Download.Waiting -> stringResource(R.string.ai_download_waiting)
    !state.enabled && state.ready -> stringResource(R.string.ai_status_off)
    state.active -> stringResource(R.string.ai_status_ready, state.selected!!.displayName)
    else -> stringResource(R.string.ai_status_not_installed)
}

@Composable
fun AiScreen(onBack: () -> Unit, vm: AiViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val mock by vm.mock.collectAsStateWithLifecycle()
    val cloud by vm.cloud.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmMetered by remember { mutableStateOf<ModelSpec?>(null) }
    val startDownload: (ModelSpec) -> Unit = { spec -> if (context.isMetered()) confirmMetered = spec else vm.download(spec) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.ai_title), onBack)
        val s = state ?: return@Column
        ContentWidth(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Gutter).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusHero(s, onDownload = { s.selected?.let(startDownload) }, onCancel = vm::cancel)

                if (s.ready) {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().toggleable(s.enabled, role = Role.Switch) { vm.setEnabled(it) }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.ai_use), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (s.enabled) stringResource(R.string.ai_ready_body, s.selected!!.displayName) else stringResource(R.string.ai_use_off_body),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(s.enabled, null)
                        }
                    }
                }

                SectionHeader(stringResource(R.string.ai_models), Modifier.padding(top = 4.dp))
                ModelCatalog.all.forEach { spec ->
                    ModelOption(spec, s, roomOk = vm.hasRoomFor(spec), onSelect = { vm.select(spec) }, onDelete = { vm.delete(spec) })
                }

                AppCard(Modifier.fillMaxWidth(), color = MaterialTheme.extended.successContainer, border = false) {
                    Text(stringResource(R.string.ai_privacy_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.extended.onSuccessContainer)
                    Spacer(Modifier.height(8.dp))
                    Bullet(Icons.Filled.CloudDownload, stringResource(R.string.ai_privacy_1))
                    Bullet(Icons.Filled.Lock, stringResource(R.string.ai_privacy_2))
                    Bullet(Icons.Filled.Money, stringResource(R.string.ai_privacy_3))
                    Bullet(Icons.Filled.WifiOff, stringResource(R.string.ai_fallback_note))
                }

                if (vm.cloudAvailable) DevToggle(Icons.Filled.Cloud, stringResource(R.string.ai_remote), stringResource(R.string.ai_mode_cloud_body), cloud, vm::setCloud)
                if (BuildConfig.DEBUG) DevToggle(Icons.Filled.Science, stringResource(R.string.ai_mode_mock), null, mock, vm::setMock)
            }
        }
    }

    confirmMetered?.let { spec ->
        AlertDialog(
            onDismissRequest = { confirmMetered = null },
            title = { Text(stringResource(R.string.ai_metered_title)) },
            text = { Text(stringResource(R.string.ai_metered_body, context.bytes(spec.bytes))) },
            confirmButton = { TextButton({ confirmMetered = null; vm.download(spec) }) { Text(stringResource(R.string.ai_download_anyway)) } },
            dismissButton = { TextButton({ confirmMetered = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StatusHero(s: AiState, onDownload: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val onHero = MaterialTheme.extended.onHero
    HeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(if (s.ready) Icons.Filled.CheckCircle else Icons.Filled.AutoAwesome, null, Modifier.size(28.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(if (s.ready) R.string.ai_ready_title else R.string.ai_hero_title),
                style = MaterialTheme.typography.headlineSmall, color = onHero,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (s.ready) stringResource(R.string.ai_ready_body, s.selected!!.displayName) else stringResource(R.string.ai_hero_body),
                style = MaterialTheme.typography.bodyMedium, color = onHero.copy(alpha = 0.85f),
            )
            val d = s.download
            when {
                d is AiState.Download.Running || d is AiState.Download.Waiting -> {
                    Spacer(Modifier.height(18.dp))
                    val running = d as? AiState.Download.Running
                    ProgressBar(running?.fraction ?: 0f, Modifier.fillMaxWidth(), color = Color.White, track = Color.White.copy(alpha = 0.25f), height = 8.dp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                running == null -> stringResource(R.string.ai_download_waiting)
                                running.verifying -> stringResource(R.string.ai_download_verifying)
                                else -> stringResource(R.string.ai_download_progress, context.bytes(running.done), context.bytes(running.total))
                            },
                            style = MaterialTheme.typography.labelLarge, color = onHero, modifier = Modifier.weight(1f),
                        )
                        if (running != null && !running.verifying) Text("${(running.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = onHero)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.ai_download_background), style = MaterialTheme.typography.bodySmall, color = onHero.copy(alpha = 0.75f))
                    GhostButton(stringResource(R.string.ai_download_cancel), onCancel, Modifier.padding(top = 4.dp), color = onHero)
                }
                !s.ready && s.selected != null -> {
                    if (d is AiState.Download.Failed) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            when (d.reason) {
                                ModelDownloadWorker.Failure.NO_SPACE -> stringResource(R.string.ai_download_failed_space, context.bytes(s.selected.bytes + s.selected.bytes / 3))
                                ModelDownloadWorker.Failure.NETWORK -> stringResource(R.string.ai_download_failed_network)
                                ModelDownloadWorker.Failure.CORRUPT -> stringResource(R.string.ai_download_failed_corrupt)
                                ModelDownloadWorker.Failure.UNKNOWN -> stringResource(R.string.ai_download_failed)
                            },
                            style = MaterialTheme.typography.bodySmall, color = onHero,
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Color.Black.copy(alpha = 0.18f)).padding(10.dp),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    HeroButton(
                        if (d is AiState.Download.Failed) stringResource(R.string.ai_retry) else stringResource(R.string.ai_download, context.bytes(s.selected.bytes)),
                        onDownload, Modifier.fillMaxWidth(), icon = Icons.Filled.CloudDownload,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ai_download_once) + " · " + s.selected.displayName,
                        style = MaterialTheme.typography.bodySmall, color = onHero.copy(alpha = 0.75f),
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelOption(spec: ModelSpec, s: AiState, roomOk: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    val context = LocalContext.current
    val selected = s.selected?.id == spec.id
    val installed = spec.id in s.installed
    val fits = ModelCatalog.fits(spec, s.ramGb)
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(if (selected) cs.primaryContainer.copy(alpha = 0.55f) else x.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else x.cardBorder, MaterialTheme.shapes.large)
            .selectable(selected, enabled = fits || installed, role = Role.RadioButton, onClick = onSelect)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.AutoAwesome, if (selected) cs.onPrimary else cs.primary, if (selected) cs.primary else cs.primaryContainer, size = 40.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
                    Pill(tierLabel(spec.tier))
                }
                Text(
                    stringResource(R.string.ai_model_meta, context.bytes(spec.bytes), spec.recommendedRamGb, spec.license),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (selected) cs.primary else cs.outline)
        }
        AnimatedVisibility(!fits && !installed) {
            Text(stringResource(R.string.ai_model_too_big, "%.1f".format(s.ramGb)), style = MaterialTheme.typography.bodySmall, color = x.warning, modifier = Modifier.padding(top = 8.dp))
        }
        if (installed) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(stringResource(R.string.ai_installed), color = x.onSuccessContainer, container = x.successContainer, icon = Icons.Filled.CheckCircle)
                Spacer(Modifier.weight(1f))
                IconButton(onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.ai_delete_model), tint = cs.onSurfaceVariant) }
            }
        } else if (selected && fits && !roomOk) {
            WarningBanner(listOf(stringResource(R.string.ai_download_failed_space, context.bytes(spec.bytes + spec.bytes / 3))), Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun DevToggle(icon: ImageVector, title: String, body: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(on, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, null)
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.extended.success)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extended.onSuccessContainer)
    }
}
