package com.bioscan.fieldterminal.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

private const val MAX_DIMENSION = 1024
private const val JPEG_QUALITY = 80

// Downscales and recompresses a photo before it goes over the network -- a
// raw camera capture can be several MB; food-estimation accuracy doesn't
// need full camera resolution, and this keeps both the request size and this
// app's mobile-data usage well bounded.
fun readAndCompressImage(context: Context, uri: Uri): ByteArray {
    val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: throw IllegalStateException("Could not read the selected image")

    val scale = MAX_DIMENSION.toFloat() / maxOf(original.width, original.height)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
    } else {
        original
    }

    return ByteArrayOutputStream().use { stream ->
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        stream.toByteArray()
    }
}

// A camera-capture intent needs somewhere to write the full-resolution photo
// before this app reads it back -- a content:// Uri via FileProvider, since
// a plain file:// Uri can't be handed to another app's process on API 24+.
// Written into the cache dir (not persisted; readAndCompressImage() reads it
// once, then the file is disposable).
fun createCameraCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera_photos").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    PendingCameraCapture.record(context, uri, file)
    return uri
}

/**
 * The capture the camera was last asked for, kept until its result arrives. The camera app can get this app killed;
 * if the result is lost on the way back, the food form offers the photo (PHOTO JUST TAKEN) when it opens again.
 */
object PendingCameraCapture {
    private const val PREFS = "ft_camera"
    const val MAX_AGE_MS = 15 * 60_000L

    fun record(context: Context, uri: Uri, file: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("uri", uri.toString()).putString("path", file.absolutePath).putLong("at", System.currentTimeMillis()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The recorded capture when its file holds a photo taken in the last [MAX_AGE_MS]; null otherwise. */
    fun stranded(context: Context, now: Long = System.currentTimeMillis()): Uri? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = p.getString("uri", null) ?: return null
        val file = p.getString("path", null)?.let(::File) ?: return null
        return if (isUsableCapture(if (file.exists()) file.length() else 0L, now - p.getLong("at", 0L))) Uri.parse(uri) else null
    }
}

/** A capture is worth offering when the camera wrote something and it is recent. */
fun isUsableCapture(sizeBytes: Long, ageMs: Long): Boolean = sizeBytes > 0 && ageMs in 0..PendingCameraCapture.MAX_AGE_MS

// DAV-85: unlike readAndCompressImage(), a lab report upload can be a PDF
// (BitmapFactory can't decode those) as well as a photo, and Gemini accepts
// either as raw bytes with the real mime type -- no recompression needed
// for either kind here.
fun readFileBytes(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Could not read the selected file")
