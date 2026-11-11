package com.bioscan.fieldterminal.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// Score scenes (owner's request, Nov 2026): the top of each big-score page (CHARGE and the five domains) gets a light
// scene behind the ring, in the page's own hue, that follows the score.
//   - drawn, not pictures: shapes and gradients, sharp at any density, nothing added to the APK;
//   - light: about 60 % of a full scene, dark, and the busiest part kept away from the number (a soft dark disc sits
//     behind the ring); the scene fades out at its foot into the page's wash;
//   - very slow movement (one cycle in 16 to 24 s), still when the phone's animations are off;
//   - the score moves the scene smoothly (designed at 20, 50 and 85, blended between); no score yet is a neutral,
//     still mid-dusk. The number and its words carry the meaning; the scene only echoes it.
// SLEEP a night sky (stars, a moon, haze); RECOVERY still water at dawn (ripples, the sun's reflection); LOAD mountain
// ridges with a path and a marker (the marker's height is the score); FUEL a field of grain (how full, the day's final
// score); BALANCE mist and petals (how even); CHARGE a city at dusk (lit windows).

enum class ScoreScene { CHARGE, SLEEP, RECOVERY, LOAD, FUEL, BALANCE }

private const val STRENGTH = 0.6f

/** The scene behind a score's header. [score] 0-100, null when there is none yet. Size it to the header. */
@Composable
fun ScoreSceneBackdrop(scene: ScoreScene, score: Double?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember {
        score == null || runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f
    }
    val period = when (scene) {
        ScoreScene.SLEEP, ScoreScene.CHARGE -> 24_000
        else -> 16_000
    }
    val phase = if (still) 0f else {
        val tr = rememberInfiniteTransition(label = "scene")
        val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        p
    }
    val t = ((score ?: 50.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        when (scene) {
            ScoreScene.SLEEP -> sleepSky(t, phase)
            ScoreScene.RECOVERY -> stillWater(t, phase)
            ScoreScene.LOAD -> ridges(t, phase)
            ScoreScene.FUEL -> field(t, phase)
            ScoreScene.BALANCE -> mist(t, phase)
            ScoreScene.CHARGE -> city(t, phase)
        }
        // A soft dark disc behind the ring keeps the number clear of the scene.
        drawCircle(
            Brush.radialGradient(listOf(FT.Base.copy(alpha = 0.55f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.40f), radius = size.minDimension * 0.42f),
            radius = size.minDimension * 0.42f, center = Offset(size.width / 2, size.height * 0.40f),
        )
        // The foot fades into the page.
        drawRect(Brush.verticalGradient(0.7f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
}

private fun wave(x: Float) = sin(2 * PI * x).toFloat()
private fun ink(c: Color, a: Float) = c.copy(alpha = (a * STRENGTH).coerceIn(0f, 1f))

private fun DrawScope.sky(c: Color, top: Float = 0.30f) =
    drawRect(Brush.verticalGradient(0f to ink(c, top), 0.75f to ink(c, top * 0.25f), 1f to Color.Transparent))

private fun DrawScope.hills(c: Color, base: Float, amp: Float, seed: Int, alpha: Float) {
    val r = Random(seed)
    val a = r.nextFloat(); val b = r.nextFloat()
    val p = Path().apply {
        moveTo(0f, size.height)
        var x = 0f
        while (x <= size.width) {
            val f = x / size.width
            lineTo(x, size.height * base - size.height * amp * (0.6f * wave(f * 0.9f + a) + 0.4f * wave(f * 2.1f + b)))
            x += 6f
        }
        lineTo(size.width, size.height); close()
    }
    drawPath(p, ink(c, alpha))
}

// ---- SLEEP: a night sky -------------------------------------------------------------------------------------------------

private fun DrawScope.sleepSky(t: Float, phase: Float) {
    val c = FT.Domain.Sleep
    sky(c, 0.34f)
    val r = Random(7)
    val count = (20 + 80 * t).toInt()
    repeat(100) { i ->
        val x = r.nextFloat() * size.width
        val y = r.nextFloat() * size.height * 0.72f
        val rad = (0.8f + r.nextFloat() * 1.3f) * density
        val off = r.nextFloat()
        val twinkles = r.nextFloat() < 0.18f
        if (i >= count) return@repeat
        var a = 0.35f + 0.55f * t
        if (twinkles) a *= 0.55f + 0.45f * (0.5f + 0.5f * wave(phase * 2 + off))
        drawCircle(ink(lerp(c, Color.White, 0.75f), a), rad, Offset(x, y))
    }
    // the moon, a crescent; thin cloud across it on a poor night
    val mc = Offset(size.width * 0.84f, size.height * 0.17f)
    val mr = 13f * density
    val crescent = Path.combine(
        PathOperation.Difference,
        Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc, mr)) },
        Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc + Offset(mr * 0.55f, -mr * 0.25f), mr)) },
    )
    drawPath(crescent, ink(lerp(c, Color.White, 0.85f), 0.55f + 0.35f * t))
    val cloud = (1 - t).coerceAtLeast(0f)
    if (cloud > 0.6f) {
        val dx = wave(phase) * 6f * density
        drawOval(ink(lerp(c, FT.Base, 0.3f), cloud * 0.8f), Offset(mc.x - mr * 2.2f + dx, mc.y - mr * 0.2f), Size(mr * 4.2f, mr * 1.1f))
    }
    // haze over the hills on a poor night
    drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 0.85f to ink(c, 0.35f * (1 - t)), 1f to Color.Transparent))
    hills(lerp(c, FT.Base, 0.7f), 0.88f, 0.06f, 3, 0.9f)
}

// ---- RECOVERY: still water at dawn ---------------------------------------------------------------------------------------

private fun DrawScope.stillWater(t: Float, phase: Float) {
    val c = FT.Domain.Cardio
    sky(c, 0.28f)
    val horizon = size.height * 0.70f
    val sunX = size.width * 0.88f
    val sunR = 11f * density
    val sunY = horizon - (4f + 40f * t) * density
    val pale = lerp(c, Color.White, 0.7f)
    drawCircle(Brush.radialGradient(listOf(ink(pale, 0.30f), Color.Transparent), center = Offset(sunX, sunY), radius = sunR * 3.2f), sunR * 3.2f, Offset(sunX, sunY))
    drawCircle(ink(pale, 0.65f), sunR, Offset(sunX, sunY))
    drawRect(ink(lerp(c, FT.Base, 0.55f), 0.7f), Offset(0f, horizon), Size(size.width, size.height - horizon))
    drawLine(ink(pale, 0.45f), Offset(0f, horizon), Offset(size.width, horizon), 1.2f * density)
    // ripples: calmer as the score rises
    val amp = (0.4f + 3.6f * (1 - t)) * density
    val rows = 12
    for (k in 1..rows) {
        val y = horizon + (size.height - horizon) * k / (rows + 1)
        val p = Path()
        var x = 0f
        while (x <= size.width) {
            val yy = y + amp * wave(x / (90f * density) + phase * (1 + k * 0.07f) + k * 0.31f)
            if (x == 0f) p.moveTo(x, yy) else p.lineTo(x, yy)
            x += 5f
        }
        drawPath(p, ink(pale, 0.12f + 0.06f * (k % 3)), style = Stroke(0.8f * density))
        // the sun's reflection: one long piece when calm, short broken dashes when not
        val half = sunR * (1.2f - 0.6f * k / rows)
        val r = Random(k * 31)
        val pieces = if (t > 0.7f) 1 else 2 + ((1 - t) * 4).toInt()
        repeat(pieces) { i ->
            val gap = if (pieces == 1) 0f else (1 - t) * half * 0.9f
            val seg = (half * 2 - gap * (pieces - 1)) / pieces
            val sx = sunX - half + i * (seg + gap) + amp * wave(phase + r.nextFloat())
            drawLine(ink(pale, 0.5f - 0.025f * k), Offset(sx, y), Offset(sx + seg, y), 1.6f * density, cap = StrokeCap.Round)
        }
    }
}

// ---- LOAD: ridges, a path, a marker --------------------------------------------------------------------------------------

private fun DrawScope.ridges(t: Float, phase: Float) {
    val c = FT.Domain.Load
    sky(c, 0.30f)
    // drifting cloud
    val cx = ((phase + 0.2f) % 1f) * (size.width + 160f * density) - 80f * density
    val cc = Offset(cx + 45f * density, size.height * 0.18f)
    drawOval(Brush.radialGradient(listOf(ink(lerp(c, Color.White, 0.6f), 0.22f), Color.Transparent), center = cc, radius = 50f * density),
        Offset(cc.x - 50f * density, cc.y - 9f * density), Size(100f * density, 18f * density))
    hills(lerp(c, FT.Base, 0.35f), 0.62f, 0.14f, 11, 0.45f)
    hills(lerp(c, FT.Base, 0.55f), 0.74f, 0.12f, 12, 0.7f)
    // the front ridge: one peak right of centre, the path climbing it, the marker at the score's height
    // the peak at the right edge and the path low under the ring, so the climb stays clear of the number
    val foot = Offset(size.width * 0.04f, size.height * 0.99f)
    val peak = Offset(size.width * 0.90f, size.height * 0.40f)
    val front = Path().apply {
        moveTo(0f, size.height)
        lineTo(0f, size.height * 0.95f)
        quadraticBezierTo(size.width * 0.62f, size.height * 0.92f, peak.x, peak.y)
        quadraticBezierTo(size.width * 0.95f, size.height * 0.52f, size.width, size.height * 0.58f)
        lineTo(size.width, size.height); close()
    }
    drawPath(front, ink(lerp(c, FT.Base, 0.78f), 1f))
    val zig = listOf(foot, Offset(size.width * 0.34f, size.height * 0.96f), Offset(size.width * 0.62f, size.height * 0.90f),
        Offset(size.width * 0.74f, size.height * 0.80f), Offset(size.width * 0.78f, size.height * 0.66f),
        Offset(size.width * 0.86f, size.height * 0.56f), Offset(size.width * 0.83f, size.height * 0.48f), peak)
    val lengths = zig.zipWithNext { a, b -> (b - a).getDistance() }
    val total = lengths.sum()
    var acc = 0f
    var marker = zig.first()
    zig.zipWithNext().forEachIndexed { i, (a, b) ->
        val dashes = (lengths[i] / (7f * density)).toInt().coerceAtLeast(1)
        for (d in 0 until dashes step 2) {
            val s = a + (b - a) * (d / dashes.toFloat())
            val e = a + (b - a) * ((d + 1) / dashes.toFloat())
            drawLine(ink(lerp(c, Color.White, 0.5f), 0.55f), s, e, 1.3f * density, cap = StrokeCap.Round)
        }
        val target = total * t
        if (target >= acc && target <= acc + lengths[i]) marker = a + (b - a) * ((target - acc) / lengths[i])
        acc += lengths[i]
    }
    drawCircle(ink(c, 0.35f), 9f * density, marker)
    drawCircle(ink(lerp(c, Color.White, 0.4f), 1f), 3.5f * density, marker)
}

// ---- FUEL: a field of grain ---------------------------------------------------------------------------------------------

private fun DrawScope.field(t: Float, phase: Float) {
    val c = FT.Domain.Fuel
    drawRect(Brush.radialGradient(listOf(ink(c, 0.32f), Color.Transparent), center = Offset(0f, size.height * 0.2f), radius = size.width * 0.9f))
    hills(lerp(c, FT.Base, 0.6f), 0.72f, 0.05f, 21, 0.55f)
    hills(lerp(c, FT.Base, 0.7f), 0.82f, 0.04f, 22, 0.8f)
    val r = Random(5)
    val maxH = size.height * 0.20f
    val stalk = ink(lerp(c, Color.White, 0.15f), 0.45f)
    val ear = ink(lerp(c, Color.White, 0.45f), 0.6f)
    repeat(220) {
        val x = r.nextFloat() * size.width
        val baseY = size.height * (0.84f + r.nextFloat() * 0.16f)
        val keep = r.nextFloat()
        val hVar = 0.6f + r.nextFloat() * 0.4f
        val lean = (r.nextFloat() - 0.5f) * 0.25f
        if (keep > 0.2f + 0.8f * t) return@repeat // a sparse field when the score is low
        val h = maxH * (0.35f + 0.65f * t) * hVar * (0.6f + 0.4f * (baseY / size.height - 0.84f) / 0.16f)
        val sway = (wave(phase + x / size.width * 0.8f) * 2.2f * density) + lean * h
        val tip = Offset(x + sway, baseY - h)
        val stem = Path().apply { moveTo(x, baseY); quadraticBezierTo(x + sway * 0.2f, baseY - h * 0.6f, tip.x, tip.y) }
        drawPath(stem, stalk, style = Stroke(0.9f * density, cap = StrokeCap.Round))
        // the ear: three small grains up the tip, leaning with the stalk, grown in with the score
        val ears = (t - 0.35f).coerceAtLeast(0f) / 0.65f
        if (ears > 0f) {
            val dir = Offset(sway * 0.12f, -1f).let { it / it.getDistance() }
            for (g in 0 until 3) {
                val p = tip + dir * (g * 3.2f * density)
                drawCircle(ear.copy(alpha = ear.alpha * ears), (1.5f - g * 0.25f) * density, p)
            }
        }
    }
}

// ---- BALANCE: mist and petals -------------------------------------------------------------------------------------------

private fun DrawScope.mist(t: Float, phase: Float) {
    val c = FT.Domain.Balance
    sky(c, 0.22f)
    val uneven = 1 - t
    val r = Random(9)
    for (k in 0 until 6) {
        val y0 = size.height * (0.14f + 0.14f * k) + uneven * (r.nextFloat() - 0.5f) * size.height * 0.10f
        val thick = size.height * 0.05f * (1 + uneven * (r.nextFloat() - 0.3f) * 1.6f)
        val speed = 1f + uneven * (r.nextFloat() - 0.5f) * 1.6f
        val off = phase * speed + k * 0.17f
        val p = Path()
        var x = 0f
        p.moveTo(0f, y0)
        while (x <= size.width) { p.lineTo(x, y0 + thick * 0.35f * wave(x / size.width + off)); x += 6f }
        x = size.width
        while (x >= 0f) { p.lineTo(x, y0 + thick + thick * 0.35f * wave(x / size.width * 1.3f + off + 0.3f)); x -= 6f }
        p.close()
        drawPath(p, ink(lerp(c, Color.White, 0.3f), 0.06f + 0.04f * (k % 2)))
    }
    // petals: many, every way, when unsettled; a few drifting one way when even
    val n = (4 + 16 * uneven).toInt()
    val pr = Random(13)
    repeat(20) { i ->
        val x0 = pr.nextFloat(); val y0 = pr.nextFloat(); val dir = pr.nextFloat() * 2 - 1
        if (i >= n) return@repeat
        val drift = if (t > 0.6f) 1f else dir
        val x = ((x0 + phase * 0.25f * drift) % 1f + 1f) % 1f * size.width
        val y = (y0 * 0.8f + 0.04f * wave(phase + i * 0.13f)) * size.height
        drawOval(ink(lerp(c, Color.White, 0.4f), 0.55f), Offset(x, y), Size(4.5f * density, 2.6f * density))
    }
}

// ---- CHARGE: a city at dusk ---------------------------------------------------------------------------------------------

private fun DrawScope.city(t: Float, phase: Float) {
    val skyC = FT.Metric.SceneCitySky
    val win = FT.Metric.SceneCityWindow
    drawRect(Brush.verticalGradient(0f to ink(skyC, 0.9f), 0.8f to ink(skyC, 0.35f), 1f to Color.Transparent))
    val r = Random(17)
    var x = -4f * density
    val ground = size.height * 0.98f
    while (x < size.width) {
        val w = (22f + r.nextFloat() * 30f) * density
        val h = size.height * (0.18f + r.nextFloat() * 0.34f)
        val top = ground - h
        drawRect(ink(lerp(skyC, FT.Base, 0.65f), 1f), Offset(x, top), Size(w - 2f * density, h))
        val cols = (w / (7f * density)).toInt().coerceIn(2, 5)
        val rows = (h / (9f * density)).toInt()
        val cw = (w - 2f * density) / (cols + 1)
        for (row in 0 until rows) for (col in 0 until cols) {
            val roll = r.nextFloat()
            val slow = r.nextFloat()
            // a few windows switch slowly on and off
            val flicker = slow < 0.06f && wave(phase + slow * 10) > 0.6f
            val lit = (roll < 0.08f + 0.80f * t) != flicker
            val wx = x + cw * (col + 0.6f)
            val wy = top + 6f * density + row * 9f * density
            if (wy > ground - 4f * density) continue
            drawRect(if (lit) ink(win, 0.75f) else ink(win, 0.06f), Offset(wx, wy), Size(3f * density, 4f * density))
        }
        x += w
    }
    drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to ink(win, 0.10f * t)))
}
