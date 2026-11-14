package com.bioscan.fieldterminal.ui.components

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Score scenes (owner's request, Nov 2026): the top of each big-score page (CHARGE and the five domains) gets a light
// scene behind the ring, in the page's own hue, that follows the score.
//   - drawn in code, illustrated: layered noise for ridges and hills, depth by atmospheric fade and mist between
//     layers, multi-stop skies, soft glows (sun, moon, windows), a fine film grain; nothing added to the APK;
//   - light: about 50 % of a full scene, dark, a soft dark disc behind the ring, the foot fading into the page;
//   - very slow movement (one cycle in 16 to 24 s), still when the phone's animations are off or there is no score;
//   - the still layers are drawn once into an image (for the size and the score); only what moves is drawn per frame;
//   - the score moves the scene smoothly (designed at 20, 50 and 85). The number and its words carry the meaning.
// SLEEP a night sky (stars, the Milky Way, the moon, haze); RECOVERY a lake at dawn (ripples, the sun's reflection);
// LOAD ridges with a trail and a marker (its height is the score); FUEL a wheat field (how full, the day's final
// score); BALANCE a misty garden under a blossom branch (how even the mist, how settled the petals); CHARGE a city at
// dusk (lit windows).

enum class ScoreScene { CHARGE, SLEEP, RECOVERY, LOAD, FUEL, BALANCE }

private const val STRENGTH = 0.50f

/** The scene behind a score's header. [score] 0-100, null when there is none yet. Size it to the header. */
@Composable
fun ScoreSceneBackdrop(scene: ScoreScene, score: Double?, modifier: Modifier = Modifier, ringCenterY: Dp? = null) {
    val context = LocalContext.current
    val still = remember(score) {
        score == null || FTMotion.reduced || runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f
    }
    val period = if (scene == ScoreScene.SLEEP || scene == ScoreScene.CHARGE) 24_000 else 16_000
    val phase: State<Float> = if (still) remember { mutableFloatStateOf(0.15f) } else {
        rememberInfiniteTransition(label = "scene").animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    }
    val t = ((score ?: 50.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                // The still layers, once per size and score.
                val w = size.width.toInt().coerceAtLeast(1)
                val h = size.height.toInt().coerceAtLeast(1)
                val stillLayer = ImageBitmap(w, h)
                CanvasDrawScope().draw(this, layoutDirection, Canvas(stillLayer), size) {
                    when (scene) {
                        ScoreScene.SLEEP -> sleepStill(t)
                        ScoreScene.RECOVERY -> lakeStill(t)
                        ScoreScene.LOAD -> ridgesStill(t)
                        ScoreScene.FUEL -> fieldStill(t)
                        ScoreScene.BALANCE -> gardenStill(t)
                        ScoreScene.CHARGE -> cityStill(t)
                    }
                    grain()
                }
                onDrawBehind {
                    drawImage(stillLayer)
                    val p = phase.value
                    when (scene) {
                        ScoreScene.SLEEP -> sleepMoving(t, p)
                        ScoreScene.RECOVERY -> lakeMoving(t, p)
                        ScoreScene.LOAD -> ridgesMoving(t, p)
                        ScoreScene.FUEL -> fieldMoving(t, p)
                        ScoreScene.BALANCE -> gardenMoving(t, p)
                        ScoreScene.CHARGE -> cityMoving(t, p)
                    }
                    // A soft dark disc behind the ring keeps the number clear of the scene.
                    val c = Offset(size.width / 2, ringCenterY?.toPx() ?: (size.height * 0.40f))
                    val r = if (ringCenterY != null) 135.dp.toPx() else size.minDimension * 0.44f
                    drawCircle(Brush.radialGradient(0f to FT.Base.copy(alpha = 0.62f), 0.6f to FT.Base.copy(alpha = 0.35f), 1f to Color.Transparent, center = c, radius = r), r, c)
                    // The foot fades into the page.
                    drawRect(Brush.verticalGradient(0.68f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }
            },
    )
}

// ---- Shared ---------------------------------------------------------------------------------------------------------

private fun ink(c: Color, a: Float) = c.copy(alpha = (a * STRENGTH).coerceIn(0f, 1f))
private fun mix(a: Color, b: Color, f: Float) = lerp(a, b, f.coerceIn(0f, 1f))
private fun wave(x: Float) = sin(2 * PI * x).toFloat()
private fun smooth(f: Float) = f * f * (3 - 2 * f)

/** Smooth 1-D value noise, 0..1. */
private class Noise(seed: Int) {
    private val v = Random(seed).let { r -> FloatArray(512) { r.nextFloat() } }
    fun at(x: Float): Float {
        val i = floor(x).toInt()
        val f = smooth(x - i)
        return v[i and 511] * (1 - f) + v[(i + 1) and 511] * f
    }
    /** Fractal noise: [octaves] layers, each twice as fine and [rough] times as strong. 0..1. */
    fun fbm(x: Float, octaves: Int = 5, rough: Float = 0.5f): Float {
        var amp = 1f; var freq = 1f; var sum = 0f; var norm = 0f
        repeat(octaves) { o -> sum += amp * at(x * freq + o * 17.3f); norm += amp; amp *= rough; freq *= 2.03f }
        return sum / norm
    }
}

/** A silhouette across the width: y(x) for each x, closed to the bottom. */
private fun DrawScope.silhouette(y: (Float) -> Float): Path = Path().apply {
    moveTo(0f, size.height)
    var x = 0f
    while (x <= size.width + 3f) { lineTo(x, y(x)); x += 3f }
    lineTo(size.width, size.height); close()
}

/** A layer filled with a vertical gradient from [top] (at [yTop]) to [bottom]. */
private fun DrawScope.fillLayer(p: Path, top: Color, bottom: Color, yTop: Float) =
    drawPath(p, Brush.verticalGradient(listOf(top, bottom), startY = yTop, endY = size.height))

private fun DrawScope.glow(c: Offset, r: Float, color: Color, a: Float) =
    drawCircle(Brush.radialGradient(0f to ink(color, a), 0.35f to ink(color, a * 0.45f), 1f to Color.Transparent, center = c, radius = r), r, c)

/** A soft horizontal band of mist around [y], [h] tall. */
private fun DrawScope.mistBand(y: Float, h: Float, color: Color, a: Float) =
    drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.5f to ink(color, a), 1f to Color.Transparent, startY = y - h / 2, endY = y + h / 2), Offset(0f, y - h / 2), Size(size.width, h))

private fun DrawScope.skyGradient(vararg stops: Pair<Float, Color>) = drawRect(Brush.verticalGradient(*stops))

private val grainTexture: ImageBitmap by lazy {
    val n = 128
    val r = Random(99)
    val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    for (y in 0 until n) for (x in 0 until n) {
        val a = (r.nextFloat() * r.nextFloat() * 40).toInt()
        bmp.setPixel(x, y, android.graphics.Color.argb(a, 255, 255, 255))
    }
    bmp.asImageBitmap()
}

/** A fine film grain over the scene, so the gradients do not band. */
private fun DrawScope.grain() = drawRect(ShaderBrush(ImageShader(grainTexture, TileMode.Repeated, TileMode.Repeated)), alpha = 0.35f)

/** A star: a dot, a soft halo, and for the brightest a four-point glint. */
private fun DrawScope.star(c: Offset, r: Float, color: Color, a: Float, glint: Boolean) {
    if (r > 1.2f * density) drawCircle(Brush.radialGradient(listOf(ink(color, a * 0.5f), Color.Transparent), center = c, radius = r * 4), r * 4, c)
    drawCircle(ink(color, a), r, c)
    if (glint) {
        val l = r * 6
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, ink(color, a * 0.7f), Color.Transparent), c.x - l, c.x + l), Offset(c.x - l, c.y), Offset(c.x + l, c.y), 0.7f * density)
        drawLine(Brush.verticalGradient(listOf(Color.Transparent, ink(color, a * 0.7f), Color.Transparent), c.y - l, c.y + l), Offset(c.x, c.y - l), Offset(c.x, c.y + l), 0.7f * density)
    }
}

// ---- SLEEP: a night sky ---------------------------------------------------------------------------------------------------

private fun DrawScope.sleepStill(t: Float) {
    val c = FT.Domain.Sleep
    val night = mix(c, FT.Base, 0.82f)
    skyGradient(0f to ink(night, 1f), 0.55f to ink(mix(c, FT.Base, 0.65f), 1f), 0.9f to ink(mix(c, FT.Base, 0.45f), 1f))
    glow(Offset(size.width * 0.5f, size.height * 0.95f), size.width * 0.7f, mix(c, Color.White, 0.2f), 0.30f)
    // the Milky Way: a soft diagonal band, clearer on a good night
    val n = Noise(3)
    repeat(26) { i ->
        val f = i / 25f
        val p = Offset(size.width * (-0.05f + 1.1f * f), size.height * (0.70f - 0.62f * f) + (n.at(f * 6) - 0.5f) * 30f * density)
        glow(p, (34f + 28f * n.at(f * 9 + 4)) * density, mix(c, Color.White, 0.5f), 0.10f * (0.3f + 0.7f * t))
    }
    // dense faint stars in the band, scattered ones elsewhere
    val r = Random(7)
    val count = (60 + 260 * t).toInt()
    repeat(320) { i ->
        val inBand = i % 3 == 0
        val f = r.nextFloat()
        val x: Float; val y: Float
        if (inBand) {
            x = size.width * (-0.05f + 1.1f * f)
            y = size.height * (0.70f - 0.62f * f) + (r.nextFloat() - 0.5f) * 70f * density
        } else { x = f * size.width; y = r.nextFloat() * size.height * 0.80f }
        val big = r.nextFloat()
        if (i >= count) return@repeat
        val rad = (if (big > 0.97f) 1.5f else if (big > 0.85f) 1.0f else 0.6f) * density
        star(Offset(x, y), rad, mix(c, Color.White, 0.8f), (0.35f + 0.6f * t) * (0.5f + big * 0.5f), glint = big > 0.985f && t > 0.4f)
    }
    // the moon, with its halo
    val mc = Offset(size.width * 0.85f, size.height * 0.16f)
    val mr = 11f * density
    glow(mc, mr * 6f, mix(c, Color.White, 0.6f), 0.35f + 0.25f * t)
    val crescent = Path.combine(PathOperation.Difference,
        Path().apply { addOval(Rect(mc, mr)) },
        Path().apply { addOval(Rect(mc + Offset(mr * 0.5f, -mr * 0.3f), mr * 0.95f)) })
    drawPath(crescent, Brush.linearGradient(listOf(ink(Color.White, 0.95f), ink(mix(c, Color.White, 0.6f), 0.8f)), mc - Offset(mr, mr), mc + Offset(mr, mr)))
    // two mountain layers with mist between, the far one hazier
    val far = Noise(31); val near = Noise(32)
    val farP = silhouette { x -> size.height * 0.80f - size.height * 0.16f * far.fbm(x / size.width * 3f + 2f) }
    fillLayer(farP, ink(mix(c, FT.Base, 0.55f), 0.9f), ink(mix(c, FT.Base, 0.75f), 1f), size.height * 0.6f)
    mistBand(size.height * 0.84f, size.height * 0.14f, mix(c, Color.White, 0.35f), 0.30f + 0.25f * (1 - t))
    val nearP = silhouette { x -> size.height * 0.90f - size.height * 0.10f * near.fbm(x / size.width * 4f + 7f) }
    fillLayer(nearP, ink(mix(c, FT.Base, 0.80f), 1f), ink(FT.Base, 1f), size.height * 0.78f)
    // haze over the sky on a poor night
    drawRect(ink(mix(c, FT.Base, 0.5f), 0.45f * (1 - t) * (1 - t)))
}

private fun DrawScope.sleepMoving(t: Float, phase: Float) {
    val c = FT.Domain.Sleep
    // a few stars breathe
    val r = Random(77)
    repeat(14) {
        val x = r.nextFloat() * size.width; val y = r.nextFloat() * size.height * 0.7f; val off = r.nextFloat()
        val a = (0.3f + 0.6f * t) * (0.5f + 0.5f * wave(phase * 3 + off))
        star(Offset(x, y), 1.1f * density, mix(c, Color.White, 0.85f), a, glint = false)
    }
    // thin cloud drifting across the moon on a poor night
    val cloud = ((0.55f - t) / 0.55f).coerceIn(0f, 1f)
    if (cloud > 0f) {
        val cx = size.width * (0.62f + 0.18f * wave(phase))
        for (k in 0 until 5) glow(Offset(cx + k * 22f * density, size.height * (0.17f + 0.015f * (k % 2))), 30f * density, mix(c, Color.White, 0.25f), 0.35f * cloud)
    }
}

// ---- RECOVERY: a lake at dawn ---------------------------------------------------------------------------------------------

private fun DrawScope.lakeStill(t: Float) {
    val c = FT.Domain.Cardio
    val horizon = size.height * 0.68f
    val sun = Offset(size.width * 0.86f, horizon - (6f + 40f * t) * density)
    val warm = mix(c, Color.White, 0.55f)
    skyGradient(0f to ink(mix(c, FT.Base, 0.80f), 1f), 0.45f to ink(mix(c, FT.Base, 0.6f), 1f), 0.68f to ink(mix(c, Color.White, 0.15f), 0.75f), 0.69f to Color.Transparent)
    glow(sun, size.width * 0.55f, warm, 0.35f + 0.2f * t)
    glow(sun, 34f * density, Color.White, 0.55f)
    drawCircle(ink(Color.White, 0.9f), 10f * density, sun)
    // the far shore and its reflection
    val n = Noise(41)
    val shore = { x: Float -> horizon - size.height * 0.07f * n.fbm(x / size.width * 3.5f + 1f) }
    fillLayer(silhouette(shore), ink(mix(c, FT.Base, 0.6f), 0.9f), ink(mix(c, FT.Base, 0.7f), 1f), horizon - size.height * 0.1f)
    // water: darker toward us
    drawRect(Brush.verticalGradient(0f to ink(mix(c, FT.Base, 0.55f), 1f), 1f to ink(mix(c, FT.Base, 0.85f), 1f), startY = horizon, endY = size.height), Offset(0f, horizon), Size(size.width, size.height - horizon))
    val reflect = Path().apply {
        moveTo(0f, horizon)
        var x = 0f
        while (x <= size.width + 3f) { lineTo(x, horizon + (horizon - shore(x)) * 0.6f); x += 3f }
        lineTo(size.width, horizon); close()
    }
    drawPath(reflect, ink(mix(c, FT.Base, 0.7f), 0.55f))
    drawLine(Brush.horizontalGradient(listOf(Color.Transparent, ink(warm, 0.6f), Color.Transparent)), Offset(0f, horizon), Offset(size.width, horizon), 0.8f * density)
    mistBand(horizon - 4f * density, 26f * density, warm, 0.25f)
}

private fun DrawScope.lakeMoving(t: Float, phase: Float) {
    val c = FT.Domain.Cardio
    val horizon = size.height * 0.68f
    val sunX = size.width * 0.86f
    val warm = mix(c, Color.White, 0.6f)
    val rough = 1 - t
    val rows = 26
    val r = Random(5)
    for (k in 1..rows) {
        val f = k / rows.toFloat()
        val y = horizon + (size.height - horizon) * f * f
        val amp = (0.3f + 3.2f * rough) * density * (0.4f + f)
        // the ripple highlight across the lake
        val p = Path()
        var x = 0f
        while (x <= size.width) {
            val yy = y + amp * wave(x / ((70f + 60f * f) * density) + phase * (1 + f) + k * 0.37f)
            if (x == 0f) p.moveTo(x, yy) else p.lineTo(x, yy)
            x += 6f
        }
        drawPath(p, ink(warm, 0.06f + 0.10f * (1 - f)), style = Stroke(0.7f * density))
        // the sun's reflection: one long streak when calm, broken glints when not
        val width = (10f + 40f * f) * density
        val pieces = if (t > 0.72f) 1 else 2 + (rough * 5).toInt()
        repeat(pieces) { i ->
            val jitter = (r.nextFloat() - 0.5f) * rough * width * 1.2f + amp * wave(phase * 2 + r.nextFloat())
            val len = width * 2 / pieces * (if (pieces == 1) 1f else 0.55f + 0.4f * r.nextFloat())
            val sx = sunX - width + i * (width * 2 / pieces) + jitter
            drawLine(Brush.horizontalGradient(listOf(Color.Transparent, ink(Color.White, 0.75f - 0.5f * f), Color.Transparent), sx, sx + len),
                Offset(sx, y), Offset(sx + len, y), (0.9f + 1.4f * f) * density, cap = StrokeCap.Round)
        }
    }
}

// ---- LOAD: ridges, a trail, a marker --------------------------------------------------------------------------------------

/** Ridged noise, 0..1: sharp crests and soft valleys, like a mountain line. */
private fun Noise.ridged(x: Float, octaves: Int = 6): Float {
    var amp = 1f; var freq = 1f; var sum = 0f; var norm = 0f
    repeat(octaves) { o -> sum += amp * (1f - abs(at(x * freq + o * 9.1f) * 2f - 1f)); norm += amp; amp *= 0.5f; freq *= 2.1f }
    return ((sum / norm - 0.35f) / 0.5f).coerceIn(0f, 1f)
}

private fun frontRidge(n: Noise, w: Float, h: Float): (Float) -> Float = { x ->
    val f = x / w
    val peak = exp(-((f - 0.88f) / 0.20f).pow(2))
    h * 0.97f - h * (0.06f + 0.46f * peak) - h * 0.12f * n.ridged(f * 4f + 3f) * (0.4f + 0.6f * peak)
}

private fun DrawScope.ridgesStill(t: Float) {
    val c = FT.Domain.Load
    val dusk = mix(c, Color.White, 0.3f)
    skyGradient(0f to ink(mix(c, FT.Base, 0.85f), 1f), 0.5f to ink(mix(c, FT.Base, 0.6f), 1f), 0.75f to ink(mix(c, FT.Base, 0.35f), 0.9f))
    glow(Offset(size.width * 0.25f, size.height * 0.62f), size.width * 0.6f, dusk, 0.35f)
    // three far ridges, hazier the further they are, mist between them
    val layers = listOf(0.50f to 0.45f, 0.60f to 0.60f, 0.72f to 0.72f)
    layers.forEachIndexed { i, (base, dark) ->
        val n = Noise(50 + i)
        val p = silhouette { x -> size.height * base - size.height * (0.24f - i * 0.04f) * n.ridged(x / size.width * (2.2f + i) + i * 3f) }
        fillLayer(p, ink(mix(c, FT.Base, dark), 1f), ink(mix(c, FT.Base, dark + 0.15f), 1f), size.height * (base - 0.2f))
        mistBand(size.height * (base + 0.03f), size.height * 0.10f, dusk, 0.22f)
    }
    val front = frontRidge(Noise(60), size.width, size.height)
    fillLayer(silhouette(front), ink(mix(c, FT.Base, 0.84f), 1f), ink(FT.Base, 1f), size.height * 0.45f)
    // a rim of last light along the front ridge
    val rim = Path().apply { var x = 0f; moveTo(0f, front(0f)); while (x <= size.width) { lineTo(x, front(x)); x += 3f } }
    drawPath(rim, Brush.horizontalGradient(listOf(ink(dusk, 0.15f), ink(dusk, 0.5f)), 0f, size.width), style = Stroke(1f * density))
    // the trail: a dotted line just under the ridge line, only on the climb right of the ring, up to the summit
    val trail = trailPoints(front)
    for (i in trail.indices step 3) if (i + 1 < trail.size) drawLine(ink(mix(c, Color.White, 0.6f), 0.55f), trail[i], trail[i + 1], 1.2f * density, cap = StrokeCap.Round)
    // a small flag on the summit
    val top = trail.last()
    drawLine(ink(Color.White, 0.6f), top, top - Offset(0f, 14f * density), 1f * density)
    drawPath(Path().apply { moveTo(top.x, top.y - 14f * density); lineTo(top.x + 8f * density, top.y - 11f * density); lineTo(top.x, top.y - 8f * density); close() }, ink(c, 0.9f))
}

private fun DrawScope.trailPoints(front: (Float) -> Float): List<Offset> {
    val pts = mutableListOf<Offset>()
    var x = size.width * 0.74f
    val end = size.width * 0.88f
    while (x <= end) { pts += Offset(x, front(x) + 7f * density); x += 4f * density }
    pts += Offset(end, front(end) + 2f * density)
    return pts
}

private fun DrawScope.ridgesMoving(t: Float, phase: Float) {
    val c = FT.Domain.Load
    // a long soft cloud drifting
    val cx = ((phase + 0.3f) % 1f) * (size.width * 1.4f) - size.width * 0.2f
    for (k in 0 until 4) glow(Offset(cx + k * 26f * density, size.height * (0.14f + 0.01f * k)), 34f * density, mix(c, Color.White, 0.55f), 0.18f)
    // a hiker on the trail, as high up the climb as the score, walking slowly
    val trail = trailPoints(frontRidge(Noise(60), size.width, size.height))
    val i = ((trail.size - 1) * t).toInt().coerceIn(0, trail.size - 1)
    val at = trail[i]
    val ahead = trail[(i + 2).coerceAtMost(trail.size - 1)]
    glow(at - Offset(0f, 8f * density), 18f * density, mix(c, Color.White, 0.4f), 0.45f)
    hiker(at, ahead, phase, ink(mix(c, Color.White, 0.75f), 1.6f))
}

/**
 * A hiker standing on [foot], facing up the trail toward [ahead]: head, a forward-leaning body, a pack, legs in stride
 * and a pole planted ahead. [phase] swings the stride slowly.
 */
private fun DrawScope.hiker(foot: Offset, ahead: Offset, phase: Float, color: Color) {
    val u = density
    val slope = ((ahead.y - foot.y) / (ahead.x - foot.x).coerceAtLeast(1f)).coerceIn(-1.5f, 0f) // up the hill is negative
    val stride = wave(phase * 6) // one stride every few seconds
    val hip = foot + Offset(0f, -7f * u)
    val shoulder = hip + Offset(1.6f * u, -6f * u)
    val head = shoulder + Offset(1.2f * u, -3f * u)
    fun ground(dx: Float) = foot + Offset(dx, dx * slope)
    val legA = ground((2.6f + 1.4f * stride) * u)
    val legB = ground((-2.2f + 1.4f * -stride) * u)
    val w = 1.7f * u
    // legs, the far one dimmer
    drawLine(color.copy(alpha = color.alpha * 0.7f), hip, legB, w, cap = StrokeCap.Round)
    drawLine(color, hip, legA, w, cap = StrokeCap.Round)
    // body, leaning into the climb
    drawLine(color, hip, shoulder, 2.4f * u, cap = StrokeCap.Round)
    // the pack on the back
    drawRoundRect(color, Offset(hip.x - 3.4f * u, shoulder.y + 0.2f * u), Size(3.0f * u, 5.2f * u), CornerRadius(1.2f * u))
    // the head
    drawCircle(color, 1.9f * u, head)
    // the arm and pole, planted ahead
    val hand = shoulder + Offset(3.0f * u, 3.2f * u + stride * 0.6f * u)
    drawLine(color, shoulder, hand, 1.3f * u, cap = StrokeCap.Round)
    drawLine(color.copy(alpha = color.alpha * 0.8f), hand + Offset(0f, -1.5f * u), ground((6.2f + 1.2f * stride) * u), 0.8f * u, cap = StrokeCap.Round)
}

// ---- FUEL: a wheat field --------------------------------------------------------------------------------------------------

private fun DrawScope.fieldStill(t: Float) {
    val c = FT.Domain.Fuel
    val gold = mix(c, Color.White, 0.55f)
    skyGradient(0f to ink(mix(c, FT.Base, 0.85f), 1f), 0.6f to ink(mix(c, FT.Base, 0.6f), 1f))
    glow(Offset(size.width * 0.08f, size.height * 0.55f), size.width * 0.75f, gold, 0.38f)
    // a tree line and rolling field, hazier far away
    val n1 = Noise(71); val n2 = Noise(72)
    val trees = silhouette { x -> size.height * 0.64f - size.height * 0.07f * n1.fbm(x / size.width * 14f, 4, 0.6f) }
    fillLayer(trees, ink(mix(c, FT.Base, 0.6f), 0.9f), ink(mix(c, FT.Base, 0.7f), 1f), size.height * 0.55f)
    mistBand(size.height * 0.66f, size.height * 0.10f, gold, 0.28f)
    val field = silhouette { x -> size.height * 0.72f - size.height * 0.04f * n2.fbm(x / size.width * 2f + 5f) }
    fillLayer(field, ink(mix(c, FT.Base, 0.55f), 1f), ink(mix(c, FT.Base, 0.85f), 1f), size.height * 0.68f)
    // far rows: fine vertical texture, denser with the score
    val r = Random(73)
    repeat((120 + 260 * t).toInt()) {
        val x = r.nextFloat() * size.width
        val y = size.height * (0.72f + r.nextFloat() * 0.08f)
        val h = (2f + 5f * t) * density * (y / size.height - 0.66f) * 6f
        drawLine(ink(gold, 0.18f + 0.15f * t), Offset(x, y), Offset(x, y - h), 0.6f * density)
    }
}

/** One wheat stem with its ear: grains alternating either side up the head, fine awns above, leaning with [lean]. */
private fun DrawScope.wheat(base: Offset, h: Float, lean: Float, ear: Float, stem: Color, grain: Color, scale: Float) {
    val tip = base + Offset(lean * h, -h)
    val ctrl = base + Offset(lean * h * 0.2f, -h * 0.6f)
    drawPath(Path().apply { moveTo(base.x, base.y); quadraticBezierTo(ctrl.x, ctrl.y, tip.x, tip.y) }, stem, style = Stroke(0.9f * scale * density, cap = StrokeCap.Round))
    if (ear <= 0f) return
    val dir = (tip - ctrl).let { it / it.getDistance() }
    val side = Offset(-dir.y, dir.x)
    val grains = 7
    val angle = Math.toDegrees(atan2(dir.y.toDouble(), dir.x.toDouble())).toFloat() + 90f
    for (g in 0 until grains) {
        val along = tip + dir * (g * 2.6f * scale * density)
        val s = if (g % 2 == 0) 1f else -1f
        val p = along + side * (s * 1.4f * scale * density)
        rotate(angle + s * 25f, p) {
            drawOval(grain.copy(alpha = grain.alpha * ear), Offset(p.x - 1.3f * scale * density, p.y - 2.4f * scale * density), Size(2.6f * scale * density, 4.8f * scale * density))
        }
    }
    val top = tip + dir * (grains * 2.6f * scale * density)
    for (a in listOf(-0.25f, 0f, 0.25f)) {
        val d = Offset(dir.x * cos(a) - dir.y * sin(a), dir.x * sin(a) + dir.y * cos(a))
        drawLine(grain.copy(alpha = grain.alpha * ear * 0.6f), top, top + d * (7f * scale * density), 0.5f * density)
    }
}

private fun DrawScope.fieldMoving(t: Float, phase: Float) {
    val c = FT.Domain.Fuel
    val stem = ink(mix(c, FT.Base, 0.15f), 0.75f)
    val grain = ink(mix(c, Color.White, 0.6f), 0.9f)
    val ear = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
    // two depths of wheat in front, swaying in a slow wave of wind
    for ((depth, rows) in listOf(0 to 70, 1 to 46)) {
        val r = Random(80 + depth)
        val scale = if (depth == 0) 0.75f else 1.15f
        repeat(rows) {
            val x = r.nextFloat() * size.width
            val keep = r.nextFloat()
            val hv = 0.7f + r.nextFloat() * 0.3f
            val baseY = size.height * (if (depth == 0) 0.84f else 0.97f) + r.nextFloat() * 6f * density
            val jitter = r.nextFloat() - 0.5f
            if (keep > 0.2f + 0.8f * t) return@repeat // a sparse field when the score is low
            val h = size.height * (if (depth == 0) 0.12f else 0.19f) * (0.45f + 0.55f * t) * hv
            val lean = 0.10f * wave(phase + x / size.width * 0.9f) + 0.06f * jitter
            wheat(Offset(x, baseY), h, lean, ear, stem, grain, scale)
        }
    }
}

// ---- BALANCE: a misty garden under a blossom branch -----------------------------------------------------------------------

private fun DrawScope.gardenStill(t: Float) {
    val c = FT.Domain.Balance
    val soft = mix(c, Color.White, 0.5f)
    skyGradient(0f to ink(mix(c, FT.Base, 0.85f), 1f), 0.6f to ink(mix(c, FT.Base, 0.62f), 1f), 1f to ink(mix(c, FT.Base, 0.5f), 1f))
    glow(Offset(size.width * 0.3f, size.height * 0.3f), size.width * 0.5f, soft, 0.25f)
    // distant hills, layered in the mist
    for (i in 0 until 3) {
        val n = Noise(90 + i)
        val base = 0.62f + 0.11f * i
        val p = silhouette { x -> size.height * base - size.height * (0.20f - 0.04f * i) * ((n.fbm(x / size.width * (1.6f + i) + i * 4f, 4, 0.45f) - 0.25f) / 0.5f).coerceIn(0f, 1f) }
        fillLayer(p, ink(mix(c, FT.Base, 0.45f + 0.15f * i), 0.95f), ink(mix(c, FT.Base, 0.7f + 0.1f * i), 1f), size.height * (base - 0.1f))
        mistBand(size.height * (base + 0.02f), size.height * 0.08f, soft, 0.22f)
    }
    // the branch from the top-right corner, with blossom clusters
    val bark = ink(mix(c, FT.Base, 0.55f), 1f)
    drawPath(Path().apply {
        moveTo(size.width * 1.02f, size.height * 0.02f)
        cubicTo(size.width * 0.92f, size.height * 0.10f, size.width * 0.86f, size.height * 0.06f, size.width * 0.74f, size.height * 0.16f)
    }, bark, style = Stroke(5f * density, cap = StrokeCap.Round))
    drawPath(Path().apply { moveTo(size.width * 0.88f, size.height * 0.07f); quadraticBezierTo(size.width * 0.86f, size.height * 0.16f, size.width * 0.80f, size.height * 0.22f) },
        bark, style = Stroke(2.5f * density, cap = StrokeCap.Round))
    val r = Random(95)
    for (cl in listOf(Offset(0.75f, 0.15f), Offset(0.81f, 0.21f), Offset(0.90f, 0.07f), Offset(0.84f, 0.11f))) {
        repeat(5) {
            val p = Offset(size.width * cl.x + (r.nextFloat() - 0.5f) * 16f * density, size.height * cl.y + (r.nextFloat() - 0.5f) * 12f * density)
            glow(p, 7f * density, soft, 0.6f)
            drawCircle(ink(mix(c, Color.White, 0.7f), 0.8f), 2.4f * density, p)
        }
    }
}

/** A petal: a teardrop, turned by [angle]. */
private fun DrawScope.petal(p: Offset, angle: Float, s: Float, color: Color) = rotate(angle, p) {
    drawPath(Path().apply {
        moveTo(p.x, p.y - s)
        cubicTo(p.x + s * 0.8f, p.y - s * 0.4f, p.x + s * 0.5f, p.y + s * 0.8f, p.x, p.y + s)
        cubicTo(p.x - s * 0.5f, p.y + s * 0.8f, p.x - s * 0.8f, p.y - s * 0.4f, p.x, p.y - s)
    }, color)
}

private fun DrawScope.gardenMoving(t: Float, phase: Float) {
    val c = FT.Domain.Balance
    val soft = mix(c, Color.White, 0.45f)
    val uneven = 1 - t
    // mist bands: even and moving together when settled, uneven and at odds when not
    val r = Random(9)
    for (k in 0 until 5) {
        val y = size.height * (0.40f + 0.11f * k) + uneven * (r.nextFloat() - 0.5f) * size.height * 0.12f
        val thick = size.height * (0.07f + uneven * r.nextFloat() * 0.08f)
        val speed = 1f + uneven * (r.nextFloat() - 0.5f) * 1.8f
        val off = phase * speed + k * 0.21f
        val p = Path()
        var x = 0f
        p.moveTo(0f, y)
        while (x <= size.width + 6f) { p.lineTo(x, y + thick * 0.3f * wave(x / size.width * (0.8f + 0.2f * k) + off)); x += 6f }
        x = size.width
        while (x >= -6f) { p.lineTo(x, y + thick + thick * 0.3f * wave(x / size.width * 1.1f + off + 0.4f)); x -= 6f }
        p.close()
        drawPath(p, Brush.verticalGradient(listOf(Color.Transparent, ink(soft, 0.10f + 0.06f * uneven), Color.Transparent), startY = y - thick * 0.3f, endY = y + thick * 1.3f))
    }
    // petals: a few drifting down one way when settled, many tumbling every way when not
    val n = (5 + 15 * uneven).toInt()
    val pr = Random(13)
    repeat(20) { i ->
        val x0 = pr.nextFloat(); val y0 = pr.nextFloat(); val dir = pr.nextFloat() * 2 - 1; val spin = pr.nextFloat()
        if (i >= n) return@repeat
        val drift = if (t > 0.6f) 0.6f else dir
        val f = (phase + y0) % 1f
        val x = (((x0 + f * 0.35f * drift) % 1f) + 1f) % 1f * size.width
        val y = (0.05f + f * 0.85f) * size.height
        val turn = spin * 360 + phase * 360 * (if (t > 0.6f) 0.3f else 1.2f) * (if (spin > 0.5f) 1 else -1)
        petal(Offset(x, y), turn, 3.6f * density, ink(mix(c, Color.White, 0.55f), 0.75f))
    }
}

// ---- CHARGE: a city at dusk -----------------------------------------------------------------------------------------------

private class Building(val x: Float, val w: Float, val h: Float, val crown: Int, val seed: Int)

private fun skyline(seed: Int, width: Float, maxH: Float, minW: Float, maxW: Float, density: Float): List<Building> {
    val r = Random(seed)
    val out = mutableListOf<Building>()
    var x = -4f * density
    while (x < width) {
        val w = (minW + r.nextFloat() * (maxW - minW)) * density
        out += Building(x, w, maxH * (0.35f + r.nextFloat() * 0.65f), r.nextInt(4), r.nextInt())
        x += w + r.nextFloat() * 2f * density
    }
    return out
}

private fun DrawScope.buildingShape(b: Building, ground: Float): Path = Path().apply {
    val top = ground - b.h
    moveTo(b.x, ground)
    when (b.crown) {
        1 -> { // a setback
            lineTo(b.x, top + 10f * density); lineTo(b.x + b.w * 0.2f, top + 10f * density); lineTo(b.x + b.w * 0.2f, top)
            lineTo(b.x + b.w * 0.8f, top); lineTo(b.x + b.w * 0.8f, top + 10f * density); lineTo(b.x + b.w, top + 10f * density)
        }
        2 -> { // a spire
            lineTo(b.x, top); lineTo(b.x + b.w * 0.5f - 1f * density, top); lineTo(b.x + b.w * 0.5f, top - 14f * density)
            lineTo(b.x + b.w * 0.5f + 1f * density, top); lineTo(b.x + b.w, top)
        }
        3 -> { lineTo(b.x, top + 6f * density); lineTo(b.x + b.w, top) } // a slanted roof
        else -> { lineTo(b.x, top); lineTo(b.x + b.w, top) }
    }
    lineTo(b.x + b.w, ground); close()
}

/**
 * A building's windows, lit with the score. The few that switch slowly are drawn only by the moving layer
 * ([flicker] set), the rest only by the still one ([flicker] null).
 */
private fun DrawScope.windows(b: Building, ground: Float, t: Float, light: Color, cool: Color, a: Float, size1: Float, flicker: Float?) {
    val r = Random(b.seed)
    val cols = (b.w / (size1 * 2.2f * density)).toInt().coerceIn(1, 6)
    val rows = ((b.h - 14f * density) / (size1 * 2.6f * density)).toInt()
    val gap = (b.w - cols * size1 * density) / (cols + 1)
    for (row in 0 until rows) for (col in 0 until cols) {
        val roll = r.nextFloat(); val tone = r.nextFloat(); val slow = r.nextFloat()
        val switching = slow < 0.05f
        if ((flicker == null) == switching) continue
        var lit = roll < 0.06f + 0.84f * t
        if (flicker != null && wave(flicker + slow * 10) > 0.55f) lit = !lit
        val wx = b.x + gap + col * (size1 * density + gap)
        val wy = ground - b.h + 12f * density + row * size1 * 2.6f * density
        if (wy > ground - 3f * density) continue
        val color = if (tone < 0.25f) cool else light
        if (lit) {
            if (size1 > 2.5f) glow(Offset(wx + size1 * density / 2, wy + size1 * density), 7f * density, color, a * 0.35f)
            drawRect(ink(color, a), Offset(wx, wy), Size(size1 * density, size1 * 1.4f * density))
        } else {
            drawRect(ink(color, 0.04f), Offset(wx, wy), Size(size1 * density, size1 * 1.4f * density))
        }
    }
}

private fun DrawScope.cityStill(t: Float) {
    val skyC = FT.Metric.SceneCitySky
    val win = FT.Metric.SceneCityWindow
    val cool = mix(win, Color.White, 0.7f)
    skyGradient(0f to ink(mix(skyC, FT.Base, 0.55f), 1f), 0.6f to ink(skyC, 1f), 0.9f to ink(mix(skyC, win, 0.22f), 1f))
    glow(Offset(size.width * 0.5f, size.height * 1.0f), size.width * 0.8f, win, 0.20f + 0.15f * t)
    // a few dim stars above the city
    val sr = Random(19)
    repeat(40) { star(Offset(sr.nextFloat() * size.width, sr.nextFloat() * size.height * 0.45f), 0.6f * density, Color.White, 0.25f, false) }
    val ground = size.height * 0.99f
    // the far skyline, hazy, few lights
    skyline(21, size.width, size.height * 0.42f, 14f, 30f, density).forEach { b ->
        drawPath(buildingShape(b, ground - size.height * 0.04f), ink(mix(skyC, FT.Base, 0.45f), 0.9f))
        windows(b, ground - size.height * 0.04f, t * 0.6f, win, cool, 0.35f, 1.6f, null)
    }
    mistBand(size.height * 0.80f, size.height * 0.16f, mix(skyC, win, 0.3f), 0.35f)
    // the near skyline, darker, its windows the score
    skyline(17, size.width, size.height * 0.52f, 22f, 44f, density).forEach { b ->
        drawPath(buildingShape(b, ground), Brush.verticalGradient(listOf(ink(mix(skyC, FT.Base, 0.65f), 1f), ink(FT.Base, 1f)), startY = ground - b.h, endY = ground))
        windows(b, ground, t, win, cool, 0.85f, 2.8f, null)
    }
    // street glow
    drawRect(Brush.verticalGradient(0.82f to Color.Transparent, 1f to ink(win, 0.18f + 0.15f * t)))
}

private fun DrawScope.cityMoving(t: Float, phase: Float) {
    // the few windows that switch slowly, drawn over the still city
    val win = FT.Metric.SceneCityWindow
    val cool = mix(win, Color.White, 0.7f)
    val ground = size.height * 0.99f
    skyline(17, size.width, size.height * 0.52f, 22f, 44f, density).forEach { b -> windows(b, ground, t, win, cool, 0.85f, 2.8f, phase) }
}
