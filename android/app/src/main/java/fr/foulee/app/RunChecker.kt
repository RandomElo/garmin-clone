package fr.foulee.app

import android.content.Context

/** Looks for runs that appeared on Garmin since the last check and notifies each one. */
object RunChecker {
    private const val MAX_NOTIFICATIONS = 3
    private const val MAX_SEEN = 100

    /** After the first full sync: the runs already there are marked as seen, so they do not notify. */
    fun markAllSeen(context: Context) {
        val settings = Settings(context)
        settings.seenIds = Db(context).activities().filter { it.isRun }.map { it.id.toString() }.toSet()
        settings.initialized = true
    }

    /** Background check: sync the latest data, then notify the runs not seen yet. Returns the runs notified. */
    fun check(context: Context): List<Run> {
        val settings = Settings(context)
        if (!settings.loggedIn || !settings.initialized || !settings.autoCheck) return emptyList()
        settings.lastCheckAt = System.currentTimeMillis()
        // Another sync (started in the app) is running: it brings the same data, the next check will notify
        if (SyncEngine.run(context, "today", "auto") == null) return emptyList()

        val db = Db(context)
        val runs = db.activities().filter { it.isRun }.sortedByDescending { it.startLocal }
        val seen = settings.seenIds
        val fresh = runs.filter { it.id.toString() !in seen }.take(MAX_NOTIFICATIONS).reversed()

        // Save before notifying: a crash must not make the same run notify again
        settings.seenIds = (runs.map { it.id.toString() } + seen).distinct().take(MAX_SEEN).toSet()
        if (fresh.isEmpty()) return emptyList()

        val form = Insights.analyze(db).form
        return fresh.map { Run.from(it, form) }.onEach { Notifier.notify(context, it) }
    }

    /** Test button: notifies the latest run from the local data, without syncing. */
    fun notifyLatest(context: Context): Run? {
        val db = Db(context)
        val latest = db.activities().filter { it.isRun }.maxByOrNull { it.startLocal } ?: return null
        return Run.from(latest, Insights.analyze(db).form).also { Notifier.notify(context, it) }
    }
}
