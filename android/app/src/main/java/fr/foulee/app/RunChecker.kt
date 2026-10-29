package fr.foulee.app

import android.content.Context
import java.io.IOException

/** Looks for runs that appeared on Garmin since the last check and notifies each one. */
object RunChecker {
    private const val MAX_NOTIFICATIONS = 3
    private const val MAX_SEEN = 500
    /** A run that ended longer ago than this is never notified (it is only marked as seen): the notification is for the run that just ended. */
    private const val MAX_AGE_MS = 12 * 3_600_000L

    /** After the first full sync: the runs already there are marked as seen, so they do not notify. */
    fun markAllSeen(context: Context) {
        val settings = Settings(context)
        settings.seenIds = Db(context).activities().filter { it.isRun }.map { it.id.toString() }.toSet()
        settings.initialized = true
    }

    /** The check applies to someone signed in, past the first sync, who has not switched it off. */
    private fun applies(settings: Settings) = settings.loggedIn && settings.initialized && settings.autoCheck

    /** One small call to Garmin: the latest runs not notified yet. Null when the check does not apply. */
    fun unseen(context: Context): List<Long>? {
        val settings = Settings(context)
        if (!applies(settings)) return null
        val seen = settings.seenIds
        return Sync.recentRunIds(GarminAuth(context)).filter { it.toString() !in seen }
    }

    /**
     * The cheap part of the background check, written in the journal when nothing is new.
     * Returns true when a new run is on Garmin: the caller then starts [check], which syncs and notifies.
     */
    fun quickLook(context: Context, source: String): Boolean {
        val settings = Settings(context)
        if (!applies(settings)) return false
        val db = Db(context)
        val t0 = System.currentTimeMillis()
        settings.lastCheckAt = t0
        return try {
            val news = unseen(context).orEmpty()
            if (news.isEmpty()) db.addCheck(t0, source, "rien", 0, System.currentTimeMillis() - t0)
            news.isNotEmpty()
        } catch (e: AuthException) {
            db.addCheck(t0, source, "connexion", 0, System.currentTimeMillis() - t0)
            db.addLog("W", "verif", "$source : connexion Garmin refusée (${e.message})")
            false
        } catch (e: IOException) {
            db.addCheck(t0, source, "réseau", 0, System.currentTimeMillis() - t0)
            db.addLog("W", "verif", "$source : réseau (${e.javaClass.simpleName}: ${e.message})")
            false
        }
    }

    /** End of the session, in epoch milliseconds (Garmin gives the start in local time and the duration). */
    private fun endedAt(a: ActivityRow): Long = runCatching {
        java.time.LocalDateTime.parse(a.startLocal.replace(' ', 'T')).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + ((a.duration ?: 0.0) * 1000).toLong()
    }.getOrDefault(0L)

    /**
     * Background check: look at Garmin with one small call, and only when a run is new sync the latest data and notify it.
     * Every check is written in the journal ([source]: where it came from), and so is the delay between the end of each run and its detection.
     */
    fun check(context: Context, source: String): List<Run> {
        val settings = Settings(context)
        if (!applies(settings)) return emptyList()
        val db = Db(context)
        val t0 = System.currentTimeMillis()
        settings.lastCheckAt = t0
        fun log(result: String, news: Int = 0) {
            db.addCheck(t0, source, result, news, System.currentTimeMillis() - t0)
            if (result != "rien") db.addLog(if (result == "course") "I" else "W", "verif", "$source : $result${if (news > 0) " ($news)" else ""}")
        }
        try {
            if (unseen(context).isNullOrEmpty()) {
                log("rien")
                return emptyList()
            }
            // Another sync (started in the app) is running: it brings the same data, the next check will notify
            if (SyncEngine.run(context, "today", "auto") == null) {
                log("occupé")
                return emptyList()
            }

            val runs = db.activities().filter { it.isRun }.sortedByDescending { it.startLocal }
            val seen = settings.seenIds
            var fresh = runs.filter { it.id.toString() !in seen }.take(MAX_NOTIFICATIONS).reversed()

            // Save before notifying: a crash must not make the same run notify again
            settings.seenIds = (runs.map { it.id.toString() } + seen).distinct().take(MAX_SEEN).toSet()
            if (fresh.isEmpty()) {
                log("rien")
                return emptyList()
            }

            val now = System.currentTimeMillis()
            // Garmin can list an old run again (re-imported, edited): it is marked as seen, not announced
            val (recent, old) = fresh.partition { now - endedAt(it) <= MAX_AGE_MS }
            old.forEach { db.addLog("I", "detection", "course ${it.id} du ${it.startLocal} ignorée : trop ancienne pour une notification") }
            if (recent.isEmpty()) {
                log("rien")
                return emptyList()
            }
            fresh = recent
            fresh.forEach {
                db.addDetection(it.id, endedAt(it), now, source)
                db.addLog("I", "detection", "course ${it.id} finie à ${Fmt.dateTime(endedAt(it))}, détectée ${(now - endedAt(it)) / 60000} min plus tard (source $source)")
            }
            log("course", fresh.size)
            val form = Insights.analyze(db).form
            // The run is rarely on Strava yet: the title and description go once it appears there
            StravaFollowUp.start(context, fresh.map { it.id })
            return fresh.map { Run.from(it, form) }.onEach { Notifier.notify(context, it) }
        } catch (e: AuthException) {
            log("connexion")
            db.addLog("W", "verif", "$source : ${e.message}")
            throw e
        } catch (e: IOException) {
            log("réseau")
            db.addLog("W", "verif", "$source : ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /** Test button: notifies the latest run from the local data, without syncing. */
    fun notifyLatest(context: Context): Run? {
        val db = Db(context)
        val latest = db.activities().filter { it.isRun }.maxByOrNull { it.startLocal } ?: return null
        return Run.from(latest, Insights.analyze(db).form).also { Notifier.notify(context, it) }
    }
}
