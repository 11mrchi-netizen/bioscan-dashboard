package com.bioscan.fieldterminal.util

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// DAV-362. Google's code scanner runs in Play services: its own camera UI, no CAMERA
// permission, no camera code here. Returns the raw barcode text, or null when the
// user cancels or the scanner is unavailable (the typed-barcode field stays as the
// fallback), and reports why through `onError` when it fails rather than cancels.
suspend fun scanBarcode(context: Context, onError: (String) -> Unit = {}): String? = suspendCoroutine { cont ->
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { cont.resume(it.rawValue) }
        .addOnCanceledListener { cont.resume(null) }
        .addOnFailureListener { onError(it.message ?: "Scanner unavailable"); cont.resume(null) }
}
