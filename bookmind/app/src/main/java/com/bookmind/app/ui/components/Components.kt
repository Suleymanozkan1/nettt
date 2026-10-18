package com.bookmind.app.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bookmind.app.R
import com.bookmind.core.ai.GenerationInfo
import com.bookmind.core.text.SourceRef

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, modifier = Modifier.semantics { heading() }) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        },
        actions = { actions() },
    )
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        if (message != null) { Spacer(Modifier.height(16.dp)); Text(message, textAlign = TextAlign.Center) }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) { Spacer(Modifier.height(16.dp)); Button(onClick = onAction) { Text(actionLabel) } }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center)
        if (onRetry != null) { Spacer(Modifier.height(12.dp)); Button(onClick = onRetry) { Text(stringResource(R.string.retry)) } }
    }
}

@Composable
fun WarningBanner(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp)) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Column { lines.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer) } }
        }
    }
}

/** Shows where content came from: an AI model, the on-device engine, or the user. */
@Composable
fun OriginBadge(info: GenerationInfo?, modifier: Modifier = Modifier) {
    val (label, icon) = when {
        info == null -> stringResource(R.string.origin_user) to Icons.Filled.MenuBook
        info.aiGenerated -> stringResource(R.string.origin_ai, info.model ?: info.engine) to Icons.Filled.AutoAwesome
        else -> stringResource(R.string.origin_local) to Icons.Filled.Memory
    }
    AssistChip(onClick = {}, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) }, modifier = modifier)
}

@Composable
fun SourceChip(ref: SourceRef, onClick: (() -> Unit)? = null) {
    val label = ref.label() + if (!ref.verified) " · " + stringResource(R.string.source_unverified) else ""
    AssistChip(
        onClick = { onClick?.invoke() },
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        colors = if (ref.verified) AssistChipDefaults.assistChipColors() else AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Simple accessible bar chart: the whole chart has a text description for screen readers. */
@Composable
fun BarChart(values: List<Pair<String, Int>>, description: String, modifier: Modifier = Modifier, highlightIndex: Int? = null) {
    val max = (values.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width / values.size.coerceAtLeast(1)
            values.forEachIndexed { i, (_, v) ->
                val h = size.height * v / max
                drawRect(
                    color = if (i == highlightIndex) primary else secondary,
                    topLeft = Offset(i * w + w * 0.15f, size.height - h),
                    size = Size(w * 0.7f, h.coerceAtLeast(2f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            values.forEach { (label, _) -> Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1) }
        }
    }
}

/** Forgetting curve: dashed = model prediction. Measured points (if any) are drawn as dots. */
@Composable
fun CurveChart(predicted: List<Pair<Int, Double>>, measured: List<Pair<Double, Double>>, description: String, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Box(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val maxX = (predicted.maxOfOrNull { it.first } ?: 1).toFloat()
            fun p(x: Double, y: Double) = Offset((x / maxX * size.width).toFloat(), (size.height * (1 - y)).toFloat())
            listOf(0.25, 0.5, 0.75, 0.9).forEach { y -> drawLine(grid, p(0.0, y), p(maxX.toDouble(), y), 1f) }
            for (i in 1 until predicted.size) {
                drawLine(line, p(predicted[i - 1].first.toDouble(), predicted[i - 1].second), p(predicted[i].first.toDouble(), predicted[i].second), 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            }
            measured.forEach { (x, y) -> drawCircle(dot, 7f, p(x.coerceAtMost(maxX.toDouble()), y), style = Stroke(3f)) }
        }
    }
}

@Composable
fun GoalRing(fraction: Float, label: String, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.size(96.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            drawArc(track, 0f, 360f, false, style = Stroke(12f))
            drawArc(color, -90f, 360f * fraction, false, style = Stroke(12f))
        }
        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
    }
}

fun formatInterval(ms: Long, minutesLabel: (Long) -> String, hoursLabel: (Long) -> String, daysLabel: (Long) -> String): String = when {
    ms < 3_600_000 -> minutesLabel((ms / 60_000).coerceAtLeast(1))
    ms < 86_400_000 -> hoursLabel(ms / 3_600_000)
    else -> daysLabel(ms / 86_400_000)
}
