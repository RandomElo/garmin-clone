package fr.foulee.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

class ReviewItem(val activity: ActivityRow, val detected: String, val score: Double, val reasons: List<String>)

/**
 * "À classer": runs that look like a race, found by plain rules (no guess hidden from the user): each criterion met adds points
 * and is shown as a reason. A run is listed from REVIEW_SCORE points until a kind is chosen for it by hand. Port of review.ts.
 */
object Review {
    private class RaceDistance(val meters: Double, val label: String)

    private val raceDistances = listOf(RaceDistance(5000.0, "5 km"), RaceDistance(10_000.0, "10 km"), RaceDistance(15_000.0, "15 km"), RaceDistance(21_097.0, "semi-marathon"), RaceDistance(42_195.0, "marathon"))
    private const val DISTANCE_TOLERANCE = 0.03
    private const val REVIEW_SCORE = 2.5
    private val raceWords = Regex("\\b(semi|marathon|trail|cross|corrida|foul[ée]es?|course de|10 ?km|5 ?km|race|comp[ée]t\\w*|chrono|run \\d+)\\b", RegexOption.IGNORE_CASE)

    // Average HR of a race, as a share of max HR: lower over a marathon than over a 5 km
    private fun raceHrShare(meters: Double) = if (meters >= 35_000) 0.8 else if (meters >= 18_000) 0.86 else 0.88

    fun list(activities: List<ActivityRow>, analysis: Analysis): List<ReviewItem> {
        val runs = activities.filter { it.isRun }.sortedByDescending { it.startLocal }
        val max = analysis.refs.max
        val items = mutableListOf<ReviewItem>()
        for (a in runs) {
            if (a.manualCategory != null || a.distance == null || a.averageSpeed == null) continue
            val d = a.distance
            val reasons = mutableListOf<String>()
            var score = 0.0

            raceDistances.firstOrNull { abs(d - it.meters) / it.meters <= DISTANCE_TOLERANCE }?.let {
                score += 1
                reasons += "Distance de course : ${it.label}"
            }
            if (a.averageHr != null && a.averageHr >= max * raceHrShare(d)) {
                score += 1
                reasons += "FC moyenne ${(a.averageHr / max * 100).roundToInt()} % de ta FC max"
            }
            // Faster than nearly all the runs of a similar distance (±20 %) of the two years before
            val from = LocalDate.parse(a.startLocal.take(10)).minusYears(2).toString()
            val similar = runs.filter { it !== a && it.distance != null && it.averageSpeed != null && abs(it.distance - d) / d <= 0.2 && it.startLocal < a.startLocal && it.startLocal >= from }
            if (similar.size >= 10) {
                val slower = similar.count { it.averageSpeed!! < a.averageSpeed } / similar.size.toDouble()
                if (slower >= 0.95) {
                    score += 1.5
                    reasons += "Plus rapide que ${(slower * 100).roundToInt()} % de tes sorties de cette distance"
                }
            }
            // Garmin's default names ("Paris Course à pied") say nothing: only words typical of a race count
            if (raceWords.containsMatchIn(a.name.replace(Regex("course à pied", RegexOption.IGNORE_CASE), ""))) {
                score += 1.5
                reasons += "Nom : « ${a.name} »"
            }
            // What Strava says about the same outing (home server): Eloi's own label and words, strong evidence
            a.strava?.let { s ->
                if (s.hasLabel("Race")) {
                    score += 2.5
                    reasons += "Label Strava : Course"
                }
                val words = (s.name + " " + s.description).replace(Regex("course à pied", RegexOption.IGNORE_CASE), "")
                if (raceWords.containsMatchIn(words) && !raceWords.containsMatchIn(a.name.replace(Regex("course à pied", RegexOption.IGNORE_CASE), ""))) {
                    score += 1.5
                    reasons += "Strava : « ${s.name} »"
                }
            }
            if (a.intervalTitle == null && (a.aerobicTe ?: 0.0) >= 4.2) {
                score += 0.5
                reasons += "Training Effect aérobie ${Insights.dec(a.aerobicTe!!, 1)}"
            }
            val start = LocalDateTime.parse(a.startLocal.replace(' ', 'T'))
            val hour = start.hour + start.minute / 60.0
            if ((start.dayOfWeek == DayOfWeek.SATURDAY || start.dayOfWeek == DayOfWeek.SUNDAY) && hour in 7.0..11.5) {
                score += 0.5
                reasons += "Départ le week-end en matinée"
            }
            if (score >= REVIEW_SCORE) items += ReviewItem(a, Categories.detected(a), score, reasons)
        }
        return items
    }
}

/** A square map with the run on it. null: the track is still loading. */
@Composable
private fun RouteThumb(track: List<Pair<Double, Double>>?, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(pal.track), contentAlignment = Alignment.Center) {
        when {
            track == null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            track.size < 2 -> Muted("Pas de tracé", size = 12)
            else -> RouteMap(track, Modifier.fillMaxSize(), compact = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(state: AppState, snap: Snapshot, onBack: () -> Unit, onOpen: (ActivityRow) -> Unit) {
    val pal = LocalPal.current
    // Settled runs leave the list at once, without waiting for the reload
    var done by remember { mutableStateOf(setOf<Long>()) }
    // The rules compare every run with the others: computed off the main thread, with a spinner meanwhile
    val found by produceState<List<ReviewItem>?>(null, snap) { value = withContext(Dispatchers.Default) { Review.list(snap.activities, snap.analysis) } }
    val items = (found ?: emptyList()).filter { it.activity.id !in done }
    // GPS tracks: from the base, or fetched once for the runs that have none yet (null = still loading, empty = none)
    var tracks by remember { mutableStateOf(mapOf<Long, List<Pair<Double, Double>>>()) }
    LaunchedEffect(items.map { it.activity.id }.toSet()) {
        withContext(Dispatchers.IO) {
            for (item in items) {
                val id = item.activity.id
                if (id in tracks) continue
                val track = state.db.track(id) ?: runCatching { Sync.detail(state.auth, state.db, id).track }.getOrNull() ?: emptyList()
                tracks = tracks + (id to track)
            }
        }
    }
    Page {
        Row(Modifier.clickable(onClick = onBack), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp), tint = pal.accent)
            Text("Activités", Modifier.padding(start = 6.dp), color = pal.accent, fontWeight = FontWeight.Medium)
        }
        if (found == null) {
            Section("À classer", "Recherche des courses…")
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) { CircularProgressIndicator() }
            return@Page
        }
        Section("À classer", "${items.size} course${if (items.size > 1) "s" else ""} à vérifier")
        Muted("Ces sorties ressemblent à une compétition d'après des règles simples : distance officielle, FC moyenne très haute, allure parmi tes plus rapides, nom de course, Training Effect très élevé, départ le week-end en matinée. Une fois classées, elles ne reviennent plus.", size = 14)
        if (items.isEmpty()) {
            Card { Text("Tout est classé.", color = pal.muted) }
            return@Page
        }
        Card {
            items.forEachIndexed { i, item -> key(item.activity.id) {
                val a = item.activity
                if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(pal.border))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RouteThumb(tracks[a.id], Modifier.size(104.dp))
                        Column(Modifier.padding(start = 14.dp).clickable { onOpen(a) }) {
                            Text(a.name, fontWeight = FontWeight.Medium, color = pal.text, maxLines = 1)
                            Muted(Fmt.date(a.startLocal) + " " + a.startLocal.take(4) + " · " + a.startLocal.drop(11).take(5), Modifier.padding(top = 2.dp), size = 13)
                            Text(Fmt.km(a.distance), Modifier.padding(top = 6.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                            Text(Fmt.pace(a.averageSpeed), color = pal.muted, fontSize = 15.sp)
                        }
                    }
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.reasons.forEach { r ->
                            Row(Modifier.clip(RoundedCornerShape(50)).background(pal.track).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Check, null, Modifier.size(13.dp), tint = pal.accent)
                                Text(r, Modifier.padding(start = 4.dp), fontSize = 12.sp, color = pal.text)
                            }
                        }
                    }
                    Muted("Classer comme", Modifier.padding(top = 12.dp, bottom = 6.dp), size = 13)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Every kind a run can be: a choice is final, the run leaves the list. The detected kind says "(détecté)"
                        listOf("competition", "interval", "long", "easy").forEach { c ->
                            val label = Categories.label(c) + if (c == item.detected) " (détecté)" else ""
                            val pick: () -> Unit = { done = done + a.id; state.setCategory(a.id, c) }
                            if (c == "competition") Button(pick) {
                                Icon(Icons.Filled.EmojiEvents, null, Modifier.size(16.dp))
                                Text(label, Modifier.padding(start = 6.dp))
                            } else OutlinedButton(pick) { Text(label) }
                        }
                    }
                }
            } }
        }
        Muted("Cartes : © contributeurs OpenStreetMap", size = 11)
    }
}
