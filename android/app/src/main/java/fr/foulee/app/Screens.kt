package fr.foulee.app

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}

/* ---------- Accueil ---------- */

@Composable
fun HomeScreen(state: AppState, snap: Snapshot, onSeeAll: () -> Unit, onOpen: (ActivityRow) -> Unit) {
    val pal = LocalPal.current
    var panel by rememberSaveable { mutableStateOf("") }
    val analysis = snap.analysis
    val readiness = analysis.readiness
    val load = analysis.today
    val hello = if (java.time.LocalTime.now().hour >= 18) "Bonsoir" else "Bonjour"

    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(state.photo, state.firstName)
            Column(Modifier.padding(start = 12.dp)) {
                Muted(Fmt.longDate(LocalDate.now()), size = 14)
                Text(hello + (state.firstName?.let { " $it" } ?: ""), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
            }
        }
        headline(analysis)?.let { Headline(it) }

        Tile(
            Icons.Filled.Route, "Cette semaine", Fmt.num(snap.weekKm), "km",
            "${snap.weekRuns} sortie${if (snap.weekRuns > 1) "s" else ""} depuis lundi · semaine dernière : ${Fmt.num(snap.lastWeekKm)} km",
            tint = pal.accent,
        )
        Tile(
            Icons.Filled.MonitorWeight, "Charge, 7 jours", load?.let { Fmt.num(it.week) } ?: "–", "/ 20",
            "${trend(snap.compare.delta)} vs ${Fmt.num(snap.compare.avgPoints)} les 3 semaines d'avant · voir pourquoi",
            open = panel == "load", onClick = { panel = if (panel == "load") "" else "load" },
            tint = pal.violet,
        )
        if (panel == "load") LoadPanel(snap.compare, onOpen)
        val tone = readiness?.let { if (it.score >= 66) pal.good else if (it.score >= 52) pal.accent else if (it.score >= 38) pal.warn else pal.bad } ?: pal.good
        Tile(
            Icons.Filled.MonitorHeart, "Forme du jour", readiness?.score?.toString() ?: "–", readiness?.let { "/ 100" },
            readiness?.let { "${it.label} · sommeil, VFC, FC au repos" } ?: "Pas encore de nuit enregistrée",
            open = if (readiness != null) panel == "readiness" else null, onClick = if (readiness != null) ({ panel = if (panel == "readiness") "" else "readiness" }) else null,
            tint = tone,
        )
        if (panel == "readiness" && readiness != null) ReadinessPanel(readiness)

        LastIntervalCard(state, snap, onOpen)

        WeeklyCard(snap.weeks)

        Card(title = "Activités récentes", action = { Text("Tout voir", color = pal.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onSeeAll)) }) {
            if (snap.activities.isEmpty()) Muted("Pas encore d'activité.", size = 14)
            snap.activities.take(5).forEach { ActivityLine(it, snap.analysis.effortOf(it), onOpen) }
        }
    }
}

private fun trend(delta: Double): String {
    val r = Math.round(delta * 10) / 10.0
    return if (r == 0.0) "stable" else (if (r > 0) "↗ +" else "↘ −") + Fmt.num(Math.abs(r))
}

/** Short text under the greeting, from the readiness factors that stand out. */
private fun headline(analysis: Analysis): String? {
    val r = analysis.readiness ?: return null
    val top = r.factors.filter { it.key != "fatigue" && it.baselineDisplay != null }.maxByOrNull { Math.abs(it.z) }
    val bits = "Forme du jour à ${r.score}/100 : ${r.label.lowercase()}."
    return if (top != null && Math.abs(top.z) >= 0.5) "$bits ${top.label} à ${top.display}, ${top.note}." else bits
}

/** Two thin bars: the last 7 days (accent) over the average of the 3 weeks before (neutral reference). */
@Composable
private fun PairedBars(current: Double, before: Double, max: Double) {
    val pal = LocalPal.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(current to pal.accent, before to pal.neutral).forEach { (value, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(10.dp)) {
                    Box(Modifier.fillMaxWidth(((value / max).coerceIn(0.0, 1.0)).toFloat().let { if (value > 0) Math.max(it, 0.015f) else 0f }).height(10.dp).clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(color))
                }
                Text(Fmt.num(value), Modifier.width(38.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontSize = 12.sp, color = pal.muted)
            }
        }
    }
}

@Composable
private fun LoadPanel(c: LoadComparison, onOpen: (ActivityRow) -> Unit) {
    val pal = LocalPal.current
    val max = Math.max(Math.max(c.current.points, c.avgPoints), 1.0)
    Card(title = "Ce qui fait la différence") {
        Text(c.explanation, fontWeight = FontWeight.Medium, color = pal.text, lineHeight = 22.sp)
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("7 derniers jours", pal.accent)
            Legend("3 semaines d'avant (moyenne)", pal.neutral)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Total", Modifier.width(112.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = pal.text)
            Box(Modifier.weight(1f)) { PairedBars(c.current.points, c.avgPoints, max) }
            Text(trend(c.delta), Modifier.width(60.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontSize = 12.sp, color = pal.muted)
        }
        c.categories.forEach { cat ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.border))
            val sessions = c.current.sessions.filter { it.category == cat.category }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(112.dp)) {
                    Text(cat.label, fontSize = 14.sp, color = pal.text)
                    Muted("${cat.count} contre ${Fmt.num(cat.avgCount)}")
                }
                Column(Modifier.weight(1f)) {
                    PairedBars(cat.points, cat.avgPoints, max)
                    sessions.forEach { s ->
                        Text(s.activity.title, Modifier.clickable { onOpen(s.activity) }.padding(top = 3.dp), color = pal.muted, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Text(trend(cat.delta), Modifier.width(60.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontSize = 12.sp, color = pal.muted)
            }
        }
        Muted("En points de charge sur 20 (20 = tes semaines les plus chargées). Le nombre à gauche compte les séances.", Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ReadinessPanel(readiness: Readiness) {
    val pal = LocalPal.current
    Card(title = "Forme du jour, facteur par facteur") {
        if (readiness.date != LocalDate.now().toString()) Muted("Dernière nuit synchronisée : ${Fmt.longDate(LocalDate.parse(readiness.date)).lowercase()}.", Modifier.padding(bottom = 8.dp))
        readiness.factors.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(f.status)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(f.label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = pal.text)
                    Muted(f.note + (f.baselineDisplay?.let { " · habituel $it" } ?: ""))
                }
                Text(f.display, fontWeight = FontWeight.SemiBold, color = pal.text)
            }
        }
        Muted("Chaque valeur est comparée à la tienne sur les 30 derniers jours (60 pour la VFC), pas à une norme générale.", Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun WeeklyCard(weeks: List<Week>) {
    val pal = LocalPal.current
    var selected by remember(weeks.size) { mutableIntStateOf(weeks.size - 1) }
    val week = weeks[selected]
    val top = Math.max(Math.ceil((weeks.maxOf { it.distance }) / 10_000.0) * 10.0, 10.0)
    val labels = weeks.indices.filter { it == 0 || weeks[it].start.month != weeks[it - 1].start.month }.filter { it % 1 == 0 }.let { list -> list.filterIndexed { k, i -> k == 0 || i - list[k - 1] >= 3 } }.map { it to Fmt.month(weeks[it].start) }
    Card(title = "Course à pied, 6 derniers mois") {
        Text(if (selected == weeks.size - 1) "Cette semaine" else "Semaine du ${Fmt.dayMonth(week.start)}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = pal.text)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            listOf("Distance" to "${Fmt.num(week.distance / 1000)} km", "Temps" to Fmt.hoursMinutes(week.duration), "Dénivelé" to "${Math.round(week.elevation)} m").forEach { (l, v) ->
                Column(Modifier.weight(1f)) {
                    Muted(l)
                    Text(v, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                }
            }
        }
        Muted("${week.count} sortie${if (week.count > 1) "s" else ""}", Modifier.padding(top = 4.dp, bottom = 8.dp))
        LineChart(listOf(Series(weeks.map { it.distance / 1000 }, pal.accent, fill = true)), 0.0, top, labels, selected = selected, onSelect = { selected = it })
    }
}

/* ---------- Activités ---------- */

private fun iconOf(type: String): ImageVector = when {
    type.contains("trail") || type.contains("hiking") -> Icons.Filled.Hiking
    type.contains("running") -> Icons.Filled.DirectionsRun
    type.contains("cycl") || type.contains("biking") -> Icons.Filled.DirectionsBike
    type.contains("strength") -> Icons.Filled.FitnessCenter
    type.contains("yoga") || type.contains("pilates") -> Icons.Filled.SelfImprovement
    else -> Icons.Filled.EmojiEvents
}

@Composable
fun ActivityLine(a: ActivityRow, effort: Double? = null, onOpen: (ActivityRow) -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(pal.accent.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(iconOf(a.type), null, Modifier.size(20.dp), tint = pal.accent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(a.title, fontWeight = FontWeight.Medium, maxLines = 2, color = pal.text)
            Muted("${Categories.label(a.category)} · ${Fmt.date(a.startLocal)}" + (effort?.let { " · effort ${Fmt.num(it)}" } ?: "") + if (a.id < 0) " · Strava seulement" else "")
        }
        Column(horizontalAlignment = Alignment.End) {
            // A Strava-only outing without distance (weight training) shows its duration alone, not "0 km" and "n/d"
            if (a.id < 0 && (a.distance ?: 0.0) <= 0) Text(Fmt.clock(a.duration), fontWeight = FontWeight.SemiBold, color = pal.text)
            else {
                Text(Fmt.km(a.distance), fontWeight = FontWeight.SemiBold, color = pal.text)
                Muted("${Fmt.clock(a.duration)} · ${if (a.isRun) Fmt.pace(a.averageSpeed) else Fmt.speed(a.averageSpeed)}")
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = pal.neutral)
    }
}

@Composable
fun ActivitiesScreen(snap: Snapshot, onReview: () -> Unit, onOpen: (ActivityRow) -> Unit) {
    val toReview = remember(snap) { Review.list(snap.activities, snap.analysis).size }
    var filter by rememberSaveable { mutableStateOf("all") }
    // The outings only Strava knows are listed with the others, sorted by date
    val everything = remember(snap) { (snap.activities + snap.stravaOnly).sortedByDescending { it.startLocal } }
    val counts = everything.groupingBy { it.category }.eachCount()
    val list = if (filter == "all") everything else everything.filter { it.category == filter }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 20.dp)) {
                Section("Activités", "${snap.activities.size} activités synchronisées" + if (snap.stravaOnly.isNotEmpty()) ", ${snap.stravaOnly.size} seulement sur Strava" else "")
                if (toReview > 0) Row(
                    Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(LocalPal.current.warn.copy(alpha = 0.14f)).clickable(onClick = onReview).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("À classer : $toReview course${if (toReview > 1) "s" else ""} ressemble${if (toReview > 1) "nt" else ""} à une compétition", color = LocalPal.current.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Toutes (${everything.size})", filter == "all") { filter = "all" }
                    Categories.all.filter { (counts[it] ?: 0) > 0 }.forEach { c -> Chip("${Categories.label(c)} (${counts[c]})", filter == c) { filter = c } }
                }
            }
        }
        items(list, key = { it.id }) { a -> Card { ActivityLine(a, snap.analysis.effortOf(a), onOpen) } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/* ---------- Charge ---------- */

@Composable
private fun ZonePill(zone: String) {
    val pal = LocalPal.current
    val (label, color) = when (zone) {
        "fresh" -> "Frais" to pal.accent
        "optimal" -> "Optimal" to pal.good
        "high" -> "Élevée" to pal.warn
        else -> "Surcharge" to pal.bad
    }
    Text(label, Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 2.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Score(label: @Composable () -> Unit, value: Double?, max: Int, hint: String, extra: String? = null) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { label() }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.let { Fmt.num(it) } ?: "–", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
            Text("/ $max", Modifier.padding(start = 4.dp, bottom = 4.dp), color = pal.muted)
            extra?.let { Text(it, Modifier.padding(start = 10.dp, bottom = 4.dp), color = pal.muted, fontSize = 14.sp) }
        }
        ProgressBar(((value ?: 0.0) / max).toFloat(), pal.accent, Modifier.padding(top = 6.dp).fillMaxWidth())
        Muted(hint, Modifier.padding(top = 4.dp))
    }
}

@Composable
fun LoadScreen(snap: Snapshot) {
    val pal = LocalPal.current
    val points = snap.analysis.load.takeLast(90)
    val today = snap.analysis.today
    var selected by remember { mutableStateOf<Int?>(null) }
    Page {
        Section("Charge d'entraînement", "Charge et fatigue sur les 3 derniers mois")
        if (today == null) {
            Card { Muted("Pas encore d'activité pour calculer la charge.", size = 14) }
            return@Page
        }
        val zone = today.fatigue?.let(Insights::zoneOf)
        val weekAgo = snap.analysis.load.getOrNull(snap.analysis.load.size - 8)
        val trend = weekAgo?.let { Math.round((today.week - it.week) * 10) / 10.0 }
        Card(title = "Charge d'entraînement") {
            Score(
                { Muted("7 derniers jours", size = 14) }, today.week, 20, "${Math.round(today.weekKm)} km, ${Txt.plural(today.weekSessions, "séance")}",
                trend?.takeIf { it != 0.0 }?.let { "${if (it > 0) "↗ +" else "↘ −"}${Fmt.num(Math.abs(it))}" },
            )
            Score({ Muted("4 dernières semaines", size = 14) }, today.month, 20, "${Math.round(today.monthKm)} km, ${Txt.plural(today.monthSessions, "séance")}")
            Score({ Muted("Fatigue", size = 14); zone?.let { ZonePill(it) } }, today.fatigue, 10, "Ta semaine comparée à ton mois (3 à 7 = équilibré)")
            Text(Insights.adviceOf(zone), Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.track).padding(12.dp), color = pal.text, fontSize = 14.sp, lineHeight = 20.sp)
            Muted("Charge sur 20 : 20 = tes semaines (ou tes mois) les plus chargés depuis le début de l'historique.", Modifier.padding(top = 8.dp))
        }
        FitnessCard(snap.fitness)
        val labels = points.indices.filter { it % 30 == 0 }.map { it to Fmt.dayMonth(LocalDate.parse(points[it].date)) }
        fun readout(p: LoadPoint?, text: (LoadPoint) -> String) = if (p == null) null else "${Fmt.dayMonth(LocalDate.parse(p.date))} · ${text(p)}"
        val point = selected?.let { points.getOrNull(it) }
        Card(title = "Charge des 4 dernières semaines (sur 20)") {
            readout(point) { "${Fmt.num(it.month)} / 20" }?.let { Text(it, Modifier.padding(bottom = 4.dp), color = pal.text, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
            LineChart(listOf(Series(points.map { it.month }, pal.violet, fill = true)), 0.0, 20.0, labels, selected = selected, onSelect = { selected = it })
        }
        Card(title = "Fatigue (sur 10)") {
            readout(point) { p -> p.fatigue?.let { "${Fmt.num(it)} / 10" } ?: "pas assez d'historique" }?.let { Text(it, Modifier.padding(bottom = 4.dp), color = pal.text, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
            LineChart(listOf(Series(points.map { it.fatigue }, pal.warn)), 0.0, 10.0, labels, selected = selected, onSelect = { selected = it })
        }
        Card(title = "Comment lire ces chiffres") {
            listOf(
                "Effort (sur 10) : la dureté d'une séance. Environ 2 pour un footing court, 4 à 5 pour une sortie longue facile, 6 à 8 pour du fractionné ou du tempo, 9 et plus pour une course longue.",
                "Charge 7 jours et 4 semaines (sur 20) : la somme des efforts de toutes tes séances sur la période, footings compris. 20 correspond à tes semaines (ou tes mois) les plus chargés depuis le début de l'historique.",
                "Fatigue (sur 10) : ta semaine comparée à la semaine moyenne de ton mois. Sous 3, tu es frais ; de 3 à 7, la charge est équilibrée ; au-delà de 8,5, c'est la surcharge.",
            ).forEach { Text(it, Modifier.padding(vertical = 5.dp), color = pal.muted, fontSize = 14.sp, lineHeight = 20.sp) }
        }
    }
}

@Composable
fun Legend(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 16.dp, height = 10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Muted(label, Modifier.padding(start = 6.dp))
    }
}
