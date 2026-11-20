package com.example.temperaturewidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.temperaturewidget.data.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Refreshes the widget whenever the screen turns on (e.g. the power button is pressed), so the
 * lock screen shows current temperatures.
 *
 * Screen-on broadcasts are only delivered to a running process, so this has to be a foreground
 * service. The app never asks for the notification permission, so on Android 13+ its notification
 * stays hidden and the service only shows up in the "Active apps" list.
 */
class WidgetRefreshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshLoop: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startRefreshLoop()
                Intent.ACTION_SCREEN_OFF -> stopRefreshLoop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_thermometer)
            .setContentTitle(getString(R.string.service_notification_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!TemperatureWidgetProvider.hasWidgets(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Refreshes immediately, then periodically for as long as the screen stays on. */
    private fun startRefreshLoop() {
        refreshLoop?.cancel()
        refreshLoop = scope.launch {
            while (isActive) {
                try {
                    TemperatureWidgetProvider.updateAll(this@WidgetRefreshService, SensorRepository.fetch(this@WidgetRefreshService))
                } catch (e: Exception) {
                    Log.w(TAG, "Screen-on refresh failed", e)
                    // Redraw the saved readings so any that are now too old turn red.
                    TemperatureWidgetProvider.updateAll(this@WidgetRefreshService, SensorRepository.cached(this@WidgetRefreshService))
                }
                delay(REFRESH_WHILE_SCREEN_ON_MS)
            }
        }
    }

    private fun stopRefreshLoop() {
        refreshLoop?.cancel()
        refreshLoop = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_MIN,
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WidgetRefreshService"
        private const val CHANNEL_ID = "widget_refresh"
        private const val NOTIFICATION_ID = 1
        private const val REFRESH_WHILE_SCREEN_ON_MS = 2 * 60 * 1000L

        /** Starts the service if a widget is placed. Android only allows this from the foreground or after boot. */
        fun start(context: Context) {
            if (!TemperatureWidgetProvider.hasWidgets(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WidgetRefreshService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start service from the background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WidgetRefreshService::class.java))
        }
    }
}
