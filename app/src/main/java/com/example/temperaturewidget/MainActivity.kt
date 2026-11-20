package com.example.temperaturewidget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.temperaturewidget.data.SensorRepository
import com.example.temperaturewidget.data.SensorSnapshot
import com.example.temperaturewidget.ui.theme.TemperatureWidgetTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TemperatureWidgetTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TemperatureScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun TemperatureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<SensorSnapshot?>(SensorRepository.cached(context)) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            try {
                snapshot = SensorRepository.fetch(context)
                error = null
                TemperatureWidgetProvider.updateAll(context, snapshot)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    LaunchedEffect(Unit) {
        WidgetRefreshService.start(context)
        reload()
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryUnrestricted = isBatteryUnrestricted(context)
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot?.readings?.forEach { reading ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(reading.name, style = MaterialTheme.typography.titleMedium)
                        Text("${reading.tempText}  ${reading.humidity}%", style = MaterialTheme.typography.titleMedium)
                    }
                } ?: Text(stringResource(R.string.loading))
                snapshot?.let {
                    Text(
                        stringResource(R.string.updated_at, it.fetchedAtText),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                error?.let {
                    Text(stringResource(R.string.error_loading, it), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { reload() }) { Text(stringResource(R.string.refresh)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Button(onClick = {
                    val manager = context.getSystemService(AppWidgetManager::class.java)
                    if (manager.isRequestPinAppWidgetSupported) {
                        manager.requestPinAppWidget(
                            ComponentName(context, TemperatureWidgetProvider::class.java), null, null,
                        )
                    }
                }) { Text(stringResource(R.string.add_widget)) }
            }
        }

        if (!batteryUnrestricted) {
            Text(stringResource(R.string.battery_hint), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                batteryLauncher.launch(intent)
            }) { Text(stringResource(R.string.allow_background)) }
        }

        Text(stringResource(R.string.lock_screen_hint), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun isBatteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
