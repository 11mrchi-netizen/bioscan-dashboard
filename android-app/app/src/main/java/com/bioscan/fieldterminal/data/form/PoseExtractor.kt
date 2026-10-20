package com.bioscan.fieldterminal.data.form

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.bioscan.fieldterminal.domain.form.ArgbFrame
import com.bioscan.fieldterminal.domain.form.PoseClip
import com.bioscan.fieldterminal.domain.form.PoseFrame
import com.bioscan.fieldterminal.domain.form.PosePoint
import com.bioscan.fieldterminal.domain.form.nextPoseTimestampMs
import com.bioscan.fieldterminal.domain.form.onRealClock
import com.bioscan.fieldterminal.domain.form.yuv420ToArgb
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable

// Running form analyser (docs/running-form/README.md): a video file on the phone to a PoseClip. The video is decoded
// frame by frame with MediaCodec (in order, no seeking), each frame turned upright and scaled down (ClipFrames.kt), and
// MediaPipe's Pose Landmarker reads it in video mode. Nothing leaves the phone.

/** MediaPipe's pose model, downloaded at build time into the app's assets (app/build.gradle.kts, downloadPoseModel). */
const val POSE_MODEL_ASSET = "pose_landmarker_full.task"

/** What the file says about itself: its slow-motion capture rate, length and how it should be turned to show upright. */
data class ClipMeta(val captureFps: Double?, val durationMs: Long?, val rotation: Int)

fun readClipMeta(context: Context, uri: Uri): ClipMeta {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        ClipMeta(
            captureFps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.takeIf { it > 0 },
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0,
        )
    } finally {
        r.release()
    }
}

/** The pose clip, with each frame's time in the file (µs) and its rotation, so a frame can be fetched again for display. */
class ExtractedClip(val clip: PoseClip, val fileTimesUs: List<Long>, val rotation: Int)

/**
 * The clip's pose, frame by frame. `onProgress` gets the share of the clip read (0 to 1). Runs off the main thread;
 * cancelling the calling coroutine stops it between frames.
 */
suspend fun extractPoseClip(context: Context, uri: Uri, onProgress: (Double) -> Unit = {}): ExtractedClip = withContext(Dispatchers.Default) {
    val job = coroutineContext[Job]
    val meta = readClipMeta(context, uri)
    PoseDetector(context).use { detector ->
        val frames = ArrayList<PoseFrame>()
        val fileTimes = ArrayList<Long>()
        var width = 0
        var height = 0
        var firstUs = -1L
        var lastMs: Long? = null
        decodeVideoFrames(context, uri, meta.rotation) { frame, timeUs ->
            job?.ensureActive()
            if (firstUs < 0) firstUs = timeUs
            val relUs = timeUs - firstUs
            val stampMs = nextPoseTimestampMs(relUs, lastMs).also { lastMs = it }
            frames += PoseFrame(relUs / 1000.0, detector.detect(frame, stampMs))
            fileTimes += timeUs
            width = frame.width
            height = frame.height
            meta.durationMs?.takeIf { it > 0 }?.let { onProgress((relUs / 1000.0 / it).coerceIn(0.0, 1.0)) }
            true
        }
        onProgress(1.0)
        ExtractedClip(onRealClock(PoseClip(frames, width, height), meta.captureFps), fileTimes, meta.rotation)
    }
}

/** The frames at the given file times (µs), upright and scaled as the pose extractor saw them; decoding stops after the last. */
suspend fun framesAt(context: Context, uri: Uri, rotation: Int, timesUs: Set<Long>): Map<Long, ArgbFrame> = withContext(Dispatchers.Default) {
    val job = coroutineContext[Job]
    val out = HashMap<Long, ArgbFrame>()
    val last = timesUs.maxOrNull() ?: return@withContext out
    decodeVideoFrames(context, uri, rotation) { frame, timeUs ->
        job?.ensureActive()
        // The converter reuses its pixel array, so a kept frame is copied.
        if (timeUs in timesUs) out[timeUs] = ArgbFrame(frame.pixels.copyOf(), frame.width, frame.height)
        timeUs < last
    }
    out
}

/** MediaPipe Pose Landmarker in video mode, on the GPU when the phone allows, else the CPU. */
class PoseDetector(context: Context) : Closeable {
    private val landmarker: PoseLandmarker = try {
        create(context, Delegate.GPU)
    } catch (e: Exception) {
        create(context, Delegate.CPU)
    }
    private var bitmap: Bitmap? = null

    private fun create(context: Context, delegate: Delegate): PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(POSE_MODEL_ASSET).setDelegate(delegate).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build(),
    )

    /** The 33 points in the frame's pixels, or null when no runner was found. */
    fun detect(frame: ArgbFrame, timestampMs: Long): List<PosePoint>? {
        val bmp = bitmap?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also { bitmap?.recycle(); bitmap = it }
        bmp.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        val pose = landmarker.detectForVideo(BitmapImageBuilder(bmp).build(), timestampMs).landmarks().firstOrNull() ?: return null
        return pose.map { p ->
            PosePoint(p.x() * frame.width.toDouble(), p.y() * frame.height.toDouble(), p.visibility().orElse(1f).toDouble())
        }
    }

    override fun close() {
        landmarker.close()
        bitmap?.recycle()
        bitmap = null
    }
}

/**
 * Decodes the first video track in presentation order and hands each frame over upright and scaled down, with its time
 * (µs). `onFrame` returns false to stop.
 */
internal fun decodeVideoFrames(context: Context, uri: Uri, rotation: Int, onFrame: (ArgbFrame, Long) -> Boolean) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IllegalArgumentException("The file has no video track.")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val converter = FrameConverter(rotation)
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    var keepGoing = true
                    if (info.size > 0) codec.getOutputImage(outIndex)?.use { keepGoing = onFrame(converter.convert(it), info.presentationTimeUs) }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (!keepGoing || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
    } finally {
        extractor.release()
    }
}

/** Copies a decoded image's planes (reusing its buffers between frames) and converts them to an upright ARGB frame. */
private class FrameConverter(private val rotation: Int) {
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)
    private var argb: IntArray? = null

    private fun copy(plane: Image.Plane, into: ByteArray): ByteArray {
        val buf = plane.buffer.duplicate().apply { rewind() }
        val out = if (into.size == buf.remaining()) into else ByteArray(buf.remaining())
        buf.get(out)
        return out
    }

    fun convert(image: Image): ArgbFrame {
        val planes = image.planes
        y = copy(planes[0], y); u = copy(planes[1], u); v = copy(planes[2], v)
        val crop = image.cropRect
        val frame = yuv420ToArgb(
            y, planes[0].rowStride, planes[0].pixelStride,
            u, v, planes[1].rowStride, planes[1].pixelStride,
            crop.width(), crop.height(), rotation, left = crop.left, top = crop.top, into = argb,
        )
        argb = frame.pixels
        return frame
    }
}
