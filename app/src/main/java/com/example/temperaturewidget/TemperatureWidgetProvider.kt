package com.example.temperaturewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.example.temperaturewidget.data.SensorReading
import com.example.temperaturewidget.data.SensorRepository
import com.example.temperaturewidget.data.SensorSnapshot
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * App widget listing the sensor temperatures. Declared for both the home screen and the
 * keyguard, so it can be placed on the lock screen on devices that support lock screen widgets.
 * Tapping the widget refreshes it.
 */
class TemperatureWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, SensorRepository.cached(context))
        TemperatureRefreshWorker.schedule(context)
        WidgetRefreshService.start(context)
        refreshAsync(context)
    }

    override fun onDisabled(context: Context) {
        TemperatureRefreshWorker.cancel(context)
        WidgetRefreshService.stop(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_REFRESH) {
            // A tap on the widget lets the app start the service, in case Android stopped it.
            WidgetRefreshService.start(context)
            refreshAsync(context)
        }
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                updateAll(context, SensorRepository.fetch(context))
            } catch (e: Exception) {
                Log.w("TemperatureWidget", "Widget refresh failed", e)
                updateAll(context, SensorRepository.cached(context))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_WIDGET_REFRESH = "com.example.temperaturewidget.WIDGET_REFRESH"

        /** Abbreviations to keep the widget narrow. Names not listed are shown as-is. */
        private val SHORT_NAMES = mapOf(
            "South" to "S",
            "North" to "N",
            // The bed and sofa icons speak for themselves, so no text.
            "Bedroom" to "",
            "Living room" to "",
        )

        /** Icons shown in front of the name. Sensors not listed get no icon. */
        private val ICONS = mapOf(
            "South" to R.drawable.ic_arrow_south,
            "North" to R.drawable.ic_arrow_north,
            "Bedroom" to R.drawable.ic_bed,
            "Living room" to R.drawable.ic_sofa,
        )

        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, TemperatureWidgetProvider::class.java))
                .isNotEmpty()

        fun updateAll(context: Context, snapshot: SensorSnapshot?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TemperatureWidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, buildViews(context, snapshot))
        }

        private fun buildViews(context: Context, snapshot: SensorSnapshot?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_temperatures)
            views.removeAllViews(R.id.rows)

            if (snapshot == null || snapshot.readings.isEmpty()) {
                views.setTextViewText(R.id.status, context.getString(R.string.loading))
                views.setViewVisibility(R.id.status, View.VISIBLE)
                views.setViewVisibility(R.id.rows, View.GONE)
            } else {
                views.setViewVisibility(R.id.status, View.GONE)
                views.setViewVisibility(R.id.rows, View.VISIBLE)
                val now = System.currentTimeMillis()
                // A grid with two sensors per row: S/N (outdoor), then bedroom/living room (indoor).
                snapshot.readings.chunked(2).forEachIndexed { index, pair ->
                    if (index > 0) views.addView(R.id.rows, RemoteViews(context.packageName, R.layout.widget_divider))
                    val row = RemoteViews(context.packageName, R.layout.widget_row)
                    fillCell(row, pair[0], R.id.icon1, R.id.name1, R.id.temp1, now)
                    val second = pair.getOrNull(1)
                    if (second != null) {
                        fillCell(row, second, R.id.icon2, R.id.name2, R.id.temp2, now)
                    } else {
                        row.setViewVisibility(R.id.cell2, View.INVISIBLE)
                    }
                    views.addView(R.id.rows, row)
                }
            }

            val refresh = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, TemperatureWidgetProvider::class.java).setAction(ACTION_WIDGET_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, refresh)
            return views
        }

        /** One grid cell: the icon and/or short name on top, the temperature below. */
        private fun fillCell(row: RemoteViews, reading: SensorReading, iconId: Int, nameId: Int, tempId: Int, now: Long) {
            ICONS[reading.name]?.let { icon ->
                row.setImageViewResource(iconId, icon)
                row.setViewVisibility(iconId, View.VISIBLE)
            }
            val name = SHORT_NAMES[reading.name] ?: reading.name
            if (name.isEmpty()) {
                row.setViewVisibility(nameId, View.GONE)
            } else {
                row.setTextViewText(nameId, name)
            }
            row.setTextViewText(tempId, shortTemp(reading))
            row.setTextColor(tempId, tempColor(reading, now))
        }

        /** Red when the sensor hasn't reported for over 5 minutes, otherwise white. */
        private fun tempColor(reading: SensorReading, now: Long) =
            if (reading.isStale(now)) STALE_COLOR else Color.WHITE

        private val STALE_COLOR = Color.rgb(0xFF, 0x6B, 0x6B)

        /** "7.1°" instead of "7.1°C" to save space. */
        private fun shortTemp(reading: SensorReading) =
            String.format(Locale.getDefault(), "%.1f°", reading.temp)
    }
}
