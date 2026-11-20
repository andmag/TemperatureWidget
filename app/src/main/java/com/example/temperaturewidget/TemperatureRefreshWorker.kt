package com.example.temperaturewidget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.temperaturewidget.data.SensorRepository
import java.util.concurrent.TimeUnit

/** Refreshes the widget in the background every 15 minutes (the shortest interval Android allows). */
class TemperatureRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        TemperatureWidgetProvider.updateAll(applicationContext, SensorRepository.fetch(applicationContext))
        Result.success()
    } catch (e: Exception) {
        Log.w("TemperatureWidget", "Background refresh failed", e)
        TemperatureWidgetProvider.updateAll(applicationContext, SensorRepository.cached(applicationContext))
        Result.retry()
    }

    companion object {
        private const val WORK_NAME = "temperature_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TemperatureRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
