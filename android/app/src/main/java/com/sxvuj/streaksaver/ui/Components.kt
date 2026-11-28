package com.sxvuj.streaksaver.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sxvuj.streaksaver.ui.theme.Fit
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// --- Layout --------------------------------------------------------------------------------------

/** The big bold title at the top of a screen, like "Today" in the Fitbit app. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** A large rounded card with an optional bold title. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(
            Modifier
                .animateContentSize(Motion.sizeSpec)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

/** A settings row with a bold title, an optional explanation and a switch. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A tappable settings row: bold title, explanation and a chevron-less action label. */
@Composable
fun ActionRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    good: Boolean = true,
    action: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            val subtitleColor by animateColorAsState(
                if (good) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                Motion.defaultEffects(),
                label = "actionSubtitle",
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
            )
        }
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A big number with a caption on a colored tile. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AnimatedNumberText(value, style = MaterialTheme.typography.headlineSmall, color = content)
        Text(label, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.85f))
    }
}

/** A small rounded label such as "All sent" or "Skipped". Color and text changes glide. */
@Composable
fun StatusPill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(container, Motion.defaultEffects(), label = "pillBg")
    val fg by animateColorAsState(content, Motion.defaultEffects(), label = "pillFg")
    AnimatedContent(
        targetState = text,
        modifier = modifier
            .clip(CircleShape)
            .drawBehind { drawRect(bg) },
        transitionSpec = { rollingText() },
        label = "pillText",
    ) { label ->
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            color = fg,
        )
    }
}

/**
 * Two or three exclusive options in one rounded track. The filled indicator slides between them
 * on a spring and the labels recolor as it passes, like the toggles in Google's apps.
 */
@Composable
fun SegmentedPills(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = 4.dp
    val position by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = Motion.fastSpatial(),
        label = "segment",
    )
    val indicator = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(gap),
    ) {
        val count = options.size.coerceAtLeast(1)
        val segment = (maxWidth - gap * (count - 1)) / count
        // The sliding pill, drawn behind the labels.
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val w = segment.toPx()
                    val left = position * (w + gap.toPx())
                    drawRoundRect(
                        color = indicator,
                        topLeft = Offset(left, 0f),
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { index, label ->
                val on = index == selected
                // How much of the indicator currently covers this label, 0..1, for a smooth recolor.
                val cover = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .pressScale(source, 0.94f)
                        .clip(CircleShape)
                        .selectable(
                            selected = on,
                            interactionSource = source,
                            indication = LocalIndication.current,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = lerp(
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.onPrimary,
                            cover,
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Shows a number that counts up from zero the first time it appears and glides to new values
 * after that. Text that does not start with a number (like "-") is shown as is. Tabular figures
 * keep the width steady while the digits change.
 */
@Composable
fun AnimatedNumberText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val match = remember(text) { NUMBER_PREFIX.find(text) }
    val target = match?.groupValues?.get(1)?.toIntOrNull()
    if (match == null || target == null) {
        Text(text, modifier, style = style, color = color)
        return
    }
    val suffix = match.groupValues[2]
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) {
        shown.animateTo(target.toFloat(), tween(durationMillis = 900, easing = Motion.EmphasizedDecelerate))
    }
    Text(
        "${shown.value.roundToInt()}$suffix",
        modifier,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = color,
    )
}

private val NUMBER_PREFIX = Regex("^(\\d+)(.*)$")

// --- Buttons: full pills with heavy labels -------------------------------------------------------

private val PillPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

@Composable
private fun PillLabel(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    // "Check" to "Checking", "Stop" to "Stopping": the new label rolls in and the pill resizes smoothly.
    AnimatedContent(targetState = text, transitionSpec = { rollingText() }, label = "pillLabel") { label ->
        Text(label, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .pressScale(source)
            .height(52.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = colors ?: ButtonDefaults.buttonColors(),
        contentPadding = PillPadding,
        interactionSource = source,
    ) { PillLabel(text, icon) }
}

@Composable
fun PillTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .pressScale(source)
            .height(52.dp),
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PillPadding,
        interactionSource = source,
    ) { PillLabel(text, icon) }
}

@Composable
fun PillOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .pressScale(source)
            .height(52.dp),
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PillPadding,
        interactionSource = source,
    ) { PillLabel(text, icon) }
}

// --- Gauge, badges and avatars -------------------------------------------------------------------

/**
 * The thick 270 degree gauge from the Fitbit app. The arc opens at the bottom. It sweeps up from
 * empty when it first appears, follows changes on a soft spring, and gives a little celebratory
 * bounce when it reaches 100%.
 */
@Composable
fun GaugeRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 168.dp,
    thickness: Dp = 18.dp,
    color: Color = Fit.colors.gauge,
    track: Color = Fit.colors.gaugeTrack,
    content: @Composable () -> Unit,
) {
    val target = progress.coerceIn(0f, 1f)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(target) {
        sweep.animateTo(target, spring(dampingRatio = 0.85f, stiffness = 60f))
    }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(target >= 1f) {
        if (target >= 1f) {
            delay(500)
            pop.animateTo(1.06f, Motion.fastEffects())
            pop.animateTo(1f, Motion.bouncy())
        }
    }
    Box(
        modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            // Read here, in the draw phase, so the sweep animation only redraws the arc.
            val shown = sweep.value.coerceIn(0f, 1f)
            val stroke = thickness.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (shown > 0f) {
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = 270f * shown,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arc,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** A rounded scalloped badge outline, the shape Fitbit uses for achievements. */
class ScallopShape(private val lobes: Int = 12, private val depth: Float = 0.07f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(cx, cy)
        val steps = 240
        val path = Path()
        for (i in 0..steps) {
            val t = (i.toFloat() / steps) * (2f * PI.toFloat())
            val r = radius * (1f - depth + depth * cos(lobes * t))
            val x = cx + r * cos(t)
            val y = cy + r * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** Round avatar for a friend, scalloped teal badge for a group, with the first letter of the name. */
@Composable
fun Avatar(name: String, isGroup: Boolean, modifier: Modifier = Modifier, diameter: Dp = 48.dp) {
    val container = if (isGroup) Fit.colors.tealContainer else MaterialTheme.colorScheme.primaryContainer
    val onContainer = if (isGroup) Fit.colors.onTealContainer else MaterialTheme.colorScheme.onPrimaryContainer
    val shape: Shape = if (isGroup) ScallopShape() else CircleShape
    Box(
        modifier
            .size(diameter)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().trimStart('@').take(1).uppercase().ifEmpty { "?" },
            color = onContainer,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** Centered placeholder for an empty list. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val idle = rememberInfiniteTransition(label = "emptyBadge")
        val turn by idle.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing)),
            label = "turn",
        )
        val breathe by idle.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(1_800, easing = Motion.Emphasized), RepeatMode.Reverse),
            label = "breathe",
        )
        Box(
            Modifier
                .size(72.dp)
                .graphicsLayer {
                    rotationZ = turn
                    scaleX = breathe
                    scaleY = breathe
                }
                .clip(ScallopShape(lobes = 10, depth = 0.08f))
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

// --- Formatting ----------------------------------------------------------------------------------

fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun formatTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

fun formatDayLong(ms: Long): String = DateFormat.getDateInstance(DateFormat.FULL).format(Date(ms))

/** "5 min ago", "in 3 h 20 min", "just now". */
fun relativeTime(targetMs: Long, nowMs: Long): String {
    val diff = targetMs - nowMs
    val abs = kotlin.math.abs(diff)
    val minutes = abs / 60_000L
    val text = when {
        minutes < 1 -> return "just now"
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0L) "$h h" else "$h h $m min"
        }
        else -> "${minutes / (24 * 60)} d"
    }
    return if (diff >= 0) "in $text" else "$text ago"
}

fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

fun formatClock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
