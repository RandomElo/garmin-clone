package fr.foulee.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private fun fr(v: Double, digits: Int = 0) = Insights.dec(abs(v), digits)

/** The few numbers that sum the session up, each with a plain-words caption. */
private fun keyFigures(a: SessionAnalysis): List<Triple<String, String, String>> {
    val figures = mutableListOf<Triple<String, String, String>>()
    a.paceDeltaSecPerKm?.let { d ->
        figures += Triple("Allure", if (d == 0) "=" else "${if (d < 0) "−" else "+"}${fr(d.toDouble())} s/km", if (d == 0) "comme d'habitude" else if (d < 0) "plus vite que d'habitude" else "plus lent que d'habitude")
    }
    a.hrDelta?.let { h ->
        val d = h.roundToInt()
        figures += Triple("Fréquence cardiaque", if (d == 0) "=" else "${if (d < 0) "−" else "+"}${fr(d.toDouble())} bpm", if (d == 0) "comme d'habitude" else if (d < 0) "plus basse que d'habitude" else "plus haute que d'habitude")
    }
    a.fadePct?.let { d ->
        figures += Triple("Fin de série", if (abs(d) < 1.5) "Stable" else "${if (d < 0) "−" else "+"}${fr(d, 1)} %", if (abs(d) < 1.5) "allure tenue jusqu'au bout" else if (d < 0) "plus lente que le début" else "plus rapide que le début")
    }
    a.hrRecovery?.let { figures += Triple("Récup cardiaque", "−${it.avg} bpm", "en 1 min de récup") }
    return figures
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionAnalysisCard(analysis: SessionAnalysis) {
    val pal = LocalPal.current
    var showTrends by remember { mutableStateOf(false) }
    val trends = analysis.correlations.filter { it.strength != "aucune" }
    FoldCard(title = "Analyse de la séance") {
        keyFigures(analysis).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value, caption) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(pal.track).padding(12.dp)) {
                        Muted(label)
                        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                        Muted(caption)
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f)) {}
            }
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            analysis.paragraphs.forEach { Text(it, color = pal.text.copy(alpha = 0.85f), lineHeight = 22.sp, fontSize = 15.sp) }
        }

        if (analysis.contributions.isNotEmpty()) {
            Text("Ce qui a joué sur l'allure", Modifier.padding(top = 16.dp), color = pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Muted("En vert ce qui t'a fait gagner du temps, en orange ce qui t'en a fait perdre.", Modifier.padding(top = 2.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                analysis.contributions.forEach { c ->
                    val slower = c.effectPct < 0
                    // Solid dark pill with white text (readable), plus an arrow so color is not the only cue
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (slower) Color(0xFFC2410C) else Color(0xFF047857)).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (slower) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, null, Modifier.size(13.dp), tint = Color.White)
                        Text(c.label, Modifier.padding(start = 4.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (c.effectSecPerKm != 0) Text(" ${if (slower) "+" else "−"}${abs(c.effectSecPerKm)} s/km", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (trends.isNotEmpty()) {
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(1.dp).background(pal.border))
            Row(Modifier.fillMaxWidth().clickable { showTrends = !showTrends }.padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Ce qui influence tes séances, d'après tes ${analysis.sampleSize} fractionnés", Modifier.weight(1f), color = pal.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Filled.ExpandMore, null, Modifier.size(18.dp).rotate(if (showTrends) 180f else 0f), tint = pal.accent)
            }
            if (showTrends) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                trends.forEach { Text(it.text, color = pal.text.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp) }
            }
        }
    }
}

/** Home page: the latest interval session with the one-line summary of its analysis. */
@Composable
fun LastIntervalCard(state: AppState, snap: Snapshot, onOpen: (ActivityRow) -> Unit) {
    val pal = LocalPal.current
    val result by produceState<Pair<ActivityRow, SessionAnalysis>?>(null, snap) {
        value = withContext(Dispatchers.IO) {
            val last = snap.history.latest() ?: return@withContext null
            val laps = state.db.lapsOf(listOf(last.id))[last.id] ?: return@withContext null
            snap.history.analyze(last.id, emptyList(), laps)?.let { last to it }
        }
    }
    val (activity, analysis) = result ?: return
    Card(title = "Dernier fractionné", onClick = { onOpen(activity) }) {
        Text(activity.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = pal.text)
        Muted("${Fmt.longDate(LocalDate.parse(activity.startLocal.take(10))).lowercase()} à ${activity.startLocal.drop(11).take(5)}", Modifier.padding(top = 2.dp), size = 14)
        Text(analysis.summary, Modifier.padding(top = 10.dp), color = pal.text.copy(alpha = 0.85f), lineHeight = 22.sp, fontSize = 15.sp)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lire l'analyse complète", color = pal.accent, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 4.dp).size(16.dp), tint = pal.accent)
        }
    }
}
