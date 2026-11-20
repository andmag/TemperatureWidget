package com.example.temperaturewidget.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class SensorReading(
    val id: Int,
    val name: String,
    val temp: Double,
    val humidity: Int,
    val battery: Int,
    val updatedAt: Long,
) {
    val tempText: String get() = String.format(Locale.getDefault(), "%.1f°C", temp)

    /** True if the sensor hasn't reported for more than [maxAgeMs]. [updatedAt] is in seconds. */
    fun isStale(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 5 * 60 * 1000L): Boolean =
        now - updatedAt * 1000 > maxAgeMs
}

data class SensorSnapshot(
    val readings: List<SensorReading>,
    /** Time (ms) the app fetched this snapshot. */
    val fetchedAt: Long,
) {
    val fetchedAtText: String
        get() = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(fetchedAt))

    /** One-line summary, e.g. "South 9.1°C · North 8.5°C · Bedroom 22.0°C". */
    val summary: String
        get() = readings.joinToString(" · ") { "${it.name} ${it.tempText}" }
}

/**
 * Fetches sensor readings from the bene-sun API and caches the latest result
 * so the widget and notification can render instantly before a refresh completes.
 */
object SensorRepository {
    private const val API_URL = "https://bene-sun.cz/pub/api/v1/sensors"
    private const val PREFS = "sensor_cache"
    private const val KEY_JSON = "json"
    private const val KEY_FETCHED_AT = "fetched_at"

    suspend fun fetch(context: Context): SensorSnapshot = withContext(Dispatchers.IO) {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        val json = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val snapshot = SensorSnapshot(parse(json), System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, json)
            .putLong(KEY_FETCHED_AT, snapshot.fetchedAt)
            .apply()
        snapshot
    }

    fun cached(context: Context): SensorSnapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JSON, null) ?: return null
        return runCatching { SensorSnapshot(parse(json), prefs.getLong(KEY_FETCHED_AT, 0)) }.getOrNull()
    }

    private fun parse(json: String): List<SensorReading> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SensorReading(
                id = o.optInt("sensor_id"),
                name = o.optString("name", "Sensor ${i + 1}"),
                temp = o.optDouble("temp"),
                humidity = o.optInt("humidity"),
                battery = o.optInt("battery"),
                updatedAt = o.optLong("updatedAt"),
            )
        }.sortedBy { it.id }
    }
}
