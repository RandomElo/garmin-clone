package fr.foulee.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private class Cell(val key: String, val label: String, val value: String, val clickable: Boolean = true)

/** Percent (for the gauge, null when there is nothing to compare) and a short sentence. */
private class Insight(val percent: Int?, val gaugeLabel: String, val text: String)

private fun insight(key: String, a: ActivityRow, snap: Snapshot): Insight? {
    val analysis = snap.analysis
    when (key) {
        "pace" -> {
            val speed = a.averageSpeed ?: return null
            val meters = a.distance ?: return null
            val since = LocalDate.now().minusDays(730).toString()
            val similar = snap.activities.filter {
                it.isRun && it.id != a.id && it.startLocal.take(10) >= since && it.averageSpeed != null && it.distance != null && abs(it.distance - meters) <= 0.2 * meters
            }
            val format = "${Fmt.num(meters / 1000, if (meters >= 20_000) 0 else 1)} km"
            if (similar.size < 3) return Insight(null, "", "Pas assez de sorties de $format environ pour comparer (il en faut au moins 3).")
            val pct = similar.count { it.averageSpeed!! < speed } * 100 / similar.size
            val mean = similar.sumOf { it.averageSpeed!! } / similar.size
            return Insight(pct, "plus rapide", "Plus rapide que $pct % de tes ${similar.size} sorties de $format environ sur 2 ans. Ta moyenne sur ce format : ${Fmt.pace(mean)}.")
        }
        "hr" -> {
            val hr = a.averageHr ?: return null
            val pct = (hr / analysis.refs.max * 100).roundToInt().coerceIn(0, 100)
            val zone = when {
                pct < 60 -> "zone 1, récupération"
                pct < 70 -> "zone 2, endurance"
                pct < 80 -> "zone 3, tempo"
                pct < 90 -> "zone 4, seuil"
                else -> "zone 5, effort maximal"
            }
            return Insight(pct, "de ta FC max", "${hr.roundToInt()} bpm de moyenne, soit $pct % de ta FC max estimée (${analysis.refs.max.roundToInt()} bpm) : $zone.")
        }
        "effort" -> {
            val effort = analysis.effortOf(a) ?: return null
            val label = when {
                effort < 3 -> "Séance facile"
                effort < 5 -> "Séance modérée"
                effort < 7 -> "Séance soutenue"
                else -> "Séance très dure"
            }
            val points = analysis.pointsOf(a)
            return Insight((effort * 10).roundToInt(), "d'effort", "$label." + (points?.let { " Elle pèse ${Fmt.num(it)} point${if (it >= 2) "s" else ""} sur les 20 de ta charge hebdomadaire." } ?: ""))
        }
        "week" -> {
            val week = analysis.weekLoadOn(a) ?: return null
            return Insight((week / 20 * 100).roundToInt(), "de charge", "Charge des 7 jours qui finissent avec cette séance : ${Fmt.num(week)} sur 20 (20 = tes semaines les plus chargées).")
        }
    }
    return null
}


private fun percentile(sorted: List<Double>, p: Double) = sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]

/** Moving average over [window] points, ignoring gaps. */
private fun smooth(values: List<Double?>, window: Int): List<Double?> = values.indices.map { i ->
    val part = values.subList(maxOf(0, i - window / 2), minOf(values.size, i + window / 2 + 1)).filterNotNull()
    if (values[i] == null || part.isEmpty()) null else part.average()
}

@Composable
private fun Curves(detail: ActivityDetail, a: ActivityRow) {
    val pal = LocalPal.current
    val samples = detail.samples
    if (detail.track.size >= 2) Card(title = "Parcours") { RouteMap(detail.track) }
    if (samples.size < 10) return
    val last = samples.lastOrNull { it.d != null }?.d ?: 0.0
    val labels = listOf(0, samples.size / 2, samples.size - 1).map { i -> i to "${Fmt.num((samples[i].d ?: 0.0) / 1000, if (last >= 20_000) 0 else 1)} km" }

    if (a.isRun) {
        // Standing still or walking (recoveries) counts as the slowest pace instead of leaving a hole in the curve
        val pace = smooth(samples.map { it.speed?.coerceAtLeast(0.5) }, 5).map { s -> s?.let { 1000 / it }?.coerceAtMost(600.0) }
        val valid = pace.filterNotNull().filter { it >= 120 }.sorted()
        if (valid.size >= 10) {
            val fast = percentile(valid, 0.03)
            val slow = percentile(valid, 0.97)
            Card(title = "Allure") {
                // The axis is inverted: faster is higher
                LineChart(listOf(Series(pace.map { it?.let { p -> -p.coerceIn(fast, slow) } }, pal.accent, fill = true)), -slow, -fast, labels, topLabel = "${Fmt.clock(fast)} à ${Fmt.clock(slow)} /km")
            }
        }
    }
    val hr = smooth(samples.map { it.hr?.takeIf { v -> v > 30 } }, 5)
    val hrValid = hr.filterNotNull().sorted()
    if (hrValid.size >= 10) {
        val lo = percentile(hrValid, 0.02)
        val hi = percentile(hrValid, 0.99)
        Card(title = "Fréquence cardiaque") {
            LineChart(listOf(Series(hr, pal.bad, fill = true)), lo, hi, labels, topLabel = "${lo.roundToInt()} à ${hi.roundToInt()} bpm")
        }
    }
    val ele = smooth(samples.map { it.ele }, 5)
    val eleValid = ele.filterNotNull()
    if (eleValid.size >= 10 && eleValid.max() - eleValid.min() >= 5) {
        Card(title = "Altitude") {
            LineChart(listOf(Series(ele, pal.good, fill = true)), eleValid.min(), eleValid.max(), labels, topLabel = "${eleValid.min().roundToInt()} à ${eleValid.max().roundToInt()} m")
        }
    }
}

/** One table row: the first column is the narrowest. */
@Composable
private fun TableRow(cells: List<String>, header: Boolean = false, muted: Boolean = false, emphasize: Int = -1, colors: Map<Int, androidx.compose.ui.graphics.Color> = emptyMap(), weights: List<Float>? = null) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cells.forEachIndexed { i, text ->
            Text(
                text, Modifier.weight(weights?.getOrNull(i) ?: if (i == 0) 0.7f else 1f), fontSize = 13.sp,
                fontWeight = if (header || i == emphasize) FontWeight.Medium else FontWeight.Normal,
                color = colors[i] ?: if (header || muted) pal.muted else pal.text,
            )
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPal.current.border))

@Composable
private fun CategoryPicker(state: AppState, a: ActivityRow) {
    val pal = LocalPal.current
    var open by remember { mutableStateOf(false) }
    val manual = a.manualCategory != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Muted("Type", size = 14)
        Box(Modifier.padding(start = 10.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(pal.card).clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (manual) Categories.label(a.category) else "Automatique (${Categories.label(a.category)})", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = pal.text)
                Icon(Icons.Filled.ExpandMore, null, Modifier.padding(start = 4.dp).size(16.dp), tint = pal.muted)
            }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text("Automatique") }, { open = false; state.setCategory(a.id, null) })
                Categories.all.forEach { c -> DropdownMenuItem({ Text(Categories.label(c)) }, { open = false; state.setCategory(a.id, c) }) }
            }
        }
        if (manual) Muted("choisi à la main", Modifier.padding(start = 8.dp))
    }
}


/** The actions on the text of a session, in one compact row: copy (a menu), spé and recoveries (dialogs), send. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextActions(state: AppState, a: ActivityRow, analysis: IntervalAnalysis?, text: StravaText) {
    val pal = LocalPal.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copyMenu by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var speOpen by remember { mutableStateOf(false) }
    var recOpen by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    fun copy(value: String) {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(value))
        copied = true
    }
    val small = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            OutlinedButton({ copyMenu = true }, Modifier.height(36.dp), contentPadding = small) { Text(if (copied) "Copié" else "Copier", fontSize = 13.sp) }
            DropdownMenu(copyMenu, { copyMenu = false }) {
                DropdownMenuItem({ Text("Le titre") }, { copyMenu = false; copy(text.name) })
                DropdownMenuItem({ Text("La description") }, { copyMenu = false; copy(text.description) })
                DropdownMenuItem({ Text("Titre et description") }, { copyMenu = false; copy(text.name + "\n\n" + text.description) })
            }
        }
        if (analysis != null) OutlinedButton({ speOpen = true }, Modifier.height(36.dp), contentPadding = small) {
            Text(a.speciality?.takeIf { it != "none" }?.let { "Spé : $it" } ?: "Spé", fontSize = 13.sp)
        }
        if (analysis != null && analysis.groups.any { it.recovery != null }) OutlinedButton({ recOpen = true }, Modifier.height(36.dp), contentPadding = small) { Text("Récups", fontSize = 13.sp) }
        if (a.strava != null && state.stravaConfigured) OutlinedButton({ state.sendText(a) }, Modifier.height(36.dp), contentPadding = small) { Text("Envoyer", fontSize = 13.sp) }
    }
    if (state.stravaConfigured && state.textStatus.isNotEmpty()) Muted(state.textStatus, Modifier.padding(top = 6.dp), size = 12)
    if (analysis != null && speOpen) SpecialityDialog(state, a, StravaTextBuilder.proposedSpeciality(analysis)) { speOpen = false }
    if (analysis != null && recOpen) RecoveryDialog(state, a, analysis) { recOpen = false }
}

/** The race pace of the reps ("Spé 5 km"): proposed from the pace, set only when Eloi taps one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpecialityDialog(state: AppState, a: ActivityRow, proposed: String?, onClose: () -> Unit) {
    val pal = LocalPal.current
    val current = a.speciality
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = pal.card, titleContentColor = pal.text, textContentColor = pal.text,
        confirmButton = { androidx.compose.material3.TextButton(onClose) { Text("Fermer") } },
        title = { Text("Spé", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Muted(
                    if (proposed != null) "Tes répétitions longues sont à l'allure de ta course sur $proposed." else "Aucune allure de course ne correspond à cette séance.",
                    size = 13,
                )
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("none" to "Pas de spé", "5 km" to "Spé 5 km", "10 km" to "Spé 10 km", "semi" to "Spé semi", "marathon" to "Spé marathon").forEach { (value, label) ->
                        // Proposed ones carry a mark so the suggestion is visible without being applied
                        val text = if (value == proposed && current != value) "$label (proposé)" else label
                        Chip(text, (current ?: "none") == value) { state.setSpeciality(a.id, value); onClose() }
                    }
                }
            }
        },
    )
}

/** Timed or measured recovery, one choice per type of efforts: the detection is a guess, this corrects it in two taps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecoveryDialog(state: AppState, a: ActivityRow, analysis: IntervalAnalysis, onClose: () -> Unit) {
    val pal = LocalPal.current
    val groups = analysis.groups.filter { it.recovery != null }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = pal.card, titleContentColor = pal.text, textContentColor = pal.text,
        confirmButton = { androidx.compose.material3.TextButton(onClose) { Text("Fermer") } },
        title = { Text("Récups", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Muted("Mesurée au temps ou à la distance ? Le titre change tout de suite.", size = 13)
                groups.forEach { g ->
                    val fixed = a.recoveryModes[g.label]
                    Column(Modifier.padding(top = 14.dp)) {
                        Text("${if (g.reps.size > 1) "${g.reps.size} × " else ""}${g.label} : ${g.recovery!!.label}", color = pal.text, fontSize = 14.sp)
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Temps", g.recovery.measured == false) { state.setRecoveryMode(a.id, g.label, false) }
                            Chip("Distance", g.recovery.measured == true) { state.setRecoveryMode(a.id, g.label, true) }
                            if (fixed != null) Chip("Auto", false) { state.setRecoveryMode(a.id, g.label, null) }
                        }
                    }
                }
            }
        },
    )
}

/** The name, description and labels Eloi gave the outing on Strava. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StravaCard(s: StravaActivity) {
    val pal = LocalPal.current
    Card(title = "Strava") {
        if (s.name.isNotBlank()) Text(s.name, fontWeight = FontWeight.SemiBold, color = pal.text, fontSize = 15.sp, lineHeight = 21.sp)
        if (s.description.isNotBlank()) Text(s.description, Modifier.padding(top = 6.dp), color = pal.muted, fontSize = 14.sp, lineHeight = 20.sp)
        if (s.labels.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.labels.forEach { l -> Text(StravaSync.labelFr(l), Modifier.clip(RoundedCornerShape(50)).background(pal.accent.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp), color = pal.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun IntervalCard(analysis: IntervalAnalysis, notes: List<String> = emptyList()) {
    val pal = LocalPal.current
    FoldCard(title = "Splits de la séance" + if (analysis.structured) " (séance programmée)" else "") {
        notes.forEach { Text(it, Modifier.padding(bottom = 12.dp), color = pal.warn, fontSize = 13.sp, lineHeight = 18.sp) }
        analysis.groups.forEachIndexed { gi, g ->
            if (gi > 0) Box(Modifier.padding(vertical = 14.dp)) { Divider() }
            Text(
                "${g.reps.size} × ${g.label}" + (g.recovery?.let { " (r : ${it.label})" } ?: ""),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.text,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.weight(1f)) { Muted("Allure moyenne"); Text(Fmt.pace(g.avgSpeed), fontWeight = FontWeight.SemiBold, color = pal.text) }
                Column(Modifier.weight(1f)) { Muted("FC moyenne"); Text(g.avgHr?.let { "$it bpm" } ?: "–", fontWeight = FontWeight.SemiBold, color = pal.text) }
            }
            Column(Modifier.padding(top = 12.dp)) {
                TableRow(listOf("Rép.", if (g.timeBased) "Distance" else "Temps", "Allure", "Écart", "FC moy."), header = true)
                Divider()
                g.reps.forEach { r ->
                    val speed = r.lap.distance!! / r.lap.duration!!
                    // Seconds per km versus the group average: negative = faster
                    val delta = (1000 / speed - 1000 / g.avgSpeed).roundToInt()
                    TableRow(
                        listOf(
                            "${r.rep}", if (g.timeBased) Fmt.km(r.lap.distance) else Fmt.clock(r.lap.duration), Fmt.pace(speed),
                            if (delta == 0) "=" else "${if (delta > 0) "+" else "−"}${abs(delta)} s/km", r.lap.averageHr?.roundToInt()?.toString() ?: "–",
                        ),
                        emphasize = 2, colors = mapOf(3 to if (delta < 0) pal.good else if (delta > 0) pal.warn else pal.muted),
                    )
                }
            }
        }
    }
}

/** The effect levels Garmin gives a session, from the gentlest to the hardest. */
private val effectLadder = listOf("RECOVERY", "BASE", "TEMPO", "LACTATE_THRESHOLD", "VO2MAX", "ANAEROBIC_CAPACITY", "SPRINT")

private fun effectLevel(key: String) = when (key) {
    "SPEED" -> effectLadder.indexOf("ANAEROBIC_CAPACITY")
    else -> effectLadder.indexOf(key)
}

/** The ladder: one segment per level, the session's one lit. */
@Composable
private fun EffectLadder(level: Int) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        effectLadder.indices.forEach { i ->
            val lit = i == level
            Box(
                Modifier.weight(1f).height(if (lit) 18.dp else 10.dp).clip(RoundedCornerShape(50))
                    .background(if (i <= level) pal.accent.copy(alpha = if (lit) 1f else 0.55f) else pal.muted.copy(alpha = 0.25f)),
            )
        }
    }
}

/** Five segments filled up to the score (0 to 5). */
@Composable
private fun Segments(value: Double, color: androidx.compose.ui.graphics.Color) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 5) {
            val fill = (value - i).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50)).background(pal.muted.copy(alpha = 0.25f))) {
                if (fill > 0f) Box(Modifier.fillMaxWidth(fill).height(10.dp).background(color))
            }
        }
    }
}

/** The effect on screen is a ladder with the session's level lit; the scores, load and zones are behind the "i". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingEffectCard(a: ActivityRow) {
    val pal = LocalPal.current
    var open by remember { mutableStateOf(false) }
    val label = a.teLabel?.let { teLabel(it) }
    Card(
        title = "Effet de l'entraînement",
        action = { Icon(Icons.Outlined.Info, "Détail", Modifier.size(22.dp).clip(CircleShape).clickable { open = true }, tint = pal.muted) },
    ) {
        Column(Modifier.clickable { open = true }) {
            Text(label ?: "Voir le détail", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (label != null) pal.text else pal.accent)
            a.teLabel?.let { effectLevel(it).takeIf { l -> l >= 0 } }?.let { Box(Modifier.padding(top = 10.dp)) { EffectLadder(it) } }
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        containerColor = pal.card, titleContentColor = pal.text, textContentColor = pal.text,
        confirmButton = { TextButton({ open = false }) { Text("Fermer") } },
        title = { Text(label ?: "Effet de l'entraînement", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                a.teLabel?.let { key ->
                    Text(teExplanation(key), color = pal.text, fontSize = 14.sp, lineHeight = 20.sp)
                    val works = teWorks(key)
                    if (works.isNotEmpty()) Column {
                        Muted("Ce que ça fait travailler", Modifier.padding(bottom = 6.dp), size = 14)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            works.forEach { w -> Text(w, Modifier.clip(RoundedCornerShape(50)).background(pal.accent.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp), color = pal.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
                a.aerobicTe?.let { Column { Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Muted("Aérobie", size = 14); Text(Fmt.num(it), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = pal.text) }; Segments(it, pal.accent) } }
                a.anaerobicTe?.let { Column { Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Muted("Anaérobie", size = 14); Text(Fmt.num(it), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = pal.text) }; Segments(it, pal.violet) } }
            }
        },
    )
}

/** What the effect trains, in a few words each. */
private fun teWorks(key: String): List<String> = when (key) {
    "RECOVERY" -> listOf("Circulation", "Récupération des muscles")
    "BASE" -> listOf("Endurance de fond", "Graisses comme carburant", "Capillaires")
    "TEMPO" -> listOf("Allure soutenue", "Efficacité du cœur", "Endurance musculaire")
    "LACTATE_THRESHOLD" -> listOf("Seuil lactique", "Élimination du lactate", "Endurance à allure rapide")
    "VO2MAX" -> listOf("Cœur (débit max)", "Transport de l'oxygène", "Muscles qui l'utilisent", "Économie de course")
    "ANAEROBIC_CAPACITY" -> listOf("Fibres rapides", "Énergie sans oxygène", "Tolérance à l'acidité")
    "SPEED" -> listOf("Fibres rapides", "Foulée", "Coordination")
    "SPRINT" -> listOf("Puissance", "Fibres rapides", "Foulée")
    else -> emptyList()
}

private fun teExplanation(key: String) = when (key) {
    "NO_BENEFIT" -> "Trop court ou trop léger pour avoir un effet."
    "RECOVERY" -> "Séance très facile : elle aide à récupérer."
    "BASE" -> "Endurance facile : elle construit la base."
    "TEMPO" -> "Allure soutenue mais tenable longtemps."
    "LACTATE_THRESHOLD" -> "Travail au seuil : repousse l'allure que tu tiens longtemps."
    "VO2MAX" -> "Proche de ton maximum : relève ton VO2 max, l'oxygène que ton corps sait utiliser."
    "ANAEROBIC_CAPACITY" -> "Efforts courts et très intenses."
    "SPEED" -> "Vitesse pure, efforts courts et rapides."
    "SPRINT" -> "Efforts maximaux très courts."
    else -> "Effet principal de la séance selon Garmin."
}

/** Garmin's label, as the web app showed it. */
private fun teLabel(key: String) = when (key) {
    "NO_BENEFIT" -> "Aucun bénéfice"
    "RECOVERY" -> "Récupération"
    "BASE" -> "Endurance de base"
    "TEMPO" -> "Tempo"
    "LACTATE_THRESHOLD" -> "Seuil lactique"
    "VO2MAX" -> "VO2 max"
    "ANAEROBIC_CAPACITY" -> "Capacité anaérobie"
    "SPEED" -> "Vitesse"
    "SPRINT" -> "Sprint"
    else -> key.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun SplitsCard(splits: List<Split>, running: Boolean) {
    FoldCard(title = "Temps intermédiaires (par km)") {
        TableRow(listOf("Km", "Temps", if (running) "Allure" else "Vitesse", "FC moy.", "D+"), header = true)
        Divider()
        splits.forEach { s ->
            val speed = if (s.duration > 0) s.distance / s.duration else null
            TableRow(
                listOf(
                    if (s.distance < 999) Fmt.num(s.km - 1 + s.distance / 1000, 2) else s.km.toString(), Fmt.clock(s.duration), if (running) Fmt.pace(speed) else Fmt.speed(speed),
                    s.averageHr?.toString() ?: "–", "${s.elevationGain.roundToInt()} m",
                ),
                emphasize = 2,
            )
        }
    }
}

/** Nothing selected: every lap. Otherwise only the selected effort types and recovery types, nothing around them. */
private fun lapVisible(laps: List<LapInfo>, i: Int, groups: Set<Int>, kinds: Set<Int>): Boolean {
    if (groups.isEmpty() && kinds.isEmpty()) return true
    val info = laps[i]
    return when (info.role) {
        LapRole.WORK -> info.group?.let { it in groups } == true
        LapRole.RECOVERY -> info.kind?.let { it in kinds } == true
        else -> false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LapsCard(laps: List<LapInfo>, analysis: IntervalAnalysis?, running: Boolean) {
    val pal = LocalPal.current
    var groups by remember(analysis) { mutableStateOf(emptySet<Int>()) }
    var kinds by remember(analysis) { mutableStateOf(emptySet<Int>()) }
    fun toggle(set: Set<Int>, i: Int) = if (i in set) set - i else set + i
    val shown = laps.indices.filter { lapVisible(laps, it, groups, kinds) }
    FoldCard(title = "Tours (montre)") {
        if (analysis != null && (analysis.groups.size > 1 || analysis.recoveryKinds.size > 1)) {
            if (analysis.groups.size > 1) {
                Muted("Efforts")
                FlowRow(Modifier.padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    analysis.groups.forEachIndexed { gi, g -> Chip("${g.reps.size} × ${g.label}", gi in groups) { groups = toggle(groups, gi) } }
                }
            }
            if (analysis.recoveryKinds.size > 1) {
                Muted("Récups")
                FlowRow(Modifier.padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    analysis.recoveryKinds.forEachIndexed { ki, k -> Chip("${k.label} (${k.laps.size})", ki in kinds) { kinds = toggle(kinds, ki) } }
                }
            }
            val efforts = shown.map { laps[it] }.filter { it.role == LapRole.WORK }.map { it.lap }
            if ((groups.isNotEmpty() || kinds.isNotEmpty()) && efforts.isNotEmpty()) {
                val speed = efforts.sumOf { it.distance ?: 0.0 } / efforts.sumOf { it.duration ?: 0.0 }
                val hrs = efforts.mapNotNull { it.averageHr }
                Muted(
                    "${efforts.size} effort${if (efforts.size > 1) "s" else ""} : allure moyenne ${if (running) Fmt.pace(speed) else Fmt.speed(speed)}" +
                        (if (hrs.isNotEmpty()) ", FC ${hrs.average().roundToInt()} bpm" else ""),
                    Modifier.padding(bottom = 6.dp), size = 13,
                )
            }
        }
        val weights = if (analysis != null) listOf(0.4f, 1.75f, 0.85f, 0.8f, 1.2f, 0.6f) else null
        TableRow(listOfNotNull("N°", if (analysis != null) "Type" else null, "Dist.", "Temps", if (running) "Allure" else "Vitesse", "FC"), header = true, weights = weights)
        Divider()
        shown.forEach { i ->
            val info = laps[i]
            val l = info.lap
            val role = if (analysis != null) {
                if (info.role == LapRole.WORK && info.group != null) "${analysis.groups[info.group!!].label} n°${info.rep}"
                else if (info.role == LapRole.RECOVERY && info.kind != null) "Récup ${analysis.recoveryKinds[info.kind!!].label}"
                else info.role.label
            } else null
            TableRow(
                listOfNotNull(
                    "${i + 1}", role, Fmt.km(l.distance), Fmt.clock(l.duration), if (running) Fmt.pace(l.averageSpeed) else Fmt.speed(l.averageSpeed), l.averageHr?.roundToInt()?.toString() ?: "–",
                ),
                muted = analysis != null && info.role != LapRole.WORK, emphasize = if (analysis != null) 4 else 3,
                colors = if (analysis != null && info.role == LapRole.WORK) mapOf(1 to pal.accent) else emptyMap(), weights = weights,
            )
        }
    }
}

/** What the phone keeps about one activity, with the curves and the GPS track when they are stored (or loaded on demand). */
/** An outing only Strava knows: what the server gave (name, description, labels) and the few numbers, without analysis, curves or load. */
@Composable
private fun StravaOnlyDetail(a: ActivityRow, onBack: () -> Unit) {
    val pal = LocalPal.current
    val s = a.strava ?: return
    Page {
        Row(Modifier.clickable(onClick = onBack), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp), tint = pal.accent)
            Text("Activités", Modifier.padding(start = 6.dp), color = pal.accent, fontWeight = FontWeight.Medium)
        }
        Column {
            Text("${Fmt.typeLabel(a.type)} · Strava seulement", color = pal.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(a.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = pal.text, lineHeight = 30.sp)
            Muted("${Fmt.longDate(LocalDate.parse(a.startLocal.take(10))).lowercase()} à ${a.startLocal.drop(11).take(5)}", Modifier.padding(top = 4.dp), size = 14)
        }
        Card {
            val cells = listOfNotNull(
                (a.distance?.takeIf { it > 0 })?.let { "Distance" to Fmt.km(it) },
                a.duration?.let { "Durée" to Fmt.clock(it) },
                (a.averageSpeed?.takeIf { a.isRun })?.let { "Allure" to Fmt.pace(it) },
                (a.elevation?.takeIf { it >= 50 })?.let { "Dénivelé" to "${it.roundToInt()} m" },
            )
            cells.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (label, value) ->
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Muted(label)
                            Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f)) {}
                }
            }
            Muted("Cette sortie n'est pas sur Garmin : pas d'analyse ni de charge.", Modifier.padding(top = 6.dp, start = 10.dp))
        }
        StravaCard(s)
    }
}

@Composable
fun ActivityDetailScreen(state: AppState, snap: Snapshot, opened: ActivityRow, onBack: () -> Unit) {
    // The row handed over can be older than the data: a choice made here (type, recovery) shows at once
    val a = snap.activities.firstOrNull { it.id == opened.id } ?: opened
    if (a.id < 0) {
        StravaOnlyDetail(a, onBack)
        return
    }
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    var detail by remember(a.id) { mutableStateOf<ActivityDetail?>(null) }
    var checked by remember(a.id) { mutableStateOf(false) }
    var loading by remember(a.id) { mutableStateOf(false) }
    var error by remember(a.id) { mutableStateOf<String?>(null) }
    var metric by remember(a.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(a.id) {
        detail = withContext(Dispatchers.IO) { state.db.detail(a.id) }
        checked = true
    }

    val analysis = snap.analysis
    val intervals = remember(detail, a.recoveryModes) { detail?.laps?.let { Intervals.analyze(it, a.recoveryModes) } }
    // Analysis of an interval session: only needs the laps, the curve adds the heart-rate recovery
    val sessionAnalysis by produceState<SessionAnalysis?>(null, a.id, detail, snap) {
        value = withContext(Dispatchers.IO) {
            val laps = detail?.laps ?: state.db.lapsOf(listOf(a.id))[a.id]
            if (laps == null || a.intervalTitle == null) null else snap.history.analyze(a.id, detail?.samples ?: emptyList(), laps)
        }
    }
    val weekPoint = analysis.load.firstOrNull { it.date == a.startLocal.take(10) }
    val cells = listOfNotNull(
        Cell("distance", "Distance", Fmt.km(a.distance), false),
        Cell("duration", "Durée", Fmt.clock(a.duration), false),
        a.vo2Max?.let { Cell("vo2", "VO2 max", Fmt.num(it), false) },
        Cell("pace", if (a.isRun) "Allure" else "Vitesse", if (a.isRun) Fmt.pace(a.averageSpeed) else Fmt.speed(a.averageSpeed)).takeIf { a.isRun },
        Cell("speed", "Vitesse", Fmt.speed(a.averageSpeed), false).takeIf { !a.isRun },
        a.averageHr?.let { Cell("hr", "FC moyenne", "${it.roundToInt()} bpm") },
        analysis.effortOf(a)?.let { Cell("effort", "Effort", "${Fmt.num(it)} / 10") },
        analysis.weekLoadOn(a)?.let { Cell("week", "Charge 7 jours", "${Fmt.num(it)} / 20") },
        // Elevation only matters when there is some
        a.elevation?.takeIf { it >= 100 }?.let { Cell("elevation", "Dénivelé", "${it.roundToInt()} m", false) },
    )

    Page {
        Row(Modifier.clickable(onClick = onBack), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp), tint = pal.accent)
            Text("Activités", Modifier.padding(start = 6.dp), color = pal.accent, fontWeight = FontWeight.Medium)
        }
        Column {
            Text(Fmt.typeLabel(a.type), color = pal.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(a.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = pal.text, lineHeight = 30.sp)
            Muted("${Fmt.longDate(LocalDate.parse(a.startLocal.take(10))).lowercase()} à ${a.startLocal.drop(11).take(5)}", Modifier.padding(top = 4.dp), size = 14)
            Box(Modifier.padding(top = 10.dp)) { CategoryPicker(state, a) }
        }

        Card {
            cells.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { c ->
                        val selected = metric == c.key
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(if (selected) pal.accent.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color.Transparent)
                                .then(if (c.clickable) Modifier.clickable { metric = if (selected) null else c.key } else Modifier)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Muted(c.label)
                                if (c.clickable) Icon(Icons.Filled.ExpandMore, null, Modifier.padding(start = 2.dp).size(14.dp), tint = if (selected) pal.accent else pal.neutral)
                            }
                            Text(c.value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (selected) pal.accent else pal.text)
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f)) {}
                }
            }
            weekPoint?.let { Muted("Charge 7 jours : ${Math.round(it.weekKm)} km et ${it.weekSessions} séance${if (it.weekSessions > 1) "s" else ""} sur la semaine qui finit ce jour-là.", Modifier.padding(top = 8.dp, start = 10.dp)) }
        }

        metric?.let { key ->
            insight(key, a, snap)?.let { i ->
                Card(highlight = true) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (i.percent != null) Gauge(i.percent, i.gaugeLabel, 150.dp)
                        Text(i.text, Modifier.padding(top = if (i.percent != null) 4.dp else 0.dp), color = pal.text, textAlign = TextAlign.Center, lineHeight = 21.sp, fontSize = 14.sp)
                    }
                }
            }
        }

        // What the app would write on Strava for this session, to read and copy by hand
        val textCard = if (a.intervalTitle != null) intervals?.let { iv ->
            remember(iv, a.recoveryModes, a.speciality, analysis) { StravaTextBuilder.build(iv, analysis.weekLoadOn(a), StravaTexts.readinessOf(a, analysis), a.speciality) }
        } else if (a.isRun && a.category in setOf("easy", "long")) {
            remember(a.id, a.category, analysis) { StravaTextBuilder.buildEasy(a, analysis.weekLoadOn(a), StravaTexts.readinessOf(a, analysis)) }
        } else null
        textCard?.let { text ->
            Card(title = "Texte pour Strava") {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.track).padding(12.dp)) {
                        Text(text.name, color = pal.text, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(text.description, Modifier.padding(top = 8.dp), color = pal.muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
                TextActions(state, a, if (text.interval) intervals else null, text)
            }
        }
        // Nothing else shows for a moment while the curves and the analysis load from the base
        if (!checked || (a.intervalTitle != null && sessionAnalysis == null)) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Muted("Chargement…", Modifier.padding(start = 10.dp), size = 14)
        }
        sessionAnalysis?.let { SessionAnalysisCard(it) }
        intervals?.let { iv -> IntervalCard(iv, remember(detail) { detail?.let { Intervals.notes(it.laps, it.samples) } ?: emptyList() }) }
        detail?.let { Curves(it, a) }
        if (a.teLabel != null || a.aerobicTe != null) TrainingEffectCard(a)
        // Kilometer splits mean little on an interval session: the reps table replaces them
        detail?.let { d ->
            val splits = remember(d) { Splits.compute(d.samples) }
            if (splits.isNotEmpty() && intervals == null) SplitsCard(splits, a.isRun)
            val lapRows = intervals?.laps ?: d.laps.map { LapInfo(it, LapRole.OTHER) }
            if (lapRows.size > 1) LapsCard(lapRows, intervals, a.isRun)
        }

        if (checked && detail == null && (a.distance ?: 0.0) >= 1000) {
            Card {
                Muted("La courbe, le tracé GPS et les tours de cette activité ne sont pas encore sur le téléphone.", size = 14)
                Button(
                    {
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                detail = withContext(Dispatchers.IO) { Sync.detail(state.auth, state.db, a.id) }
                                state.reload()
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                            } finally {
                                loading = false
                            }
                        }
                    },
                    Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !loading,
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = pal.card)
                    Text(if (loading) "Chargement…" else "Charger la courbe, le tracé et les tours", Modifier.padding(start = if (loading) 8.dp else 0.dp))
                }
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = pal.bad, fontSize = 14.sp) }
            }
        }
    }
}
