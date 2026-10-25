package fr.foulee.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class Zone(val to: Double, val label: String, val color: Color)

private class Explanation(
    val question: String, val use: String, val source: String, val from: (HealthMetric) -> Double, val zones: (HealthMetric, Pal) -> List<Zone>, val inputs: List<String>,
)

private val greenStrong = Color(0xFF059669)
private val sky = Color(0xFF7DD3FC)

/** What each indicator answers, its scale and what it is made of, shown by the "i" button. */
private val explanations = mapOf(
    "recovery" to Explanation(
        "Prêt pour une séance dure ?", "Savoir si tu peux faire une séance dure.", "Ta nuit, comparée à tes 30 derniers jours.", { 20.0 },
        { _, p -> listOf(Zone(38.0, "À ménager", p.bad), Zone(52.0, "Fatigué", p.warn), Zone(66.0, "Correct", p.neutral), Zone(82.0, "Prêt", p.good), Zone(100.0, "Top", greenStrong)) },
        listOf("VFC", "Sommeil", "FC au repos", "Body Battery", "Stress", "Charge", "vs tes 30 derniers jours"),
    ),
    "sleep" to Explanation(
        "Tu dors assez en ce moment ?", "Récupérer : le corps assimile l'entraînement en dormant.", "Montre au poignet, 7 dernières nuits.", { 5.0 },
        { _, p -> listOf(Zone(6.5, "Court", p.warn), Zone(7.5, "Correct", p.neutral), Zone(9.0, "Bon", p.good)) },
        listOf("Durée", "Score Garmin", "7 dernières nuits", "vs 60 nuits d'avant"),
    ),
    "hrv" to Explanation(
        "Ton système nerveux est reposé ?", "Repérer fatigue et stress avant de les sentir.", "Écart entre deux battements, la nuit.", { m -> ((m.band?.first ?: m.numeric) * 0.75).roundToInt().toDouble() },
        { m, p -> listOf(Zone(m.band?.first ?: m.numeric, "Bas", p.warn), Zone(m.band?.second ?: m.numeric, "Ta normale", p.neutral), Zone(((m.band?.second ?: m.numeric) * 1.25).roundToInt().toDouble(), "Haut", p.good)) },
        listOf("VFC de la nuit", "7 dernières nuits", "vs 60 nuits", "FC au repos"),
    ),
    "fitness" to Explanation(
        "Plus rapide à même effort ?", "Voir si ta forme monte, sans test.", "Allure des footings ramenée à 150 bpm.", { -15.0 },
        { _, p -> listOf(Zone(-3.0, "En baisse", p.warn), Zone(3.0, "Stable", p.neutral), Zone(15.0, "En progrès", p.good)) },
        listOf("Footings 30 min à 2 h", "FC 130 à 165", "Allure à 150 bpm", "4 semaines vs 8 d'avant"),
    ),
    "fatigue" to Explanation(
        "Ta charge est raisonnable ?", "Éviter d'en faire trop d'un coup.", "Charge de la semaine contre ton mois.", { 0.0 },
        { _, p -> listOf(Zone(3.0, "Frais", sky), Zone(7.0, "Équilibré", p.good), Zone(8.5, "Élevé", p.warn), Zone(10.0, "Trop", p.bad)) },
        listOf("Charge 7 jours", "vs semaine moyenne du mois"),
    ),
)

private val icons: Map<String, ImageVector> = mapOf(
    "recovery" to Icons.Filled.BatteryChargingFull, "sleep" to Icons.Filled.Bedtime, "hrv" to Icons.Filled.MonitorHeart,
    "fitness" to Icons.Filled.TrendingUp, "fatigue" to Icons.Filled.FitnessCenter,
)

/** Value placed on the scale of the explanation, as the user reads it (s/km gained for the fitness). */
private fun scaleValue(m: HealthMetric): String =
    if (m.key == "fitness") (if (m.numeric == 0.0) "=" else "${if (m.numeric > 0) "+" else "−"}${Math.abs(m.numeric).roundToInt()} s/km")
    else m.value + (m.unit?.takeIf { !it.startsWith("/") }?.let { " $it" } ?: "")

/** Value of one point of the trend, in the unit of the metric. */
private fun formatPoint(key: String, v: Double): String = when (key) {
    "recovery" -> "${v.roundToInt()}/100"
    "sleep" -> (v * 60).roundToInt().let { "${it / 60} h ${(it % 60).toString().padStart(2, '0')}" }
    "hrv" -> "${v.roundToInt()} ms"
    "fitness" -> "${Fmt.clock(v)}/km"
    else -> "${Fmt.num(v)}/10"
}

/** Colored zones with their name, and a marker on today's value. */
@Composable
private fun Scale(m: HealthMetric) {
    val pal = LocalPal.current
    val e = explanations.getValue(m.key)
    val start = e.from(m)
    val zones = e.zones(m, pal)
    val end = zones.last().to
    fun pct(v: Double) = ((min(max(v, start), end) - start) / (end - start)).toFloat()
    val widths = zones.mapIndexed { k, z -> ((z.to - (if (k > 0) zones[k - 1].to else start)) / (end - start)).toFloat().coerceAtLeast(0.02f) }
    Column(Modifier.padding(top = 12.dp)) {
        BoxWithMarker(pct(m.numeric), scaleValue(m)) {
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                zones.forEachIndexed { k, z -> Box(Modifier.weight(widths[k]).height(10.dp).background(z.color)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            zones.forEachIndexed { k, z -> Text(z.label, Modifier.weight(widths[k]), fontSize = 11.sp, color = pal.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1) }
        }
    }
}

/** The bar with the value above it and a thin marker on it. */
@Composable
private fun BoxWithMarker(fraction: Float, text: String, bar: @Composable () -> Unit) {
    val pal = LocalPal.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val total = maxWidth
        val markerX = total * fraction
        Column {
            Box(Modifier.fillMaxWidth().height(22.dp)) {
                val textX = (markerX - 40.dp).coerceIn(0.dp, total - 80.dp)
                Text(text, Modifier.padding(start = textX).width(80.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.text, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Box { bar(); Box(Modifier.offset(x = (markerX - 2.dp).coerceAtLeast(0.dp), y = (-4).dp).width(4.dp).height(18.dp).clip(RoundedCornerShape(50)).background(pal.text)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoPanel(m: HealthMetric) {
    val pal = LocalPal.current
    val e = explanations.getValue(m.key)
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.track).padding(14.dp)) {
        Text(e.question, fontWeight = FontWeight.SemiBold, color = pal.text)
        Scale(m)
        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            e.inputs.forEach { Text(it, Modifier.clip(RoundedCornerShape(6.dp)).background(pal.card).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 11.sp, color = pal.muted) }
        }
        Row(Modifier.padding(top = 12.dp)) { Text("Sert à", Modifier.width(56.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.text); Muted(e.use) }
        Row(Modifier.padding(top = 4.dp)) { Text("Mesure", Modifier.width(56.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.text); Muted(e.source) }
    }
}

/** Trend line without axis: the card gives the value, the line where it goes. The picked day is shared by all the cards. */
@Composable
private fun Sparkline(m: HealthMetric, hoverDate: String?, onHover: (String?) -> Unit) {
    val pal = LocalPal.current
    val values = m.series.map { it.second }
    val known = values.filterNotNull()
    if (known.size < 2) return
    val lo = known.min()
    val hi = known.max()
    val span = if (hi - lo == 0.0) 1.0 else hi - lo
    val n = values.size
    val index = hoverDate?.let { d -> m.series.indexOfFirst { it.first == d } } ?: -1
    val point = if (index >= 0) m.series[index] else null
    Column(Modifier.padding(top = 10.dp)) {
        Box(Modifier.height(18.dp)) {
            point?.let {
                Row {
                    Muted("${Fmt.dayMonth(LocalDate.parse(it.first))} : ", size = 12)
                    Text(it.second?.let { v -> formatPoint(m.key, v) } ?: "pas de donnée", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                }
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(44.dp)
                .pointerInput(n) { detectTapGestures(onTap = { o -> onHover(m.series[((o.x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)].first) }) }
                .pointerInput(n) { detectHorizontalDragGestures(onDragEnd = { onHover(null) }, onDragCancel = { onHover(null) }) { c, _ -> onHover(m.series[((c.position.x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)].first) } },
        ) {
            fun x(i: Int) = i.toFloat() / max(n - 1, 1) * size.width
            fun y(v: Double): Float {
                val t = ((v - lo) / span).toFloat()
                // Pace (lower is better) is drawn upside down, so up always means better
                return size.height * (0.08f + (if (m.inverted) t else 1 - t) * 0.84f)
            }
            val path = Path()
            var started = false
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                // A missing day is bridged rather than cutting the line
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            drawPath(path, pal.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (index >= 0) {
                drawLine(pal.neutral, Offset(x(index), 0f), Offset(x(index), size.height), 1.dp.toPx())
                values[index]?.let { v -> drawCircle(Color.White, 6.dp.toPx(), Offset(x(index), y(v))); drawCircle(pal.accent, 4.dp.toPx(), Offset(x(index), y(v))) }
            }
        }
    }
}

@Composable
private fun MetricCard(m: HealthMetric, hoverDate: String?, onHover: (String?) -> Unit) {
    val pal = LocalPal.current
    var info by remember { mutableStateOf(false) }
    Card {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icons.getValue(m.key), null, Modifier.size(16.dp), tint = pal.muted)
                Text(m.label, Modifier.padding(start = 8.dp), color = pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Filled.Info, "Comment lire : ${m.label}", Modifier.padding(start = 6.dp).size(20.dp).clip(RoundedCornerShape(50)).clickable { info = !info }.padding(2.dp), tint = if (info) pal.accent else pal.neutral)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(m.status)
                Text(m.statusLabel, Modifier.padding(start = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = pal.muted)
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(m.value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
            m.unit?.let { Text(it, Modifier.padding(start = 6.dp, bottom = 4.dp), color = pal.muted, fontSize = 14.sp) }
        }
        m.delta?.let { Muted(it) }
        Text(m.sentence, Modifier.padding(top = 8.dp), color = pal.muted, fontSize = 13.sp, lineHeight = 18.sp)
        if (info) InfoPanel(m)
        Sparkline(m, hoverDate, onHover)
    }
}

/* ---------- Raw data of the watch ---------- */

private class Bucket(val key: String, val label: String, val days: List<DailyRow>)

private fun bucketize(days: List<DailyRow>, weekly: Boolean): List<Bucket> {
    if (!weekly) return days.map { Bucket(it.date, Fmt.dayMonth(LocalDate.parse(it.date)), listOf(it)) }
    return days.groupBy { LocalDate.parse(it.date).with(DayOfWeek.MONDAY).toString() }.map { (monday, list) -> Bucket(monday, Fmt.dayMonth(LocalDate.parse(monday)), list) }.sortedBy { it.key }
}

private fun avg(b: Bucket, pick: (DailyRow) -> Double?): Double? = b.days.mapNotNull(pick).takeIf { it.isNotEmpty() }?.average()

private class BarItem(val label: String, val parts: List<Double>, val base: Double = 0.0, val display: String)

/** Stacked bars (or floating bars with a [BarItem.base]); tap or drag to read one, the readout is above. */
@Composable
private fun BarsChart(bars: List<BarItem>, colors: List<Color>, height: androidx.compose.ui.unit.Dp = 150.dp) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    var selected by remember(bars.size) { mutableIntStateOf(-1) }
    val n = bars.size
    if (n == 0) return
    Text(bars.getOrNull(selected)?.let { "${it.label} : ${it.display}" } ?: " ", Modifier.padding(bottom = 4.dp), fontSize = 12.sp, color = pal.text, fontWeight = FontWeight.Medium, minLines = 1)
    val lo = bars.minOf { it.base }.let { if (it > 0) it * 0.9 else 0.0 }
    val hi = bars.maxOf { it.base + it.parts.sum() }.takeIf { it > lo } ?: (lo + 1)
    Canvas(
        Modifier.fillMaxWidth().height(height)
            .pointerInput(n) { detectTapGestures { o -> selected = ((o.x / size.width) * n).toInt().coerceIn(0, n - 1) } }
            .pointerInput(n) { detectHorizontalDragGestures { c, _ -> selected = ((c.position.x / size.width) * n).toInt().coerceIn(0, n - 1) } },
    ) {
        val bottom = 20.dp.toPx()
        val step = size.width / n
        val bw = max(step * 0.7f, 1f)
        fun y(v: Double) = ((size.height - bottom) * (1 - ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)))
        drawLine(pal.border, Offset(0f, size.height - bottom), Offset(size.width, size.height - bottom), 1.dp.toPx())
        bars.forEachIndexed { i, b ->
            var acc = b.base
            b.parts.forEachIndexed { k, p ->
                if (p > 0) drawRect(colors[k % colors.size].copy(alpha = if (selected < 0 || selected == i) 1f else 0.45f), Offset(i * step + (step - bw) / 2, y(acc + p)), Size(bw, y(acc) - y(acc + p)))
                acc += p
            }
        }
        val style = TextStyle(color = pal.muted, fontSize = 11.sp)
        listOf(0, n / 2, n - 1).distinct().forEach { i ->
            val layout = measurer.measure(bars[i].label, style)
            drawText(layout, topLeft = Offset(((i + 0.5f) * step - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width), size.height - bottom + 4.dp.toPx()))
        }
    }
}

@Composable
private fun RawData(daily: List<DailyRow>, days: Int) {
    val pal = LocalPal.current
    val since = LocalDate.now().minusDays(days - 1L).toString()
    val rows = daily.filter { it.date >= since }
    val weekly = days > 90
    val per = if (weekly) " (moyenne hebdo)" else ""
    val buckets = bucketize(rows, weekly)
    if (buckets.isEmpty()) {
        Card { Muted("Aucune donnée de santé synchronisée.", size = 14) }
        return
    }
    val sleepColors = listOf(Color(0xFF2A78D6), Color(0xFF1BAF7A), Color(0xFF6250D6), Color(0xFFE87BA4))
    val sleepNames = listOf("profond", "léger", "paradoxal", "éveillé")
    Card(title = "Sommeil$per") {
        BarsChart(
            buckets.map { b ->
                val parts = listOf<(DailyRow) -> Double?>({ it.deepSleep }, { it.lightSleep }, { it.remSleep }, { it.awakeSleep }).map { p -> avg(b, p) ?: 0.0 }
                val total = parts.sum()
                val score = avg(b) { it.sleepScore }
                BarItem(b.label, parts, 0.0, if (total > 0) "${Txt.hours(total)}${score?.let { " · score ${it.roundToInt()}" } ?: ""} (${parts.mapIndexed { i, v -> "${sleepNames[i]} ${Txt.hours(v)}" }.joinToString(", ")})" else "–")
            },
            sleepColors, 180.dp,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            sleepNames.forEachIndexed { i, name -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(sleepColors[i])); Muted(name.replaceFirstChar { it.uppercase() }, Modifier.padding(start = 4.dp)) } }
        }
    }
    Card(title = "Body Battery$per") {
        BarsChart(
            buckets.mapNotNull { b ->
                val high = avg(b) { it.bodyBatteryHigh }
                val low = avg(b) { it.bodyBatteryLow }
                if (high == null || low == null) null else BarItem(b.label, listOf(max(high - low, 0.5)), low, "de ${low.roundToInt()} à ${high.roundToInt()}")
            },
            listOf(pal.good), 150.dp,
        )
    }
    Card(title = "VFC nocturne$per") {
        val values = buckets.map { avg(it) { r -> r.hrvNight } }
        if (values.filterNotNull().size >= 2) LineChart(listOf(Series(values, pal.accent)), values.filterNotNull().min() - 2, values.filterNotNull().max() + 2, listOf(0 to buckets.first().label, buckets.size - 1 to buckets.last().label), height = 120.dp)
        else Muted("Pas de données.", size = 14)
    }
    Card(title = "FC au repos$per") {
        val values = buckets.map { avg(it) { r -> r.restingHr } }
        if (values.filterNotNull().size >= 2) LineChart(listOf(Series(values, pal.bad)), values.filterNotNull().min() - 2, values.filterNotNull().max() + 2, listOf(0 to buckets.first().label, buckets.size - 1 to buckets.last().label), height = 120.dp)
        else Muted("Pas de données.", size = 14)
    }
    Card(title = "Pas$per") {
        BarsChart(buckets.map { b -> val s = avg(b) { it.steps }; BarItem(b.label, listOf(s ?: 0.0), 0.0, s?.let { "${Insights.dec(it, 0)} pas" } ?: "–") }, listOf(pal.accent), 150.dp)
    }
}

@Composable
fun HealthScreen(snap: Snapshot) {
    val pal = LocalPal.current
    var days by rememberSaveable { mutableIntStateOf(30) }
    var hover by remember { mutableStateOf<String?>(null) }
    var showRaw by rememberSaveable { mutableStateOf(false) }
    val metrics by produceState<List<HealthMetric>?>(null, snap, days) {
        value = withContext(Dispatchers.IO) { HealthSummary.compute(days, snap.activities.sortedBy { it.startLocal }, snap.daily, snap.analysis) }
    }
    Page {
        Section("Santé", "Tout ce que mesure ta montre, résumé en cinq indicateurs")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7 to "7 j", 30 to "30 j", 90 to "3 mois", 365 to "1 an").forEach { (d, label) -> Chip(label, days == d) { days = d } }
        }
        metrics?.forEach { MetricCard(it, hover) { d -> hover = d } }
        Row(Modifier.fillMaxWidth().clickable { showRaw = !showRaw }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("${if (showRaw) "Masquer" else "Voir"} toutes les données de la montre", color = pal.accent, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Icon(Icons.Filled.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp).rotate(if (showRaw) 180f else 0f), tint = pal.accent)
        }
        if (showRaw) RawData(snap.daily, days)
    }
}
