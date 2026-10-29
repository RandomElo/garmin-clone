package fr.foulee.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * A new run is on Garmin well before it is on Strava (Strava gets it a few minutes later, and the home server a bit after that). Once a run is
 * detected, this asks the server every minute for what it has, until the run shows up there and its title and description are sent, or until
 * [GIVE_UP_MS] have passed. The waiting list is kept in the settings, so it survives the app being closed or the phone restarting.
 */
object StravaFollowUp {
    private const val NAME = "strava-follow-up"
    private const val GIVE_UP_MS = 45 * 60_000L
    private const val EVERY_SECONDS = 60L

    fun start(context: Context, ids: List<Long>) {
        val settings = Settings(context)
        if (settings.stravaUrl.isNullOrBlank() || settings.stravaKey == null || ids.isEmpty()) return
        val now = System.currentTimeMillis()
        settings.stravaWaiting = settings.stravaWaiting + ids.map { "$it:$now" }
        Db(context).addLog("I", "strava", "attente de ${ids.size} course(s) sur Strava : une vérification par minute pendant 45 min")
        // One request at the end of the run: the server then syncs every minute for 30 minutes, so the rounds below only have to read
        kotlin.concurrent.thread {
            val db = Db(context)
            StravaSync.settingsLog = { db.addLog("W", "strava", it) }
            try {
                db.addLog("I", "strava", "demande de synchro au serveur : ${StravaSync.requestSync(settings)}")
            } catch (e: Exception) {
                db.addLog("W", "strava", "demande de synchro impossible : ${e.message}")
            }
        }
        schedule(context, 10)
    }

    fun schedule(context: Context, seconds: Long = EVERY_SECONDS) {
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setInitialDelay(seconds, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** One round. Returns true while some run is still waited for. */
    fun round(context: Context): Boolean {
        val settings = Settings(context)
        val db = Db(context)
        val waiting = settings.stravaWaiting.mapNotNull { e -> e.split(":").takeIf { it.size == 2 }?.let { it[0].toLongOrNull() to it[1].toLongOrNull() } }
            .filter { it.first != null && it.second != null }.map { it.first!! to it.second!! }
        if (waiting.isEmpty()) return false
        val now = System.currentTimeMillis()
        val expired = waiting.filter { now - it.second > GIVE_UP_MS }
        expired.forEach { db.addLog("W", "strava", "course ${it.first} : toujours pas sur Strava après 45 min, j'arrête d'attendre") }
        var open = waiting - expired.toSet()
        if (open.isEmpty()) {
            settings.stravaWaiting = emptySet()
            return false
        }
        try {
            StravaSync.settingsLog = { db.addLog("W", "strava", it) }
            StravaSync.run(settings, db)
            val activities = db.activities()
            val matched = open.filter { (id, _) -> activities.firstOrNull { it.id == id }?.strava != null }
            if (matched.isNotEmpty()) {
                db.addLog("I", "strava", "course(s) ${matched.joinToString { it.first.toString() }} trouvée(s) sur Strava : envoi du texte")
                StravaTexts.push(settings, db)?.let { db.addLog("I", "strava", it) }
                open = open - matched.toSet()
            }
        } catch (e: Exception) {
            db.addLog("W", "strava", "attente : ${e.message ?: e.javaClass.simpleName}")
        }
        settings.stravaWaiting = open.map { "${it.first}:${it.second}" }.toSet()
        return open.isNotEmpty()
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (round(applicationContext)) schedule(applicationContext)
            return Result.success()
        }
    }
}
