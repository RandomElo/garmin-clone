package fr.foulee.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One line of a running sync: pending, running, success, error or skipped.
 * [planned] items at [rate] seconds each give the expected duration, until the step knows its real total.
 */
class Step(val key: String, val label: String, val planned: Int, val rate: Double, private val rateKey: String = key) {
    private var state by mutableStateOf("pending")
    var status: String
        get() = state
        set(v) {
            if (v == "running") startedAt = System.currentTimeMillis()
            if (v != "running" && state == "running") finishedAt = System.currentTimeMillis()
            state = v
        }
    var startedAt = 0L
        private set
    var finishedAt = 0L
        private set
    var count by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    /** What the step did, in a few words, shown under its line when it went well. */
    var note by mutableStateOf<String?>(null)

    /** Expected duration in seconds */
    val expected: Double get() = (if (total > 0) total else planned).coerceAtLeast(1) * rate

    private val over get() = status == "success" || status == "error" || status == "skipped"

    /** 0 to 1 */
    fun fraction(now: Long): Double = when {
        over -> 1.0
        status != "running" -> 0.0
        total > 0 -> minOf(count.toDouble() / total, 1.0)
        else -> minOf((now - startedAt) / 1000.0 / expected, 0.9)
    }

    /** Seconds left: the pace seen in this step once it has a few items, the expected one before. */
    fun remaining(now: Long): Double = when {
        over -> 0.0
        status != "running" -> expected
        total > 0 && count >= 3 -> (total - count) * ((now - startedAt) / 1000.0 / count)
        total > 0 -> expected * (1 - count.toDouble() / total)
        else -> maxOf(expected - (now - startedAt) / 1000.0, 1.0)
    }

    /** Remembers the pace of a step that went well, for the next estimate (smoothed with the previous one). */
    fun recordRate(settings: Settings) {
        if (status != "success" || finishedAt <= startedAt) return
        val items = if (planned > 1 || key == "activities" || key == "details" || key == "wellness") total else 1
        if (items <= 0) return
        val seconds = (finishedAt - startedAt) / 1000.0 / items
        settings.setStepRate(rateKey, settings.stepRate(rateKey)?.let { (it + seconds) / 2 } ?: seconds)
    }
}

/** Seconds per item (or per step for the one-call steps) before any sync was measured. */
private val defaultRates = mapOf("auth" to 2.0, "activities" to 0.015, "details" to 0.2, "wellness" to 0.55, "fitness" to 4.0, "strava" to 3.0, "detect" to 0.5, "texts" to 3.0)

/** Live state of the sync in progress, read by the screens. */
class RunState(val mode: String, val trigger: String, val startedAt: Long, plan: Map<String, Int>, settings: Settings) {
    val steps = listOf(
        step("auth", "Connexion à Garmin", plan, settings),
        step("activities", "Activités", plan, settings),
        step("details", "Courbes et tracés GPS", plan, settings),
        step("wellness", "Santé (sommeil, VFC…)", plan, settings),
        step("fitness", "Forme (VO2 max, statut)", plan, settings),
        step("strava", "Strava (import des sorties)", plan, settings),
        step("detect", "Détection des séances", plan, settings),
        step("texts", "Envoi des textes à Strava", plan, settings),
    )

    // A "today" sync and a "full" one do not cost the same per item (few big calls against many small ones): each keeps its own pace
    private fun step(key: String, label: String, plan: Map<String, Int>, settings: Settings) =
        Step(key, label, plan[key] ?: 1, settings.stepRate("$key-$mode") ?: defaultRates.getValue(key), "$key-$mode")

    /** 0-100, weighted by the expected duration of each step */
    val progress: Int
        get() {
            val now = System.currentTimeMillis()
            val all = steps.sumOf { it.expected }.coerceAtLeast(1.0)
            return Math.round(steps.sumOf { it.expected * it.fraction(now) } / all * 100).toInt()
        }

    /** Remaining seconds: what is left of the running step plus the expected duration of the steps to come. */
    fun etaSeconds(): Double? {
        val now = System.currentTimeMillis()
        // A step that is skipped or over costs nothing; the first seconds are too uncertain to announce anything
        if (now - startedAt < 4000) return null
        return steps.sumOf { it.remaining(now) }
    }
}

object SyncEngine {
    private val lock = AtomicBoolean(false)

    /** The sync in progress, or null. */
    var current by mutableStateOf<RunState?>(null)
        private set

    /**
     * Runs one sync. [mode]: "today" (latest activities, last days of wellness, a few missing days) or
     * "full" (whole history and the whole wellness window). [trigger]: "manual", "auto" or "login".
     * Returns the final status ("success", "partial" or "error"), or null without doing anything when another sync is running.
     */
    fun run(context: Context, mode: String, trigger: String): String? {
        if (!lock.compareAndSet(false, true)) return null
        val settings = Settings(context)
        val db = Db(context)
        val auth = GarminAuth(context)
        // What the sync is expected to do, for the time estimate: counted from the base before anything is fetched
        val full = mode == "full"
        val plan = mapOf(
            "activities" to if (full) (settings.garminTotal.takeIf { it > 0 } ?: 800).coerceAtMost(800) else 20,
            "details" to (if (db.activityCount() == 0) (if (full) 160 else 10) else Sync.detailsPlan(db, if (full) 40 else 10, if (full) 120 else 0)),
            "wellness" to Sync.wantedDays(db, if (trigger == "auto") 0 else 3, if (full) 120 else 15).size,
        )
        val state = RunState(mode, trigger, System.currentTimeMillis(), plan, settings)
        current = state
        StravaSync.settingsLog = { db.addLog("W", "strava", it) }
        db.addLog("I", "synchro", "début : $mode, déclenchée par $trigger, prévu ${plan.entries.joinToString(", ") { "${it.key} ${it.value}" }}")
        val (authStep, activitiesStep, detailsStep, wellnessStep, fitnessStep) = state.steps
        var newActivities = 0
        var days = 0
        var fatal = false
        try {
            authStep.status = "running"
            try {
                auth.refreshProfile()
                authStep.status = "success"
            } catch (e: Exception) {
                authStep.status = "error"
                authStep.error = e.message ?: e.javaClass.simpleName
                fatal = true
            }

            if (fatal) {
                activitiesStep.status = "skipped"
                detailsStep.status = "skipped"
                wellnessStep.status = "skipped"
                fitnessStep.status = "skipped"
            } else {
                activitiesStep.status = "running"
                val before = db.activityCount()
                try {
                    val full = mode == "full"
                    val total = if (full) Sync.garminTotal(auth).also { settings.garminTotal = it } else 0
                    Sync.activities(auth, db, full, total) { done, all -> activitiesStep.count = done; activitiesStep.total = all }
                    newActivities = db.activityCount() - before
                    activitiesStep.status = "success"
                } catch (e: Exception) {
                    activitiesStep.status = "error"
                    activitiesStep.error = e.message ?: e.javaClass.simpleName
                    if (e is AuthException) fatal = true
                }

                if (fatal) {
                    detailsStep.status = "skipped"
                    wellnessStep.status = "skipped"
                    fitnessStep.status = "skipped"
                } else {
                    // The curves and GPS tracks of the most recent activities, so they open without waiting
                    detailsStep.status = "running"
                    try {
                        Sync.details(auth, db, if (mode == "full") 40 else 10, if (mode == "full") 120 else 0) { done, all -> detailsStep.count = done; detailsStep.total = all }
                        detailsStep.status = "success"
                    } catch (e: Exception) {
                        detailsStep.status = "error"
                        detailsStep.error = e.message ?: e.javaClass.simpleName
                        if (e is AuthException) fatal = true
                    }
                }

                if (fatal) {
                    wellnessStep.status = "skipped"
                    fitnessStep.status = "skipped"
                } else {
                    wellnessStep.status = "running"
                    try {
                        // A background check only refreshes the last days when a run just appeared
                        val recent = if (trigger == "auto" && newActivities == 0) 0 else 3
                        val wanted = Sync.wantedDays(db, recent, if (mode == "full") 120 else 15)
                        wellnessStep.total = wanted.size
                        val name = settings.displayName ?: throw AuthException("Profil Garmin introuvable.")
                        days = Sync.daily(auth, db, name, wanted) { done, all -> wellnessStep.count = done; wellnessStep.total = all }
                        wellnessStep.status = "success"
                    } catch (e: Exception) {
                        wellnessStep.status = "error"
                        wellnessStep.error = e.message ?: e.javaClass.simpleName
                    }
                    // Training status and VO2 max change slowly: not worth a call at every background check
                    if (trigger == "auto" && newActivities == 0) {
                        fitnessStep.status = "skipped"
                    } else {
                        fitnessStep.status = "running"
                        try {
                            Sync.fitness(auth, db, settings.displayName ?: throw AuthException("Profil Garmin introuvable."))
                            fitnessStep.status = "success"
                        } catch (e: Exception) {
                            fitnessStep.status = "error"
                            fitnessStep.error = e.message ?: e.javaClass.simpleName
                        }
                    }
                }
            }

            // The home Strava server is optional and never makes the sync fail: if it does not answer, its line says why and Garmin's data stands
            val stravaStep = state.steps[5]
            when {
                settings.stravaUrl.isNullOrBlank() -> stravaStep.status = "skipped"
                trigger == "auto" && newActivities == 0 -> stravaStep.status = "skipped"
                else -> {
                    stravaStep.status = "running"
                    try {
                        val result = StravaSync.run(settings, db)
                        stravaStep.count = result.count
                        stravaStep.error = result.warning
                        stravaStep.status = "success"
                        settings.stravaStatus = result.warning
                    } catch (e: Exception) {
                        stravaStep.status = "skipped"
                        stravaStep.error = e.message ?: e.javaClass.simpleName
                        settings.stravaStatus = stravaStep.error
                    }
                    settings.stravaStatusAt = System.currentTimeMillis()
                }
            }

            // The runs of the last days, sorted: what was found and what goes to Strava
            val detectStep = state.steps[6]
            if (fatal || (trigger == "auto" && newActivities == 0)) {
                detectStep.status = "skipped"
            } else {
                detectStep.status = "running"
                try {
                    val since = java.time.LocalDate.now().minusDays(3).toString()
                    val recent = db.activities().filter { it.isRun && it.startLocal.take(10) >= since }
                    detectStep.note = if (recent.isEmpty()) "aucune course ces 3 derniers jours" else recent.groupingBy { Categories.label(it.category).lowercase() }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
                    detectStep.status = "success"
                } catch (e: Exception) {
                    detectStep.status = "skipped"
                    detectStep.error = e.message ?: e.javaClass.simpleName
                }
            }

            // Titles and descriptions of the new sessions: never makes the sync fail either
            val textsStep = state.steps[7]
            if (settings.stravaUrl.isNullOrBlank() || settings.stravaKey == null || state.steps[5].status != "success") {
                textsStep.status = "skipped"
            } else {
                textsStep.status = "running"
                try {
                    textsStep.note = StravaTexts.push(settings, db)?.substringAfter(" : ") ?: "rien de nouveau à envoyer"
                    textsStep.status = "success"
                } catch (e: Exception) {
                    textsStep.status = "skipped"
                    textsStep.error = e.message ?: e.javaClass.simpleName
                }
            }

            state.steps.forEach { it.recordRate(settings) }
            state.steps.filter { it.status != "pending" }.forEach {
                val seconds = if (it.finishedAt > it.startedAt) " en ${(it.finishedAt - it.startedAt) / 1000.0} s" else ""
                val level = if (it.status == "error") "E" else if (it.error != null) "W" else "I"
                db.addLog(level, "synchro", "${it.key} : ${it.status}$seconds${if (it.total > 0) ", ${it.count}/${it.total}" else ""}${it.note?.let { n -> " ($n)" } ?: ""}${it.error?.let { e -> " : $e" } ?: ""}")
            }
            val failed = state.steps.count { it.status == "error" }
            val status = when {
                failed == 0 -> "success"
                state.steps.any { it.status == "success" } && !fatal -> "partial"
                else -> "error"
            }
            if (status != "error") settings.lastSuccessAt = System.currentTimeMillis()
            // A background check that found nothing is not worth a line in the history
            if (trigger != "auto" || newActivities > 0 || status != "success") {
                db.addRun(RunRecord(state.startedAt, System.currentTimeMillis(), mode, trigger, status, state.steps.firstOrNull { it.error != null }?.error, newActivities, days))
            }
            db.addLog(if (status == "success") "I" else "W", "synchro", "fin : $status en ${(System.currentTimeMillis() - state.startedAt) / 1000.0} s, $newActivities nouvelle(s) activité(s)")
            return status
        } catch (e: Exception) {
            db.addLog("E", "synchro", "interrompue : ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            current = null
            lock.set(false)
        }
    }
}
