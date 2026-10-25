package fr.foulee.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** An activity as the home Strava server knows it: Strava's own name, description and labels, which Garmin does not have. */
class StravaActivity(
    val id: String, val sport: String, val name: String, val description: String, val labels: List<String>,
    /** Local start, "2026-10-24 10:27:45", the same shape as the Garmin one */
    val startLocal: String,
    val totalSeconds: Double?, val movingSeconds: Double?, val distance: Double?, val elevation: Double?, val modifiedAt: String?,
    /** The Garmin activity it was matched with, when there is one */
    val garminId: Long? = null,
) {
    val isRun get() = sport.contains("Run")

    fun hasLabel(label: String) = labels.any { it.equals(label, ignoreCase = true) }
}

class StravaError(message: String) : Exception(message)

/**
 * Talks to the home Strava server (contract in docs/notes). The server gives the texts and labels of the Strava side,
 * the app keeps the detection of the kind of run: nothing here classifies anything, the texts are only evidence.
 */
object StravaSync {
    private const val START_TOLERANCE_SECONDS = 120L
    // Strava post-processes the data (moving time, trimmed pauses), so its durations never quite equal the watch's: the start time (day and hour)
    // is what identifies an outing. The duration only breaks a tie, and rejects an absurd gap (a 2-minute stub against a 1-hour run).
    private const val DURATION_SANITY = 0.5
    private const val CLEAR_MARGIN = 0.25
    // Some outings (indoor ones around the clock change) reach Strava with their local start shifted by exactly one hour. Such a shift only
    // counts when the duration is nearly identical, and it ranks after any outing that starts at the right hour.
    private const val HOUR_SHIFT_SECONDS = 3600L
    private const val HOUR_SHIFT_DURATION = 0.02
    private val localFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** "strava.home" becomes "http://strava.home". Null for an empty field. */
    fun normalize(url: String?): String? {
        val trimmed = url?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun get(base: String, key: String, path: String) = request("GET", base, key, path, null)

    fun post(base: String, key: String, path: String, body: String, readTimeoutMs: Int = 30_000) = request("POST", base, key, path, body, readTimeoutMs)

    /** One call to the server; one attempt only, a failure is reported and the next sync tries again. */
    private fun request(method: String, base: String, key: String, path: String, body: String?, readTimeoutMs: Int = 30_000): JSONObject {
        val headers = mutableMapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json"
        val response = try {
            Http.request(method, base + path, headers, body, readTimeoutMs)
        } catch (e: IOException) {
            val host = runCatching { java.net.URL(base).host }.getOrDefault(base)
            throw StravaError(
                when (e) {
                    is java.net.UnknownHostException -> "Le téléphone ne trouve pas « $host » : son DNS ne le connaît pas. Vérifie le DNS du Wi-Fi (un seul serveur, celui d'AdGuard) et le DNS privé d'Android."
                    is java.net.SocketTimeoutException -> "« $host » ne répond pas (délai dépassé). Le téléphone est-il sur le Wi-Fi de la maison ou en VPN ?"
                    is java.net.ConnectException -> "Connexion refusée par « $host » : le serveur est éteint ou le port est bloqué."
                    else -> "Serveur Strava injoignable (${e.message ?: e.javaClass.simpleName})."
                },
            )
        }
        when {
            response.code == 401 || response.code == 403 -> throw StravaError("Clé refusée par le serveur Strava.")
            response.code == 503 -> throw StravaError(SESSION_WARNING)
            response.code !in 200..299 -> throw StravaError("Serveur Strava : HTTP ${response.code}.")
        }
        return try {
            JSONObject(response.body)
        } catch (e: Exception) {
            throw StravaError("Réponse inattendue du serveur Strava.")
        }
    }

    private fun JSONObject.number(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun parse(o: JSONObject): StravaActivity? {
        val id = o.optString("stravaId").takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val start = o.optString("debut").takeIf { it.isNotEmpty() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
        // The server sends the local start too; without it, the phone's own time zone (the same one the watch uses) converts the UTC start
        val local = o.optString("debutLocal").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it.removeSuffix("Z")) }.getOrNull() }
            ?: start.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        val labels = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
        return StravaActivity(
            id, o.optString("sport"), o.optString("nom"), o.optString("description").takeIf { it != "null" } ?: "", labels, local.format(localFormat),
            o.number("dureeTotaleS"), o.number("dureeMouvementS"), o.number("distanceM"), o.number("deniveleM"), o.optString("modifieLe").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    /** What came back from one fetch: how many activities, and a warning when the server's own Strava session is not open. */
    class Result(val count: Int, val warning: String?)

    private const val SESSION_WARNING = "La session Strava du serveur est à refaire sur son adresse. Les textes déjà reçus restent."

    /** Fetches what changed since the last time, stores it and matches it with the Garmin activities. */
    fun run(settings: Settings, db: Db): Result {
        val base = normalize(settings.stravaUrl) ?: throw StravaError("Pas d'adresse de serveur Strava.")
        val key = settings.stravaKey ?: throw StravaError("Pas de clé pour le serveur Strava.")
        val since = settings.stravaCursor
        val json = get(base, key, "/api/activites" + (since?.let { "?depuis=" + Http.encode(it) } ?: ""))
        val items = json.optJSONArray("activites") ?: throw StravaError("Réponse inattendue du serveur Strava.")
        val fetched = (0 until items.length()).mapNotNull { parse(items.getJSONObject(it)) }
        db.upsertStrava(fetched)
        match(db)
        // The cursor moves only once everything received is in the base, and a null one keeps the old value
        if (!json.isNull("suivant")) json.optString("suivant").takeIf { it.isNotEmpty() && it != "null" }?.let { settings.stravaCursor = it }
        val session = json.optString("sessionStrava").takeIf { it.isNotEmpty() && it != "null" }
        return Result(fetched.size, if (session != null && session != "connecte") SESSION_WARNING else null)
    }

    /** Strava's sport as a Garmin-style type key, so that the lists, icons and categories treat it like any other activity. */
    fun garminType(sport: String) = when (sport) {
        "Run", "VirtualRun" -> "running"
        "TrailRun" -> "trail_running"
        "WeightTraining", "Crossfit" -> "strength_training"
        "Ride", "VirtualRide", "EBikeRide", "GravelRide", "MountainBikeRide" -> "cycling"
        "Swim" -> "lap_swimming"
        "Walk" -> "walking"
        "Hike" -> "hiking"
        else -> "other"
    }

    /** One label per outing, never two. Competition and training come first (Race, then Workout), the long run last. */
    private val LABEL_PRIORITY = listOf("Race", "Workout", "LongRun")

    /** The only label an outing keeps when it carries several: the highest in [LABEL_PRIORITY]. */
    fun single(labels: List<String>): List<String> =
        LABEL_PRIORITY.firstOrNull { p -> labels.any { it.equals(p, ignoreCase = true) } }?.let { listOf(it) } ?: labels.take(1)

    /** Strava's labels in the app's words. */
    fun labelFr(label: String) = when (label) {
        "Race" -> "Course"
        "Workout" -> "Entraînement"
        "LongRun" -> "Sortie longue"
        else -> label
    }

    /** One call to see whether the address and the key work, without keeping anything. */
    fun test(url: String?, key: String?): String {
        val base = normalize(url) ?: throw StravaError("Adresse vide.")
        if (key.isNullOrBlank()) throw StravaError("Clé vide.")
        val json = get(base, key.trim(), "/api/activites?depuis=" + Http.encode(OffsetDateTime.now(ZoneOffset.UTC).toString()))
        return when (json.optString("sessionStrava")) {
            "connecte", "" -> "Serveur joint, session Strava ouverte."
            else -> SESSION_WARNING
        }
    }

    fun epoch(local: String): Long? = runCatching { LocalDateTime.parse(local, localFormat).toEpochSecond(ZoneOffset.UTC) }.getOrNull()

    /** How far the watch duration is from Strava's elapsed or moving time (0 = equal, 0 when unknown): the closer of the two counts. */
    private fun durationGap(garmin: Double?, strava: StravaActivity): Double {
        if (garmin == null || garmin <= 0) return 0.0
        val references = listOfNotNull(strava.totalSeconds, strava.movingSeconds).filter { it > 0 }
        if (references.isEmpty()) return 0.0
        return references.minOf { Math.abs(garmin - it) / it }
    }

    /**
     * Matches by day and hour: the start, in local time on both sides (so the clock change breaks nothing), to within two minutes.
     * The duration is only a tie-breaker and a sanity check. With several fits, the best one wins only when it is clearly the best
     * (start and duration together); otherwise nothing is guessed.
     */
    fun match(db: Db) {
        val garmin = db.activities().mapNotNull { a -> epoch(a.startLocal)?.let { Triple(a.id, it, a.duration) } }
        val taken = db.matchedGarminIds().toMutableSet()
        for (s in db.stravaActivities().filter { it.garminId == null }) {
            val at = epoch(s.startLocal) ?: continue
            // score: start gap (0 to 1 over the two minutes) plus the duration gap; lower is better
            val fits = garmin
                .filter { (id, _, _) -> id !in taken }
                .mapNotNull { (id, start, duration) ->
                    val gap = abs(start - at)
                    val durationGap = durationGap(duration, s)
                    when {
                        gap <= START_TOLERANCE_SECONDS && durationGap <= DURATION_SANITY -> id to (gap.toDouble() / START_TOLERANCE_SECONDS + durationGap)
                        duration != null && abs(gap - HOUR_SHIFT_SECONDS) <= START_TOLERANCE_SECONDS && durationGap <= HOUR_SHIFT_DURATION -> id to (2.0 + durationGap)
                        else -> null
                    }
                }
                .sortedBy { it.second }
            val best = fits.firstOrNull() ?: continue
            val next = fits.getOrNull(1)
            if (next != null && next.second - best.second < CLEAR_MARGIN) continue
            db.setStravaMatch(s.id, best.first)
            taken += best.first
        }
    }
}

/** An outing the check would send: the app's kind, who chose it, and how the server finds the activity. */
class CheckItem(val key: String, val activity: ActivityRow, val category: String, val choice: String, val json: JSONObject) {
    /** The same outing with another kind chosen by hand: it is then sent as a manual choice. */
    fun changedTo(newCategory: String) = CheckItem(key, activity, newCategory, "manuel", JSONObject(json.toString()).put("categorie", newCategory).put("choix", "manuel"))
}

/** The Strava outing closest in time to a Garmin one, with the gaps the comparison shows. */
class Nearest(val strava: StravaActivity, val startGapSeconds: Long, val durationGapPercent: Long?, val distanceGapPercent: Long?)

/** The server's answer for one outing. [status]: inchange, a-corriger, planifie, introuvable, ambigu, ignore or invalide. */
class CheckResult(val item: CheckItem, val status: String, val before: List<String>, val after: List<String>, val error: String?, val hint: String? = null, val nearest: Nearest? = null)

/** [applied]: the answer to a sending that was asked to write (otherwise a preview: nothing was written, whatever the server mode). */
class CheckReport(val simulation: Boolean, val results: List<CheckResult>, val sentAt: Long, val applied: Boolean = false)

/**
 * "Vérifier Strava": sends the app's kind of each outing, the server answers which labels it would set. The app never writes to Strava itself.
 * While the server answers simulation, nothing is marked as sent, so the same outings go again the day the writing is turned on.
 */
object StravaCheck {
    private const val WINDOW_DAYS = 90L
    // The contract allows 2000 outings per preview and 50 per writing call; a write takes about ten seconds, so writing goes by small calls
    private const val PREVIEW_CHUNK = 2000
    private const val WRITE_CHUNK = 5
    private const val WRITE_TIMEOUT_MS = 120_000

    /** The Strava label an outing of this kind carries (one label at most). */
    fun labelsOf(internalCategory: String) = when (internalCategory) {
        "competition" -> listOf("Race")
        "interval" -> listOf("Workout")
        "long" -> listOf("LongRun")
        else -> emptyList()
    }

    /** The app's kind in the contract's words. */
    fun contractName(category: String) = when (category) {
        "competition" -> "competition"
        "interval" -> "fractionne"
        "long" -> "sortie_longue"
        "easy" -> "footing"
        "strength" -> "musculation"
        else -> "autre"
    }

    /** The outings whose kind (or whose chooser) changed since the last real sending or refusal: the last 90 days, or everything with [all]. */
    fun candidates(db: Db, activities: List<ActivityRow>, all: Boolean): List<CheckItem> {
        val since = LocalDate.now().minusDays(WINDOW_DAYS).toString()
        val sent = db.stravaSent()
        return activities.filter { all || it.startLocal.take(10) >= since }.sortedByDescending { it.startLocal }.mapNotNull { a ->
            val category = contractName(a.category)
            val choice = if (a.manualCategory != null) "manuel" else "auto"
            val key = a.strava?.let { "s${it.id}" } ?: "g${a.id}"
            if (sent[key] == (category to choice)) return@mapNotNull null
            val json = JSONObject().put("categorie", category).put("choix", choice)
            if (a.strava != null) json.put("stravaId", a.strava.id)
            else {
                // Not matched yet: the server finds it by its local start and its elapsed time
                val duration = a.duration ?: return@mapNotNull null
                json.put("debutLocal", a.startLocal.take(19).replace(' ', 'T')).put("dureeTotaleS", Math.round(duration))
            }
            CheckItem(key, a, category, choice, json)
        }
    }

    private fun clockOf(seconds: Double) = Fmt.clock(seconds)

    /** The closest Strava outing the app knows, or null when there is none with a readable start. */
    fun nearest(a: ActivityRow, stravas: List<StravaActivity>): Nearest? {
        val at = StravaSync.epoch(a.startLocal) ?: return null
        val (s, gap) = stravas.mapNotNull { s -> StravaSync.epoch(s.startLocal)?.let { s to (it - at) } }.minByOrNull { Math.abs(it.second) } ?: return null
        val seconds = s.totalSeconds ?: s.movingSeconds
        fun percent(garmin: Double?, strava: Double?) = if (garmin != null && strava != null && strava > 0) Math.round(Math.abs(garmin - strava) / strava * 100) else null
        return Nearest(s, gap, percent(a.duration, seconds), percent(a.distance, s.distance))
    }

    /**
     * Why an outing was not found: the closest Strava outing the app knows, with the gap in start time and in duration, or the fact that
     * Strava has nothing around that time (which points at the Strava import, not at the matching).
     */
    fun explain(a: ActivityRow, stravas: List<StravaActivity>): String {
        if (StravaSync.epoch(a.startLocal) == null) return "Heure de départ illisible."
        if (stravas.isEmpty()) return "Aucune activité Strava n'est encore sur le téléphone : lance une synchro avec le serveur."
        val near = nearest(a, stravas) ?: return "Aucune activité Strava avec une heure lisible."
        val s = near.strava
        val gap = near.startGapSeconds
        if (Math.abs(gap) > 6 * 3600) return "Aucune sortie Strava à moins de 6 h de ce départ (la plus proche : ${s.name.ifBlank { "sans nom" }}, ${Fmt.date(s.startLocal)} ${s.startLocal.drop(11).take(5)}). Elle n'est peut-être pas sur Strava."
        val minutes = gap / 60
        val seconds = Math.abs(gap) % 60
        val timeGap = if (gap == 0L) "même heure" else "${if (gap > 0) "+" else "−"}${Math.abs(minutes)} min ${seconds} s"
        val strava = s.totalSeconds ?: s.movingSeconds
        val duration = if (a.duration != null && strava != null && near.durationGapPercent != null) ", durée Garmin ${clockOf(a.duration)} contre ${clockOf(strava)} sur Strava (${near.durationGapPercent} % d'écart)" else ""
        return "Plus proche sur Strava : « ${s.name.ifBlank { "sans nom" }} », ${s.startLocal.drop(11).take(5)} ($timeGap)$duration. Tolérance : départ à 2 min près, la durée ne sert qu'à départager."
    }

    private fun labels(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.optString(it) }.filter { it.isNotEmpty() }

    /** [apply] false: a preview, the server only says what it would change. true: the server writes (unless it is in simulation). */
    fun run(settings: Settings, db: Db, items: List<CheckItem>, apply: Boolean = false, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): CheckReport {
        val base = StravaSync.normalize(settings.stravaUrl) ?: throw StravaError("Pas d'adresse de serveur Strava.")
        val key = settings.stravaKey ?: throw StravaError("Pas de clé pour le serveur Strava.")
        var simulation = false
        val results = mutableListOf<CheckResult>()
        val stravas = db.stravaActivities()
        var sent = 0
        for (chunk in items.chunked(if (apply) WRITE_CHUNK else PREVIEW_CHUNK)) {
            onProgress(sent, items.size)
            val body = JSONObject().put("appliquer", apply).put("activites", JSONArray(chunk.map { it.json })).toString()
            val json = StravaSync.post(base, key, "/api/labels", body, if (apply) WRITE_TIMEOUT_MS else 30_000)
            // Anything but an explicit false counts as a simulation (the safe reading), and one simulated chunk makes the whole check one
            if (!json.has("simulation") || json.optBoolean("simulation", true)) simulation = true
            val answers = json.optJSONArray("resultats") ?: throw StravaError("Réponse inattendue du serveur Strava.")
            if (answers.length() != chunk.size) throw StravaError("Le serveur a répondu pour ${answers.length()} sorties sur ${chunk.size}.")
            chunk.forEachIndexed { i, item ->
                val o = answers.getJSONObject(i)
                val status = o.optString("statut")
                results += CheckResult(
                    item, status, labels(o.optJSONArray("labelsAvant")), labels(o.optJSONArray("labelsApres")), o.optString("erreur").takeIf { it.isNotEmpty() && it != "null" },
                    if (status == "introuvable" || status == "ambigu") explain(item.activity, stravas) else null,
                    if (status == "introuvable" || status == "ambigu") nearest(item.activity, stravas) else null,
                )
            }
            sent += chunk.size
            // Really written (or already right): remembered at once, so a failure further on loses nothing. Not found, ambiguous, invalid or failed: asked again next time.
            if (apply && !simulation) db.markStravaSent(results.takeLast(chunk.size).filter { it.status in setOf("inchange", "ecrit", "planifie", "ignore", "conflit") }.map { Triple(it.item.key, it.item.category, it.item.choice) })
        }
        return CheckReport(simulation, results, System.currentTimeMillis(), apply)
    }
}
