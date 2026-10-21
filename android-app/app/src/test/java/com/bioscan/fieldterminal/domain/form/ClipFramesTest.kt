package com.bioscan.fieldterminal.domain.form

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipFramesTest {

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    /** A width×height frame, black (Y 16) with one white pixel at (wx, wy); chroma neutral, interleaved like NV12. */
    private fun frame(width: Int, height: Int, wx: Int, wy: Int, rotation: Int, longSide: Int = 4096): ArgbFrame {
        val y = ByteArray(width * height) { 16 }
        y[wy * width + wx] = 235.toByte()
        val uv = ByteArray(width * height / 2) { 128.toByte() }
        return yuv420ToArgb(y, width, 1, uv, uv.copyOfRange(1, uv.size) + byteArrayOf(128.toByte()), width, 2, width, height, rotation, longSide)
    }

    private fun whiteAt(f: ArgbFrame): Pair<Int, Int> {
        val i = f.pixels.indexOfFirst { red(it) > 200 }
        return (i % f.width) to (i / f.width)
    }

    @Test
    fun framesAreTurnedUprightAsTheFileAsks() {
        // A white pixel at the stored frame's top-left corner, 8×4.
        assertEquals(0 to 0, whiteAt(frame(8, 4, 0, 0, 0)))
        val r90 = frame(8, 4, 0, 0, 90)
        assertEquals(4, r90.width); assertEquals(8, r90.height)
        assertEquals(3 to 0, whiteAt(r90))
        assertEquals(7 to 3, whiteAt(frame(8, 4, 0, 0, 180)))
        assertEquals(0 to 7, whiteAt(frame(8, 4, 0, 0, 270)))
    }

    @Test
    fun framesAreScaledToTheLongSideAndColoursConverted() {
        assertEquals(960 to 540, uprightSize(1920, 1080, 0, 960))
        assertEquals(540 to 960, uprightSize(1920, 1080, 90, 960))
        assertEquals(640 to 480, uprightSize(640, 480, 0, 960))
        val f = frame(16, 8, 0, 0, 0, longSide = 8)
        assertEquals(8, f.width); assertEquals(4, f.height)
        // Video-range black and white become 0 and 255; neutral chroma stays grey.
        val black = f.pixels.last()
        assertEquals(0, red(black)); assertEquals(0, green(black)); assertEquals(0, blue(black))
        val white = f.pixels.first()
        assertEquals(255.0, red(white).toDouble(), 1.0); assertEquals(255.0, green(white).toDouble(), 1.0); assertEquals(255.0, blue(white).toDouble(), 1.0)
    }

    @Test
    fun slowMotionStoredAtNormalSpeedIsPutBackOnItsRealClock() {
        val clip = PoseClip((0 until 30).map { PoseFrame(it * 1000.0 / 30, null) }, 960, 540)
        val real = onRealClock(clip, captureFps = 120.0)
        assertEquals(120.0, clipFps(real.frames), 0.01)
        assertEquals(clip, onRealClock(clip, captureFps = null))
        assertEquals(clip, onRealClock(clip, captureFps = 30.0))
        // A file already stored at 120 fps keeps its times.
        val fast = PoseClip((0 until 30).map { PoseFrame(it * 1000.0 / 120, null) }, 960, 540)
        assertEquals(fast, onRealClock(fast, captureFps = 120.0))
    }

    @Test
    fun poseTimestampsAreWholeMillisecondsAndAlwaysIncrease() {
        var last: Long? = null
        val stamps = listOf(0L, 4166L, 8333L, 8999L, 12_500L).map { us -> nextPoseTimestampMs(us, last).also { last = it } }
        assertEquals(listOf(0L, 4L, 8L, 9L, 12L), stamps)
    }
}
