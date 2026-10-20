package com.bioscan.fieldterminal.domain.form

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The pure parts of reading a video for the pose extractor (data/form/): turning a decoded frame into an upright picture
// small enough for the pose model, and putting slow motion back on its real clock.

/**
 * The longest side frames are scaled to before pose detection. MediaPipe resizes to 256 px internally, so a larger frame
 * only costs decoding time; 960 px keeps the points' sub-pixel precision with room to spare.
 */
const val POSE_FRAME_LONG_SIDE = 960

/** An upright frame as ARGB pixels. */
class ArgbFrame(val pixels: IntArray, val width: Int, val height: Int)

/** Upright size of a stored frame: a 90° or 270° rotation swaps the sides. Then scaled so the longer side is at most `longSide`. */
fun uprightSize(width: Int, height: Int, rotation: Int, longSide: Int): Pair<Int, Int> {
    val (w, h) = if (rotation % 180 == 0) width to height else height to width
    val s = min(1.0, longSide.toDouble() / max(w, h))
    return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
}

/**
 * A YUV 4:2:0 frame (the decoder's planes, any row and pixel strides) to an upright ARGB frame, rotated by the video's
 * `rotation` (0, 90, 180 or 270, clockwise, as the file asks the player to show it) and scaled down by nearest pixel.
 * Colours by ITU-R BT.601 in video range, which is what phone cameras record. `left`/`top` are the decoder's crop offset
 * (the visible picture can start inside the decoded buffer).
 */
fun yuv420ToArgb(
    y: ByteArray, yRowStride: Int, yPixelStride: Int,
    u: ByteArray, v: ByteArray, uvRowStride: Int, uvPixelStride: Int,
    width: Int, height: Int, rotation: Int, longSide: Int = POSE_FRAME_LONG_SIDE,
    left: Int = 0, top: Int = 0,
    into: IntArray? = null,
): ArgbFrame {
    val (outW, outH) = uprightSize(width, height, rotation, longSide)
    val uprightW = if (rotation % 180 == 0) width else height
    val uprightH = if (rotation % 180 == 0) height else width
    val sx = uprightW.toDouble() / outW
    val sy = uprightH.toDouble() / outH
    val px = if (into != null && into.size == outW * outH) into else IntArray(outW * outH)
    for (oy in 0 until outH) {
        val uy = min(uprightH - 1, (oy * sy).toInt())
        for (ox in 0 until outW) {
            val ux = min(uprightW - 1, (ox * sx).toInt())
            // Upright pixel to stored pixel.
            val srcX: Int; val srcY: Int
            when (rotation) {
                90 -> { srcX = uy; srcY = height - 1 - ux }
                180 -> { srcX = width - 1 - ux; srcY = height - 1 - uy }
                270 -> { srcX = width - 1 - uy; srcY = ux }
                else -> { srcX = ux; srcY = uy }
            }
            val bx = srcX + left; val by = srcY + top
            val yy = (y[by * yRowStride + bx * yPixelStride].toInt() and 0xFF) - 16
            val uvIndex = (by / 2) * uvRowStride + (bx / 2) * uvPixelStride
            val uu = (u[uvIndex].toInt() and 0xFF) - 128
            val vv = (v[uvIndex].toInt() and 0xFF) - 128
            val c = 1192 * max(0, yy)
            val r = (c + 1634 * vv) shr 10
            val g = (c - 833 * vv - 400 * uu) shr 10
            val b = (c + 2066 * uu) shr 10
            px[oy * outW + ox] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }
    return ArgbFrame(px, outW, outH)
}

/**
 * Slow motion on its real clock. Some phones store a slow-motion clip re-timed to play at normal speed (120 frames shot
 * per second, stored 30 a second); its timestamps then run 4 times too slow. When the file's capture frame rate (Android's
 * METADATA_KEY_CAPTURE_FRAMERATE) is well above the rate its timestamps show, the times are scaled down to match it.
 */
fun onRealClock(clip: PoseClip, captureFps: Double?): PoseClip {
    val fileFps = clipFps(clip.frames)
    if (captureFps == null || fileFps <= 0 || captureFps < fileFps * 1.5) return clip
    val scale = fileFps / captureFps
    return clip.copy(frames = clip.frames.map { it.copy(timeMs = it.timeMs * scale) })
}

/** A frame's timestamp for MediaPipe's video mode: whole ms, and always after the previous one (slow motion is < 5 ms apart). */
fun nextPoseTimestampMs(timeUs: Long, previousMs: Long?): Long = if (previousMs == null) timeUs / 1000 else max(timeUs / 1000, previousMs + 1)
