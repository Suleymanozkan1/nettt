package com.bookmind.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bookmind.app.BuildConfig
import com.bookmind.app.R
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.AppSettings
import com.bookmind.app.ui.components.ChoiceChip
import com.bookmind.app.ui.components.GhostButton
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.SecondaryButton
import com.bookmind.app.ui.components.WarningBanner
import com.bookmind.app.ui.theme.extended

/** Result of "test connection" for the computer model. */
sealed interface ComputerCheck {
    data object Idle : ComputerCheck
    data object Running : ComputerCheck
    data class Ok(val models: List<String>) : ComputerCheck
    data class Failed(val message: String) : ComputerCheck
}

@Composable
fun AiSettingsSection(
    s: AppSettings,
    installedModels: List<Pair<String, Long>>,
    ramGb: Double,
    importing: Boolean,
    check: ComputerCheck,
    cloudAvailable: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onImport: (Uri) -> Unit,
    onDeleteModel: (String) -> Unit,
    onTestComputer: (String, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Text(stringResource(R.string.ai_free_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))

    ModeOption(AiMode.LOCAL, s.aiMode, Icons.Filled.Tune, R.string.ai_mode_local, R.string.ai_mode_local_body, onUpdate)
    ModeOption(AiMode.ON_DEVICE, s.aiMode, Icons.Filled.PhoneAndroid, R.string.ai_mode_device, R.string.ai_mode_device_body, onUpdate)
    if (s.aiMode == AiMode.ON_DEVICE) OnDevicePanel(s, installedModels, ramGb, importing, onUpdate, onImport, onDeleteModel)
    ModeOption(AiMode.COMPUTER, s.aiMode, Icons.Filled.Computer, R.string.ai_mode_computer, R.string.ai_mode_computer_body, onUpdate)
    if (s.aiMode == AiMode.COMPUTER) ComputerPanel(s, check, onUpdate, onTestComputer)
    if (cloudAvailable) ModeOption(AiMode.REMOTE, s.aiMode, Icons.Filled.Wifi, R.string.ai_remote, R.string.ai_mode_cloud_body, onUpdate)
    if (BuildConfig.DEBUG) ModeOption(AiMode.MOCK, s.aiMode, Icons.Filled.Science, R.string.ai_mock, R.string.ai_mode_mock_body, onUpdate)
    if (s.aiMode == AiMode.REMOTE && !cloudAvailable) WarningBanner(listOf(stringResource(R.string.ai_cloud_removed)))
    Text(stringResource(R.string.ai_fallback_note), style = MaterialTheme.typography.bodySmall, color = x.onSuccessContainer,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium).background(x.successContainer).padding(12.dp))
}

@Composable
private fun ModeOption(mode: AiMode, current: AiMode, icon: ImageVector, title: Int, body: Int, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val selected = mode == current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium)
            .background(if (selected) cs.primaryContainer else MaterialTheme.extended.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else MaterialTheme.extended.cardBorder, MaterialTheme.shapes.medium)
            .selectable(selected, role = Role.RadioButton) { onUpdate { it.copy(aiMode = mode) } }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(icon, if (selected) cs.onPrimary else cs.primary, if (selected) cs.primary else cs.primaryContainer, size = 36.dp, iconSize = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (selected) cs.primary else cs.outline)
    }
}

@Composable
private fun OnDevicePanel(
    s: AppSettings,
    installed: List<Pair<String, Long>>,
    ramGb: Double,
    importing: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onImport: (Uri) -> Unit,
    onDelete: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }
    Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp)) {
        if (ramGb in 0.1..5.5) WarningBanner(listOf(stringResource(R.string.ai_device_low_ram, "%.1f".format(ramGb))))
        Text(stringResource(R.string.ai_device_models_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        installed.forEach { (name, bytes) ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceChip(s.onDeviceModel == name, name, { onUpdate { it.copy(onDeviceModel = name) } }, Modifier.weight(1f, fill = false))
                Text("  " + "%.0f MB".format(bytes / 1_048_576.0), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                IconButton({ onDelete(name) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = cs.onSurfaceVariant) }
            }
        }
        SecondaryButton(stringResource(if (importing) R.string.ai_device_importing else R.string.ai_device_import), { picker.launch(arrayOf("*/*")) },
            Modifier.padding(top = 8.dp), icon = Icons.Filled.FileOpen, enabled = !importing)
        Text(stringResource(R.string.ai_context_size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1_024, 2_048, 4_096).forEach { t -> ChoiceChip(s.onDeviceContextTokens == t, "$t", { onUpdate { it.copy(onDeviceContextTokens = t) } }) }
        }
    }
}

@Composable
private fun ComputerPanel(s: AppSettings, check: ComputerCheck, onUpdate: ((AppSettings) -> AppSettings) -> Unit, onTest: (String, String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    var url by remember { mutableStateOf(s.computerUrl) }
    var model by remember { mutableStateOf(s.computerModel) }
    LaunchedEffect(s.computerModel) { model = s.computerModel }
    Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp)) {
        Text(stringResource(R.string.ai_computer_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        OutlinedTextField(url, { url = it; onUpdate { s -> s.copy(computerUrl = it) } }, Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.ai_computer_url)) }, placeholder = { Text("http://192.168.1.20:11434") }, singleLine = true,
            shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(model, { model = it; onUpdate { s -> s.copy(computerModel = it) } }, Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.ai_computer_model)) }, placeholder = { Text("qwen2.5:3b") }, singleLine = true, shape = MaterialTheme.shapes.medium)
        GhostButton(stringResource(R.string.ai_computer_test), { onTest(url, model) }, Modifier.padding(top = 8.dp), enabled = url.isNotBlank() && check !is ComputerCheck.Running)
        when (check) {
            ComputerCheck.Idle -> Unit
            ComputerCheck.Running -> Text(stringResource(R.string.ai_computer_testing), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            is ComputerCheck.Failed -> Text(check.message, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 4.dp))
            is ComputerCheck.Ok -> {
                Text(stringResource(R.string.ai_computer_ok, check.models.size), style = MaterialTheme.typography.bodySmall, color = x.success, modifier = Modifier.padding(top = 4.dp))
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    check.models.forEach { m -> ChoiceChip(s.computerModel == m, m, { onUpdate { it.copy(computerModel = m) } }) }
                }
            }
        }
        Text(stringResource(R.string.ai_context_size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(4_096, 8_192, 16_384, 32_768).forEach { t -> ChoiceChip(s.computerContextTokens == t, "$t", { onUpdate { it.copy(computerContextTokens = t) } }) }
        }
    }
}
