package fr.foulee.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CHANNEL = "runs"

    fun notify(context: Context, run: Run) {
        val manager = NotificationManagerCompat.from(context)
        val channel = NotificationChannel(CHANNEL, "Fin de course", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(run.title)
            .setContentText(run.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(run.summary))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One notification per run (the id is the Garmin activity id, truncated)
        manager.notify(run.id.toInt(), notification)
    }
}
