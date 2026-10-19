package com.bookmind.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookmind.app.R
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.ui.theme.CoverPalettes
import com.bookmind.app.ui.theme.Lora
import com.bookmind.app.ui.theme.extended
import com.bookmind.core.ai.GenerationInfo
import com.bookmind.core.text.SourceRef
import kotlin.math.abs

/** Horizontal page gutter used by every screen. */
val Gutter = 20.dp

// ---------- Bars and headers ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
    )
}

/** Large page title for the main tabs, with an optional eyebrow line above and actions on the right. */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(start = Gutter, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        }
        actions()
    }
}

/** Round tonal icon button used in headers. */
@Composable
fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.extended.card, border = BorderStroke(1.dp, MaterialTheme.extended.cardBorder),
        modifier = Modifier.padding(start = 8.dp).size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        if (actionLabel != null && onAction != null) {
            Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) = SectionHeader(text, modifier)

// ---------- Surfaces ----------

/** The app's standard card: white (dark: raised) surface, soft border, generous radius. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.extended.card,
    border: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val b = if (border) BorderStroke(1.dp, MaterialTheme.extended.cardBorder) else null
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = b) { Column(Modifier.padding(contentPadding), content = content) }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = b) { Column(Modifier.padding(contentPadding), content = content) }
    }
}

/** Brand gradient card for the most important element on a screen. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, brush: Brush = MaterialTheme.extended.hero, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.clip(MaterialTheme.shapes.extraLarge).background(brush).drawBehind {
            // Soft light circles give the gradient depth without an image asset.
            drawCircle(Color.White.copy(alpha = 0.08f), radius = size.minDimension * 0.75f, center = Offset(size.width * 0.95f, size.height * 0.05f))
            drawCircle(Color.White.copy(alpha = 0.06f), radius = size.minDimension * 0.45f, center = Offset(size.width * 0.05f, size.height * 1.05f))
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.extended.onHero) { content() }
    }
}

/** Icon in a rounded tinted square. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, container: Color, modifier: Modifier = Modifier, size: Dp = 40.dp, iconSize: Dp = 22.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(iconSize), tint = tint)
    }
}

/** Small rounded label. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onPrimaryContainer, container: Color = MaterialTheme.colorScheme.primaryContainer, icon: ImageVector? = null) {
    Row(modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(14.dp), tint = color); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Selectable pill used for every "choose one/many" row (goals, lengths, modes, days). */
@Composable
fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primary else MaterialTheme.extended.card
    val fg = if (selected) cs.onPrimary else cs.onSurface
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(bg)
            .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.extended.cardBorder, CircleShape))
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = fg.copy(alpha = if (enabled) 1f else 0.4f)); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = if (enabled) 1f else 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Buttons ----------

private val ButtonHeight = 52.dp

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick, modifier.heightIn(min = ButtonHeight), enabled = enabled && !loading, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = LocalContentColor.current)
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    FilledTonalButton(onClick, modifier.heightIn(min = ButtonHeight), enabled = enabled, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary) {
    OutlinedButton(onClick, modifier.heightIn(min = ButtonHeight), enabled = enabled, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.extended.cardBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Light button for use on the hero gradient. */
@Composable
fun HeroButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Button(onClick, modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------- Inputs ----------

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(MaterialTheme.extended.card)
            .border(1.dp, MaterialTheme.extended.cardBorder, CircleShape).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, maxLines = 1)
            BasicTextField(value, onValueChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary), modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
        }
        if (value.isNotEmpty()) IconButton({ onValueChange("") }) { Icon(Icons.Filled.Close, stringResource(R.string.clear), tint = cs.onSurfaceVariant) }
    }
}

// ---------- States ----------

@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
        if (message != null) { Spacer(Modifier.height(16.dp)); Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector = Icons.AutoMirrored.Filled.MenuBook,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) { Spacer(Modifier.height(20.dp)); PrimaryButton(actionLabel, onAction) }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().padding(vertical = 8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onRetry != null) {
                Text(stringResource(R.string.retry), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(8.dp))
            }
        }
    }
}

@Composable
fun WarningBanner(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val x = MaterialTheme.extended
    Surface(color = x.warningContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Filled.Info, null, tint = x.warning, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = x.onWarningContainer) }
            }
        }
    }
}

/** Shows where content came from: an AI model, the on-device engine, or the user. */
@Composable
fun OriginBadge(info: GenerationInfo?, modifier: Modifier = Modifier) {
    val x = MaterialTheme.extended
    when {
        info == null -> Pill(stringResource(R.string.origin_user), modifier, x.onSuccessContainer, x.successContainer, Icons.Filled.EditNote)
        info.aiGenerated -> Pill(stringResource(R.string.origin_ai, info.model ?: info.engine), modifier, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer, Icons.Filled.AutoAwesome)
        else -> Pill(stringResource(R.string.origin_local), modifier, x.onInfoContainer, x.infoContainer, Icons.Filled.PhoneAndroid)
    }
}

@Composable
fun SourceChip(ref: SourceRef, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val label = ref.label(AppLanguage.tag) + if (!ref.verified) " · " + stringResource(R.string.source_unverified) else ""
    Row(
        Modifier.padding(top = 6.dp).clip(CircleShape).background(cs.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(14.dp), tint = if (ref.verified) cs.primary else cs.error)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (ref.verified) cs.onSurfaceVariant else cs.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Data display ----------

@Composable
fun StatTile(icon: ImageVector, value: String, label: String, tint: Color, container: Color, modifier: Modifier = Modifier) {
    AppCard(modifier, contentPadding = PaddingValues(14.dp)) {
        IconBadge(icon, tint, container, size = 34.dp, iconSize = 18.dp)
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null) {
    AppCard(modifier, contentPadding = PaddingValues(14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Animated ring with rounded ends. */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    stroke: Dp = 10.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(track, 0f, 360f, false, tl, arcSize, style = Stroke(s))
            if (animated > 0f) drawArc(color, -90f, 360f * animated, false, tl, arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun GoalRing(fraction: Float, label: String, modifier: Modifier = Modifier) {
    ProgressRing(fraction, modifier.semantics { contentDescription = label }) {
        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
    }
}

/** Thin rounded progress bar. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, track: Color = MaterialTheme.colorScheme.surfaceContainerHighest, height: Dp = 6.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animated).clip(CircleShape).background(color))
    }
}

/** Accessible bar chart with rounded bars; the whole chart has a text description for screen readers. */
@Composable
fun BarChart(values: List<Pair<String, Int>>, description: String, modifier: Modifier = Modifier, highlightIndex: Int? = null, height: Dp = 120.dp) {
    val max = (values.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val soft = MaterialTheme.colorScheme.primaryContainer
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width / values.size.coerceAtLeast(1)
            val barW = (w * 0.56f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, (_, v) ->
                val h = if (v == 0) 4.dp.toPx() else (size.height * v / max).coerceAtLeast(8.dp.toPx())
                drawRoundRect(
                    color = when { v == 0 -> empty; i == highlightIndex -> primary; else -> soft },
                    topLeft = Offset(i * w + (w - barW) / 2, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2.4f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            values.forEachIndexed { i, (label, _) ->
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1,
                    color = if (i == highlightIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Forgetting curve: dashed = model prediction with a soft area fill; measured points (if any) are rings. */
@Composable
fun CurveChart(predicted: List<Pair<Int, Double>>, measured: List<Pair<Double, Double>>, description: String, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Box(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val maxX = (predicted.maxOfOrNull { it.first } ?: 1).toFloat()
            fun p(x: Double, y: Double) = Offset((x / maxX * size.width).toFloat(), (size.height * (1 - y)).toFloat())
            listOf(0.25, 0.5, 0.75, 0.9).forEach { y -> drawLine(grid, p(0.0, y), p(maxX.toDouble(), y), 1.dp.toPx()) }
            if (predicted.size >= 2) {
                val path = Path().apply { predicted.forEachIndexed { i, (x, y) -> p(x.toDouble(), y).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
                val area = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), line.copy(alpha = 0.0f))))
                drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            }
            measured.forEach { (x, y) -> drawCircle(dot, 5.dp.toPx(), p(x.coerceAtMost(maxX.toDouble()), y), style = Stroke(2.5.dp.toPx())) }
        }
    }
}

// ---------- Books ----------

/**
 * A generated cover: the book's own colour (stable per id), title in a serif face, a spine shadow and a
 * small author line. Books have no cover images, so this gives each one a recognisable face.
 */
@Composable
fun BookCover(title: String, author: String?, seed: String, modifier: Modifier = Modifier) {
    val colors = remember(seed) { CoverPalettes[abs(seed.hashCode()) % CoverPalettes.size] }
    BoxWithConstraints(
        modifier
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp))
            .background(Brush.linearGradient(colors))
            .drawBehind {
                // Spine shadow and a thin highlight line.
                drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent), endX = size.width * 0.09f))
                drawLine(Color.White.copy(alpha = 0.18f), Offset(size.width * 0.075f, 0f), Offset(size.width * 0.075f, size.height), 1.dp.toPx())
                drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.7f, center = Offset(size.width * 1.05f, size.height * 0.92f))
            },
    ) {
        // Type scales with the cover so long words fit on small covers.
        val w = maxWidth.value
        val titleSize = (w * 0.115f).coerceIn(11f, 22f)
        val pad = (w * 0.11f).dp
        Column(Modifier.fillMaxSize().padding(start = pad + 2.dp, end = pad * 0.7f, top = pad, bottom = pad * 0.8f)) {
            Box(Modifier.width((w * 0.16f).dp).height(2.dp).background(Color.White.copy(alpha = 0.7f)))
            Spacer(Modifier.height((w * 0.07f).dp))
            Text(
                title,
                fontFamily = Lora, fontWeight = FontWeight.SemiBold, color = Color.White,
                fontSize = titleSize.sp, lineHeight = (titleSize * 1.22f).sp,
                maxLines = 5, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (author != null) Text(author, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = (w * 0.068f).coerceIn(8f, 12f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------- Rows ----------

/** A settings/list row: tinted icon, title, optional subtitle and trailing content. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconBadge(icon, iconTint, iconContainer, size = 38.dp, iconSize = 20.dp); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

/** Width-limited centred column so tablets and landscape phones don't stretch text across the screen. */
@Composable
fun ContentWidth(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), content = content)
    }
}

fun formatInterval(ms: Long, minutesLabel: (Long) -> String, hoursLabel: (Long) -> String, daysLabel: (Long) -> String): String = when {
    ms < 3_600_000 -> minutesLabel((ms / 60_000).coerceAtLeast(1))
    ms < 86_400_000 -> hoursLabel(ms / 3_600_000)
    else -> daysLabel(ms / 86_400_000)
}

/** Material slider without step tick dots (they read as noise on narrow screens); steps still snap. */
@Composable
fun AppSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, steps: Int, modifier: Modifier = Modifier) {
    androidx.compose.material3.Slider(
        value, onValueChange, modifier, valueRange = valueRange, steps = steps,
        colors = androidx.compose.material3.SliderDefaults.colors(
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    )
}

