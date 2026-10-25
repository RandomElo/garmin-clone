package fr.foulee.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

class RunCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        RunChecker.check(applicationContext)
        Result.success()
    } catch (e: AuthException) {
        // Needs a new login in the app: retrying would only hit Garmin for nothing
        Result.failure()
    } catch (e: IOException) {
        // Garmin unreachable (phone off the network): try again later
        Result.retry()
    }

    companion object {
        private const val NAME = "run-check"

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        /** Android does not allow less than 15 minutes between two periodic runs. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RunCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
