package fr.foulee.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import java.time.LocalTime
import kotlin.concurrent.thread

/**
 * How a new run is noticed quickly. Three ways work together:
 * - an alarm every 5 minutes (30 minutes at night) that makes one small call to Garmin, and starts the full check only when a run is new;
 * - the 15 minute periodic work, kept as a safety net;
 * - the notifications of Garmin Connect: when the watch finishes syncing, the app asks Garmin at once (needs the notification access).
 * Android may still delay the alarm when the phone idles; the exemption from battery optimisation limits that.
 */
object Detection {
    const val ACTION_CHECK = "fr.foulee.app.CHECK"
    private const val EVERY_MS = 5 * 60_000L
    private const val NIGHT_EVERY_MS = 30 * 60_000L
    const val GARMIN_CONNECT = "com.garmin.android.apps.connectmobile"
    private const val DEBOUNCE_MS = 60_000L
    private var lastGarminTrigger = 0L

    fun schedule(context: Context) {
        RunCheckWorker.schedule(context)
        scheduleAlarm(context)
        Db(context).addLog("I", "detection", "surveillance activée (travail 15 min + alarme)")
    }

    fun cancel(context: Context) {
        RunCheckWorker.cancel(context)
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
        Db(context).addLog("I", "detection", "surveillance arrêtée")
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 1, Intent(context, CheckReceiver::class.java).setAction(ACTION_CHECK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The next round: not exact on purpose (an exact alarm needs a special permission), but allowed while the phone idles. */
    fun scheduleAlarm(context: Context) {
        val delay = if (LocalTime.now().hour < 6) NIGHT_EVERY_MS else EVERY_MS
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pending(context))
    }

    // Channels of Garmin Connect seen on the phone (dumpsys): "ACTIVITIES_CHANNEL_ID" (activities) and "ForegroundServiceChannel" (the link with
    // the watch). The others (weather, badges, audio prompts, system) say nothing about a run and are ignored.
    private val CHANNELS_OF_INTEREST = setOf("ACTIVITIES_CHANNEL_ID", "ForegroundServiceChannel")

    /**
     * A notification of Garmin Connect came or went. Only its channel is looked at, never its text. Those about activities or about the link with
     * the watch mean the watch has probably just synced: ask Garmin now, then again a bit later. Every event is written in the journal
     * (with its channel) so the real behaviour of Garmin Connect can be read afterwards.
     */
    fun onGarminNotification(context: Context, event: String, channel: String?) {
        val settings = Settings(context)
        if (!settings.loggedIn || !settings.initialized || !settings.autoCheck) return
        val now = System.currentTimeMillis()
        val interesting = channel in CHANNELS_OF_INTEREST
        if (!interesting) {
            // Weather, badges... : noted, nothing else
            Db(context).addCheck(now, "garmin-notif", "notification $event, canal ${channel ?: "?"} (ignorée)", 0, 0)
            return
        }
        // Garmin Connect updates its notification often while it syncs: one series per minute at most
        synchronized(this) {
            if (now - lastGarminTrigger < DEBOUNCE_MS) return
            lastGarminTrigger = now
        }
        Db(context).addCheck(now, "garmin-notif", "notification $event, canal $channel", 0, 0)
        Db(context).addLog("I", "detection", "notification Garmin Connect ($event, canal $channel) : vérification immédiate")
        thread {
            // The activity can take a few seconds to show on Garmin after the sync: three looks, stopping at the first that finds a run
            for (wait in longArrayOf(3_000, 45_000, 120_000)) {
                Thread.sleep(wait)
                val found = runCatching { RunChecker.check(context, "garmin").isNotEmpty() }.getOrDefault(false)
                if (found) return@thread
            }
        }
    }

    // ---- state shown in the app ----

    fun batteryUnrestricted(context: Context): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun garminListened(context: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Median and 90th percentile of the measured delays, in minutes (null without any measure). */
    fun delayStats(rows: List<DetectionRow>): Pair<Double, Double>? {
        val delays = rows.map { it.delayMinutes }.filter { it >= 0 }.sorted()
        if (delays.isEmpty()) return null
        val median = if (delays.size % 2 == 1) delays[delays.size / 2] else (delays[delays.size / 2 - 1] + delays[delays.size / 2]) / 2
        return median to delays[minOf(delays.size - 1, (delays.size * 0.9).toInt())]
    }
}

/** The alarm, and the restart of the alarm after a reboot or an update of the app. */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> if (Settings(app).loggedIn) {
                Db(app).addLog("I", "detection", "${intent.action?.substringAfterLast('.')} : surveillance relancée")
                Detection.schedule(app)
            }
            Detection.ACTION_CHECK -> {
                val settings = Settings(app)
                if (!settings.loggedIn || !settings.autoCheck) return
                // The next round first: a check that fails must not break the chain
                Detection.scheduleAlarm(app)
                val pending = goAsync()
                thread {
                    try {
                        // One small call here (a receiver has seconds, not minutes); the full check runs as expedited work when a run is new
                        if (RunChecker.quickLook(app, "alarme")) RunCheckWorker.runNow(app, "alarme")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

/** Listens to the notifications of Garmin Connect only (the user grants the access in the Android settings). */
class GarminSyncListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, "posée")
    override fun onNotificationRemoved(sbn: StatusBarNotification) = handle(sbn, "retirée")

    private fun handle(sbn: StatusBarNotification, event: String) {
        if (sbn.packageName == Detection.GARMIN_CONNECT) Detection.onGarminNotification(applicationContext, event, sbn.notification?.channelId)
    }
}
