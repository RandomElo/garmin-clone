package fr.foulee.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The check for new runs: every 15 minutes as a safety net, and at once when the alarm or Garmin Connect found something. */
class RunCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val source = inputData.getString("source") ?: "travail"
        Db(applicationContext).addLog("I", "travail", "démarré ($source, essai ${runAttemptCount + 1})")
        RunChecker.check(applicationContext, source)
        Result.success()
    } catch (e: AuthException) {
        // Needs a new login in the app: retrying would only hit Garmin for nothing
        Result.failure()
    } catch (e: IOException) {
        // Garmin unreachable (phone off the network): try again later
        Result.retry()
    }

    /** Needed for the expedited work on Android 11 and older: a discreet notification while the check runs. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("checks", "Vérification des courses", NotificationManager.IMPORTANCE_MIN))
        val notification = NotificationCompat.Builder(applicationContext, "checks")
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle("Vérification des courses")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(2, notification)
    }

    companion object {
        private const val NAME = "run-check"
        private const val NOW = "run-check-now"

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(NOW)
        }

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Android does not allow less than 15 minutes between two periodic runs: the alarm ([Detection]) does the faster rounds. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RunCheckWorker>(15, TimeUnit.MINUTES).setConstraints(network).setInputData(workDataOf("source" to "travail")).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** The check right now, without waiting for the next round (expedited: starts within seconds even when the phone is idle). */
        fun runNow(context: Context, source: String) {
            val request = OneTimeWorkRequestBuilder<RunCheckWorker>()
                .setConstraints(network)
                .setInputData(workDataOf("source" to source))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
