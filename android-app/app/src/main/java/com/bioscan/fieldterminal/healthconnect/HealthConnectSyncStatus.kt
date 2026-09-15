package com.bioscan.fieldterminal.healthconnect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.HealthConnectSyncResult
import java.time.Instant

// Phase G2. Plain object holding the outcome of the last auto-sync
// (triggered once per app open from MainActivity's AuthGate), read by
// SettingsScreen's HEALTH CONNECT card -- same "no ViewModel, a small
// Compose-state-holding object" convention this app already uses elsewhere
// (e.g. GeminiApiKeyStore/MapSettingsStore), just in-memory rather than
// persisted, since this is a per-session status line, not a setting.
object HealthConnectSyncStatus {
    var lastResult by mutableStateOf<HealthConnectSyncResult?>(null)
        private set
    var lastSyncedAt by mutableStateOf<Instant?>(null)
        private set

    fun record(result: HealthConnectSyncResult) {
        lastResult = result
        if (result is HealthConnectSyncResult.Success) lastSyncedAt = Instant.now()
    }
}
