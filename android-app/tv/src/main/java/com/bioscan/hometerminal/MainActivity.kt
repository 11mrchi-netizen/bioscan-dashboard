package com.bioscan.hometerminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.hometerminal.auth.TvGoogleAuth
import com.bioscan.hometerminal.data.Supabase
import com.bioscan.hometerminal.env.CollectorStatus
import com.bioscan.hometerminal.env.EnvCollectorService
import com.bioscan.hometerminal.env.EnvMetric
import com.bioscan.hometerminal.env.SensorInfo
import com.bioscan.hometerminal.home.HomeAccess
import com.bioscan.hometerminal.home.homeSource
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Phase E1's only screen: whether the collector can work (signed in, Google Home access) and what it is reading. The
 * bioscan skin for the Streamer comes later; this is a status page, operated with the remote.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeSource.bind(this)
        EnvCollectorService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { StatusScreen() }
            }
        }
    }
}

@Composable
private fun StatusScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by Supabase.client.auth.sessionStatus.collectAsState()
    val access by homeSource.access().collectAsState(initial = HomeAccess.CHECKING)
    val status by CollectorStatus.state.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("HOME TERMINAL", style = MaterialTheme.typography.headlineMedium)
            Text("Environment collector", style = MaterialTheme.typography.titleMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bioscan account", style = MaterialTheme.typography.labelLarge)
                    when (val s = session) {
                        is SessionStatus.Authenticated -> Text("Signed in as ${s.session.user?.email ?: "unknown"}")
                        is SessionStatus.Initializing -> Text("Checking…")
                        else -> Button(onClick = {
                            scope.launch { message = TvGoogleAuth.signIn(context)?.takeIf { it.isNotEmpty() } }
                        }) { Text("Sign in with Google") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Google Home", style = MaterialTheme.typography.labelLarge)
                    when (access) {
                        HomeAccess.GRANTED -> Text("Access given")
                        HomeAccess.CHECKING -> Text("Checking…")
                        HomeAccess.NO_SDK -> Text("This build has no Google Home APIs SDK. See docs/home-environment/README.md.")
                        HomeAccess.UNAVAILABLE -> Text("Google Home can't share access on this device.")
                        HomeAccess.NOT_GRANTED -> Button(onClick = {
                            scope.launch { message = homeSource.requestAccess() }
                        }) { Text("Allow access to Google Home") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Collector", style = MaterialTheme.typography.labelLarge)
                    Text(if (status.running) "Running" else "Stopped")
                    Text("Waiting to upload: ${status.queued}")
                    Text("Last upload: ${status.lastUploadAt?.let(::ago) ?: "never"}")
                    status.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        Column(Modifier.weight(1f)) {
            Text("Sensors", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (status.sensors.isEmpty()) {
                Text(if (access == HomeAccess.GRANTED) "No temperature, humidity, light or air-quality sensor found yet." else "Sensors appear once Google Home access is given.")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(status.sensors, key = { it.id }) { sensor -> SensorCard(sensor, status) }
            }
        }
    }
}

@Composable
private fun SensorCard(sensor: SensorInfo, status: CollectorStatus.State) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sensor.name, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(sensor.room, sensor.vendor, sensor.product).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            val values = EnvMetric.entries.mapNotNull { metric -> status.latest[sensor.id to metric] }
            if (values.isEmpty()) Text("No value yet", style = MaterialTheme.typography.bodyMedium)
            values.forEach { m ->
                Text("${label(m.metric)}  ${format(m.metric, m.value)} ${m.metric.unit}   (${ago(m.at)})", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun label(metric: EnvMetric) = when (metric) {
    EnvMetric.TEMPERATURE_C -> "Temperature"
    EnvMetric.HUMIDITY_PCT -> "Humidity"
    EnvMetric.ILLUMINANCE_LUX -> "Light"
    EnvMetric.CO2_PPM -> "CO₂"
    EnvMetric.PM25_UGM3 -> "PM2.5"
    EnvMetric.AIR_QUALITY -> "Air quality"
}

private fun format(metric: EnvMetric, value: Double) = when (metric) {
    EnvMetric.TEMPERATURE_C, EnvMetric.HUMIDITY_PCT -> "%.1f".format(value)
    EnvMetric.AIR_QUALITY -> listOf("", "good", "fair", "moderate", "poor", "very poor", "extremely poor").getOrElse(value.toInt()) { "?" }
    else -> "%.0f".format(value)
}

private fun ago(at: Instant): String {
    val s = Duration.between(at, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}
