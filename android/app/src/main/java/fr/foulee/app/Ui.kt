package fr.foulee.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 12) =
    Text(text, modifier, color = LocalPal.current.muted, fontSize = size.sp, lineHeight = (size + 5).sp)

/** A section title that folds the cards under it: the page stays short, the section is one tap away. */
@Composable
fun FoldSection(title: String, subtitle: String? = null, openWhen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { androidx.compose.runtime.mutableStateOf(false) }
    // [openWhen] (a sync starts, say) unfolds the section by itself; folding it again by hand stays possible
    androidx.compose.runtime.LaunchedEffect(openWhen) { if (openWhen) open = true }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = LocalPal.current.text)
                if (subtitle != null) Muted(subtitle, Modifier.padding(top = 4.dp), size = 14)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Replier" else "Déplier", tint = LocalPal.current.muted)
        }
        if (open) Column(Modifier.padding(top = 16.dp)) { content() }
    }
}

/** A card that is folded until tapped: the page stays short, the detail is one tap away. */
@Composable
fun FoldCard(title: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { androidx.compose.runtime.mutableStateOf(initiallyOpen) }
    Card(
        title = title, onClick = { open = !open },
        action = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Replier" else "Déplier", tint = LocalPal.current.muted) },
    ) { if (open) content() }
}

/** rounded-2xl card with a thin border, like the web cards. */
@Composable
fun Card(modifier: Modifier = Modifier, title: String? = null, action: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null, highlight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(20.dp)) {
            if (title != null || action != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, color = pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    action?.invoke()
                }
            }
            content()
        }
    }
    val shape = RoundedCornerShape(16.dp)
    val border = BorderStroke(if (highlight) 1.5.dp else 1.dp, if (highlight) pal.accent else pal.border)
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = pal.card, border = border, content = body)
    else Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = pal.card, border = border, content = body)
}

@Composable
fun Tile(
    icon: ImageVector, label: String, value: String, unit: String?, footer: String, modifier: Modifier = Modifier, open: Boolean? = null, onClick: (() -> Unit)? = null,
    tint: Color = LocalPal.current.accent, progress: Float? = null,
) {
    val pal = LocalPal.current
    Card(modifier, onClick = onClick, highlight = open == true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(17.dp), tint = tint)
                }
                Text(label, Modifier.padding(start = 10.dp), color = pal.muted, fontSize = 14.sp)
            }
            if (open != null) Icon(Icons.Filled.ExpandMore, null, Modifier.size(18.dp).rotate(if (open) 180f else 0f), tint = pal.muted)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = tint)
            if (unit != null) Text(unit, Modifier.padding(start = 4.dp, bottom = 4.dp), color = pal.muted)
        }
        if (progress != null) ProgressBar(progress, tint, Modifier.fillMaxWidth().padding(top = 8.dp))
        Muted(footer, Modifier.padding(top = 8.dp))
    }
}

/** Filter chip with the web look: card background, thin border, accent when selected. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(containerColor = pal.card, labelColor = pal.muted, selectedContainerColor = pal.accent.copy(alpha = 0.12f), selectedLabelColor = pal.accent),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = pal.border, selectedBorderColor = pal.accent.copy(alpha = 0.4f)),
    )
}

/** The short text under the greeting, as a tinted card with an accent bar. */
@Composable
fun Headline(text: String) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.accent.copy(alpha = 0.08f)).height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(pal.accent))
        Icon(Icons.Filled.AutoAwesome, null, Modifier.padding(start = 14.dp, top = 14.dp).size(18.dp), tint = pal.accent)
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 14.dp), color = pal.text, lineHeight = 22.sp, fontSize = 15.sp)
    }
}

/** GPS track fitted in the box (no map tiles), start in green and finish in red. */
@Composable
fun TrackMap(track: List<Pair<Double, Double>>, modifier: Modifier = Modifier, height: Dp = 220.dp) {
    val pal = LocalPal.current
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(pal.track)) {
        if (track.size < 2) return@Canvas
        val lat0 = track.minOf { it.first }
        val lat1 = track.maxOf { it.first }
        val lon0 = track.minOf { it.second }
        val lon1 = track.maxOf { it.second }
        // Longitude degrees get shorter away from the equator
        val kx = Math.cos(Math.toRadians((lat0 + lat1) / 2))
        val w = max((lon1 - lon0) * kx, 1e-6)
        val h = max(lat1 - lat0, 1e-6)
        val pad = 16.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h).toFloat()
        val offX = (size.width - (w * scale).toFloat()) / 2
        val offY = (size.height - (h * scale).toFloat()) / 2
        fun point(p: Pair<Double, Double>) = Offset(offX + ((p.second - lon0) * kx * scale).toFloat(), offY + ((lat1 - p.first) * scale).toFloat())
        val path = Path()
        track.forEachIndexed { i, p -> if (i == 0) path.moveTo(point(p).x, point(p).y) else path.lineTo(point(p).x, point(p).y) }
        drawPath(path, pal.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color.White, 7.dp.toPx(), point(track.first()))
        drawCircle(pal.good, 5.dp.toPx(), point(track.first()))
        drawCircle(Color.White, 7.dp.toPx(), point(track.last()))
        drawCircle(pal.bad, 5.dp.toPx(), point(track.last()))
    }
}

@Composable
fun StatusDot(status: String) {
    val pal = LocalPal.current
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (status == "good") pal.good else if (status == "low") pal.warn else pal.neutral))
}

@Composable
fun Avatar(photo: String?, name: String?, size: Dp = 48.dp) {
    val pal = LocalPal.current
    Box(Modifier.size(size).clip(CircleShape).background(pal.accent.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
        Text(name?.firstOrNull()?.uppercase() ?: "?", color = pal.accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (photo != null) AsyncImage(model = photo, contentDescription = null, modifier = Modifier.size(size))
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Box(modifier.height(10.dp).clip(RoundedCornerShape(50)).background(pal.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

class Series(val values: List<Double?>, val color: Color, val fill: Boolean = false)

/**
 * Line chart on a shared horizontal axis. Tap or drag to pick a point when [onSelect] is given.
 * [labels] are (index, text) pairs drawn under the axis.
 */
@Composable
fun LineChart(series: List<Series>, min: Double, max: Double, labels: List<Pair<Int, String>>, modifier: Modifier = Modifier, height: Dp = 150.dp, selected: Int? = null, topLabel: String? = null, onSelect: ((Int) -> Unit)? = null) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    val n = series.maxOfOrNull { it.values.size } ?: 0
    val pick = if (onSelect == null) Modifier else Modifier
        .pointerInput(n) {
            detectTapGestures { o -> onSelect(((o.x - 12.dp.toPx()) / max((size.width - 24.dp.toPx()) / max(n - 1, 1), 1f)).roundToInt().coerceIn(0, n - 1)) }
        }
        .pointerInput(n) {
            detectHorizontalDragGestures { change, _ -> onSelect(((change.position.x - 12.dp.toPx()) / max((size.width - 24.dp.toPx()) / max(n - 1, 1), 1f)).roundToInt().coerceIn(0, n - 1)) }
        }
    Canvas(modifier.fillMaxWidth().height(height).then(pick)) {
        if (n == 0) return@Canvas
        val padX = 12.dp.toPx()
        val top = (if (topLabel != null) 24 else 12).dp.toPx()
        val bottom = 22.dp.toPx()
        val step = (size.width - 2 * padX) / max(n - 1, 1)
        val range = if (max > min) max - min else 1.0
        fun x(i: Int) = padX + i * step
        fun y(v: Double) = top + (1 - ((v - min) / range).toFloat().coerceIn(0f, 1f)) * (size.height - top - bottom)

        drawLine(pal.border, Offset(padX, y(min)), Offset(size.width - padX, y(min)), 1.dp.toPx())
        drawLine(pal.border, Offset(padX, y(max)), Offset(size.width - padX, y(max)), 1.dp.toPx())
        for (s in series) {
            // One continuous run of points at a time: a gap in the data must not be bridged by the fill
            val line = Path()
            var from = -1
            fun closeRun(to: Int) {
                if (from < 0) return
                if (s.fill && to > from) {
                    val area = Path()
                    area.moveTo(x(from), y(min))
                    for (j in from..to) area.lineTo(x(j), y(s.values[j]!!))
                    area.lineTo(x(to), y(min))
                    area.close()
                    drawPath(area, s.color.copy(alpha = 0.12f))
                }
                from = -1
            }
            s.values.forEachIndexed { i, v ->
                if (v == null) { closeRun(i - 1); return@forEachIndexed }
                if (from < 0) { line.moveTo(x(i), y(v)); from = i } else line.lineTo(x(i), y(v))
            }
            closeRun(s.values.lastIndex)
            drawPath(line, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        selected?.let { i ->
            drawLine(pal.neutral, Offset(x(i), top), Offset(x(i), y(min)), 1.dp.toPx())
            series.firstOrNull()?.values?.getOrNull(i)?.let { drawCircle(series[0].color, 5.dp.toPx(), Offset(x(i), y(it))) }
        }
        val style = TextStyle(color = pal.muted, fontSize = 11.sp)
        topLabel?.let { drawText(measurer.measure(it, style), topLeft = Offset(padX, 2.dp.toPx())) }
        for ((i, text) in labels) {
            val layout = measurer.measure(text, style)
            val left = (x(i) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(left, size.height - bottom + 4.dp.toPx()))
        }
    }
}

@Composable
fun Section(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = LocalPal.current.text)
        if (subtitle != null) Muted(subtitle, Modifier.padding(top = 4.dp), size = 14)
    }
}

@Composable
fun Clickable(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(modifier.clickable(onClick = onClick)) { content() }
