package fr.foulee.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.max

private fun prettify(key: String) = key.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun statusLabel(key: String) = when (key.uppercase()) {
    "PRODUCTIVE" -> "Productif"
    "MAINTAINING" -> "Maintien"
    "PEAKING" -> "Au pic de forme"
    "RECOVERY" -> "Récupération"
    "UNPRODUCTIVE" -> "Improductif"
    "DETRAINING" -> "Désentraînement"
    "OVERREACHING" -> "Surcharge"
    "NO_STATUS" -> "Pas de statut"
    else -> prettify(key)
}

/** good, normal or low, to color the status */
private fun statusTone(key: String) = when (key.uppercase()) {
    "PRODUCTIVE", "PEAKING" -> "good"
    "UNPRODUCTIVE", "DETRAINING", "OVERREACHING" -> "low"
    else -> "normal"
}

private fun acwrLabel(key: String) = when (key.uppercase()) {
    "OPTIMAL" -> "Optimale"
    "LOW" -> "Basse"
    "HIGH" -> "Élevée"
    "VERY_HIGH" -> "Très élevée"
    else -> prettify(key)
}

private fun balanceLabel(key: String) = when (key.uppercase()) {
    "BALANCED" -> "Équilibré"
    "AEROBIC_LOW_SHORTAGE" -> "Manque de charge aérobie basse (endurance)"
    "AEROBIC_HIGH_SHORTAGE" -> "Manque de charge aérobie haute (seuil)"
    "ANAEROBIC_SHORTAGE" -> "Manque de charge anaérobie (vitesse)"
    else -> prettify(key)
}

private fun raceTime(seconds: Double?): String {
    if (seconds == null) return "–"
    val s = seconds.toInt()
    return if (s >= 3600) "${s / 3600}:${(s % 3600 / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** What Garmin says about the form: training status, VO2 max, acute / chronic load, load balance and race predictions. */
@Composable
fun FitnessCard(rows: List<FitnessRow>) {
    val pal = LocalPal.current
    val last = rows.lastOrNull() ?: return
    // The latest VO2 max, even when the last snapshot has none
    val vo2 = rows.lastOrNull { it.vo2Max != null }?.vo2Max
    Card(title = "Forme selon Garmin", action = { Muted("au ${Fmt.dayMonth(LocalDate.parse(last.date))}", size = 12) }) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Muted("VO2 max")
                Text(vo2?.let { Fmt.num(it) } ?: "–", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
            }
            Column(Modifier.weight(1f)) {
                Muted("Statut d'entraînement")
                last.status?.let { s ->
                    val color = when (statusTone(s)) { "good" -> pal.good; "low" -> pal.warn; else -> pal.accent }
                    Text(statusLabel(s), Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 4.dp), color = color, fontWeight = FontWeight.Medium)
                } ?: Text("–", fontSize = 20.sp, color = pal.text)
            }
        }

        if (last.acuteLoad != null && last.chronicLoad != null) {
            Text("Charge aiguë et chronique", Modifier.padding(top = 16.dp), color = pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val cMin = last.chronicMin ?: last.chronicLoad * 0.8
            val cMax = last.chronicMax ?: last.chronicLoad * 1.5
            val scaleMax = max(max(cMax * 1.4, last.acuteLoad * 1.1), 1.0)
            val acute = (last.acuteLoad / scaleMax).toFloat().coerceIn(0f, 1f)
            // The band between the two limits is the range Garmin calls productive for the chronic load
            BoxScale(acute, cMin / scaleMax, cMax / scaleMax)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Muted("Aiguë ${last.acuteLoad.toInt()}", size = 13)
                Muted("Chronique ${last.chronicLoad.toInt()} (plage ${cMin.toInt()} à ${cMax.toInt()})", size = 13)
            }
            last.acwr?.let { Muted("Rapport aigu / chronique : ${Fmt.num(it, 2)}${last.acwrStatus?.let { s -> " (${acwrLabel(s)})" } ?: ""}", Modifier.padding(top = 4.dp), size = 13) }
        }

        if (last.loadAerobicLow != null || last.loadAerobicHigh != null || last.loadAnaerobic != null) {
            Text("Équilibre de la charge (30 jours)", Modifier.padding(top = 16.dp), color = pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val items = listOf("Aérobie basse" to last.loadAerobicLow, "Aérobie haute" to last.loadAerobicHigh, "Anaérobie" to last.loadAnaerobic)
            val top = max(items.maxOf { it.second ?: 0.0 }, 1.0)
            items.forEach { (label, v) ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Muted(label, Modifier.width(100.dp), size = 13)
                    ProgressBar(((v ?: 0.0) / top).toFloat(), pal.accent, Modifier.weight(1f))
                    Text("${(v ?: 0.0).toInt()}", Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontSize = 12.sp, color = pal.muted)
                }
            }
            last.loadBalance?.let { Muted(balanceLabel(it), Modifier.padding(top = 6.dp), size = 13) }
        }

        if (last.race5k != null || last.race10k != null || last.raceHalf != null || last.raceMarathon != null) {
            Text("Prédictions de course", Modifier.padding(top = 16.dp), color = pal.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                listOf("5 km" to last.race5k, "10 km" to last.race10k, "Semi" to last.raceHalf, "Marathon" to last.raceMarathon).forEach { (label, t) ->
                    Column(Modifier.weight(1f)) {
                        Muted(label, size = 12)
                        Text(raceTime(t), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = pal.text)
                    }
                }
            }
        }
    }
}

/** A bar with a shaded range and a marker. All values are fractions of the bar. */
@Composable
private fun BoxScale(marker: Float, rangeStart: Double, rangeEnd: Double) {
    val pal = LocalPal.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        val total = maxWidth
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(pal.track))
        Box(Modifier.padding(start = total * rangeStart.toFloat().coerceIn(0f, 1f)).width(total * (rangeEnd - rangeStart).toFloat().coerceIn(0f, 1f)).height(10.dp).background(pal.good.copy(alpha = 0.35f)))
        Box(Modifier.offset(x = (total * marker - 2.dp).coerceAtLeast(0.dp), y = (-4).dp).width(4.dp).height(18.dp).clip(RoundedCornerShape(50)).background(pal.text))
    }
}
