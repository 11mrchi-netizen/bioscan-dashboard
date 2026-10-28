package com.bioscan.fieldterminal.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.bioscan.fieldterminal.domain.SCALE_COMPANY_ID
import com.bioscan.fieldterminal.domain.ScaleReading
import com.bioscan.fieldterminal.domain.decodeScaleBroadcast
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** True when the phone has Bluetooth and it is switched on. */
fun bluetoothReady(context: Context): Boolean =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

/**
 * Every finished weigh-in the body scale advertises while this flow is collected. The caller needs the scan permission
 * (BLUETOOTH_SCAN on Android 12+, location before) and Bluetooth on; the scan stops when collection stops.
 */
@SuppressLint("MissingPermission")
fun scaleReadings(context: Context): Flow<ScaleReading> = callbackFlow {
    val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner
    if (scanner == null) {
        close(IllegalStateException("Bluetooth is off or not available"))
        return@callbackFlow
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val payload = result.scanRecord?.getManufacturerSpecificData(SCALE_COMPANY_ID) ?: return
            decodeScaleBroadcast(payload)?.let { trySend(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            close(IllegalStateException("Bluetooth scan failed ($errorCode)"))
        }
    }
    val filter = ScanFilter.Builder().setManufacturerData(SCALE_COMPANY_ID, byteArrayOf(), byteArrayOf()).build()
    scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    awaitClose { runCatching { scanner.stopScan(callback) } }
}
