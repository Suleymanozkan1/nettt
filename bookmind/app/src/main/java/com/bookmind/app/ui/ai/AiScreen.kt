package com.bookmind.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.ai.GeminiConfig
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.ContentWidth
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.IconBadge
import com.bookmind.app.ui.components.Pill
import com.bookmind.app.ui.components.SectionHeader
import com.bookmind.app.ui.theme.extended
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** What the app shows about Gemini. */
enum class AiStatus { READY, OFF, NO_KEY }

@HiltViewModel
class AiViewModel @Inject constructor(
    private val settings: SettingsRepository,
    val config: GeminiConfig,
) : ViewModel() {
    val status = settings.settings.map { s ->
        when {
            !config.configured -> AiStatus.NO_KEY
            s.aiMode == AiMode.GEMINI -> AiStatus.READY
            else -> AiStatus.OFF
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), if (config.configured) AiStatus.READY else AiStatus.NO_KEY)

    fun setEnabled(on: Boolean) = viewModelScope.launch { settings.update { it.copy(aiMode = if (on) AiMode.GEMINI else AiMode.LOCAL) } }
}

/** One-line status for the settings row. */
@Composable
fun aiStatusText(status: AiStatus, model: String): String = when (status) {
    AiStatus.READY -> stringResource(R.string.ai_status_ready, model)
    AiStatus.OFF -> stringResource(R.string.ai_status_off)
    AiStatus.NO_KEY -> stringResource(R.string.ai_status_no_key)
}

@Composable
fun AiScreen(onBack: () -> Unit, vm: AiViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    val summary = vm.config.summary
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.ai_title), onBack)
        ContentWidth(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = 40.dp)) {
            HeroCard(Modifier.fillMaxWidth().testTag("ai_hero")) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ai_hero_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    }
                    Text(stringResource(R.string.ai_hero_body), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 8.dp))
                    Pill(vm.config.model, Modifier.padding(top = 14.dp), color = cs.primary, container = Color.White)
                }
            }

            when (status) {
                AiStatus.NO_KEY -> AppCard(Modifier.fillMaxWidth().padding(top = 16.dp), color = x.warningContainer, border = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.Key, x.warning, Color.White.copy(alpha = 0.6f), size = 38.dp, iconSize = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ai_no_key_title), style = MaterialTheme.typography.titleSmall, color = x.onWarningContainer)
                            Text(stringResource(R.string.ai_no_key_body), style = MaterialTheme.typography.bodySmall, color = x.onWarningContainer)
                        }
                    }
                }
                else -> AppCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Row(Modifier.fillMaxWidth().toggleable(status == AiStatus.READY, role = Role.Switch) { vm.setEnabled(it) }.testTag("ai_toggle"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ai_use_gemini), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(if (status == AiStatus.READY) R.string.ai_use_gemini_on else R.string.ai_use_gemini_off),
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Switch(status == AiStatus.READY, null)
                    }
                }
            }

            SectionHeader(stringResource(R.string.ai_privacy_title))
            AppCard(Modifier.fillMaxWidth()) {
                InfoRow(Icons.Filled.PrivacyTip, stringResource(R.string.ai_privacy_sent))
                InfoRow(Icons.Filled.PrivacyTip, stringResource(R.string.ai_privacy_free_tier))
                InfoRow(Icons.Filled.CloudOff, stringResource(R.string.ai_privacy_off))
            }

            SectionHeader(stringResource(R.string.ai_summary_settings))
            AppCard(Modifier.fillMaxWidth()) {
                InfoRow(Icons.Filled.Tune, stringResource(R.string.ai_summary_chapter_ratio, (summary.chapterRatio * 100).roundToInt()))
                InfoRow(Icons.Filled.Tune, stringResource(R.string.ai_summary_overall, summary.overallMinWords, summary.overallMaxWords))
                InfoRow(Icons.Filled.Tune, stringResource(R.string.ai_summary_chunks, summary.chunkMinWords, summary.chunkMaxWords))
                Text(stringResource(R.string.ai_summary_config_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
