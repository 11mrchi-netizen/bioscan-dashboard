package com.bioscan.hometerminal.env

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.bioscan.hometerminal.R
import com.bioscan.hometerminal.home.HomeAccess
import com.bioscan.hometerminal.home.homeSource
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

private const val TAG = "EnvCollector"

/**
 * Collects the home's environment around the clock. A foreground service of type specialUse: the Home APIs only give
 * the current value and its changes (no history), so someone has to be listening, and Google TV stops background work
 * that is not in the foreground. specialUse, not dataSync, because Android 15 limits dataSync services to 6 hours a day.
 *
 * Started by the activity and after boot ([BootReceiver]). It waits for Google Home access, then:
 * - listens to every sensor and queues the values [SampleGate] admits,
 * - queues a heartbeat every 5 minutes, so a gap in the data reads as "collector down", not "nothing changed",
 * - uploads the queue every minute (when signed in; otherwise it keeps queueing).
 */
class EnvCollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started == null) started = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        CollectorStatus.update { it.copy(running = false) }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        val uploader = EnvUploader(applicationContext)
        val source = homeSource
        val queuedAtStart = uploader.readings.size()
        CollectorStatus.update { it.copy(running = true, queued = queuedAtStart) }

        // Uploads run from the start, so readings queued before a restart go out even while access is still checked.
        scope.launch {
            while (true) {
                try {
                    val signedIn = uploader.flush()
                    CollectorStatus.update {
                        it.copy(
                            lastUploadAt = if (signedIn) Instant.now() else it.lastUploadAt,
                            lastError = if (signedIn) null else "Not signed in: readings are kept until you sign in.",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Upload failed", e)
                    CollectorStatus.update { it.copy(lastError = "Upload failed: ${e.message}") }
                }
                val queued = uploader.readings.size()
                CollectorStatus.update { it.copy(queued = queued) }
                delay(UPLOAD_EVERY_MS)
            }
        }

        source.access().retrying("access").first { it == HomeAccess.GRANTED }

        scope.launch {
            source.sensors().retrying("sensors").collect { sensors ->
                uploader.rememberSensors(sensors)
                CollectorStatus.update { it.copy(sensors = sensors) }
            }
        }
        scope.launch {
            val gate = SampleGate()
            source.measurements().retrying("measurements").collect { m ->
                CollectorStatus.received(m)
                if (gate.admit(m)) uploader.readings.add(listOf(EnvOutbox.encode(m)))
            }
        }
        scope.launch {
            while (true) {
                uploader.heartbeats.add(listOf(System.currentTimeMillis().toString()))
                delay(HEARTBEAT_EVERY_MS)
            }
        }
    }

    // A dropped subscription (the hub rebooting, the network going away) comes back on its own, with a growing pause.
    private fun <T> Flow<T>.retrying(what: String): Flow<T> = retryWhen { cause, attempt ->
        Log.w(TAG, "$what stopped, retrying", cause)
        CollectorStatus.update { it.copy(lastError = "Google Home $what: ${cause.message}") }
        delay(minOf(10 * 60_000L, 15_000L * (attempt + 1)))
        true
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.collector_channel), NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.collector_running))
            .setSmallIcon(R.drawable.ic_collector)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "collector"
        private const val NOTIFICATION_ID = 1
        private const val UPLOAD_EVERY_MS = 60_000L
        private const val HEARTBEAT_EVERY_MS = 5 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EnvCollectorService::class.java))
        }
    }
}
