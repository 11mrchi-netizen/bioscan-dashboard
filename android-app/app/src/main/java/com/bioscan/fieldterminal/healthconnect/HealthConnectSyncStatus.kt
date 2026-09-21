package com.bioscan.fieldterminal.healthconnect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.HealthConnectSyncResult
import com.bioscan.fieldterminal.data.HealthConnectWriteBackResult
import java.time.Instant

// Phase G2 / DAV-154. Plain object holding the outcome of the last auto-sync
// and write-back, read by SettingsScreen's HEALTH CONNECT card.
object HealthConnectSyncStatus {
    var lastResult by mutableStateOf<HealthConnectSyncResult?>(null)
        private set
    var lastSyncedAt by mutableStateOf<Instant?>(null)
        private set

    var lastWriteBackResult by mutableStateOf<HealthConnectWriteBackResult?>(null)
        private set
    var lastWriteBackAt by mutableStateOf<Instant?>(null)
        private set

    fun record(result: HealthConnectSyncResult) {
        lastResult = result
        if (result is HealthConnectSyncResult.Success) lastSyncedAt = Instant.now()
    }

    fun recordWriteBack(result: HealthConnectWriteBackResult) {
        lastWriteBackResult = result
        if (result is HealthConnectWriteBackResult.Success) lastWriteBackAt = Instant.now()
    }
}
