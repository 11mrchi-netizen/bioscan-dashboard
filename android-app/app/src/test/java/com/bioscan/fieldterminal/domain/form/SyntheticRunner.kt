package com.bioscan.fieldterminal.domain.form

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// A stick-figure runner on a treadmill, seen from the side, drawn as MediaPipe would report it: 33 points per frame in
// pixels, with known landings, toe-offs and angles. The tests read it back through the analyser. Geometry in a world
// frame (forward, up, pixels), then mapped to the picture.

data class RunnerSpec(
    val fps: Double = 120.0,
    val seconds: Double = 12.0,
    val cadenceSpm: Double = 172.0,
    /** Share of a stride each foot is on the ground. */
    val duty: Double = 0.34,
    /** Foot angle at landing against flat, toe up positive. */
    val strikeDeg: Double = 15.0,
    /** Ankle ahead of the hips at landing and behind them at toe-off, shares of leg length. */
    val landAhead: Double = 0.30,
    val toeOffBehind: Double = 0.42,
    val trunkLeanDeg: Double = 7.0,
    /** Hips' total rise and fall per step, share of leg length. */
    val oscillation: Double = 0.08,
    val direction: Int = 1,
    val nearSide: BodySide = BodySide.LEFT,
    val noisePx: Double = 1.0,
    val seed: Int = 7,
    val legPx: Double = 400.0,
    val floorY: Double = 1000.0,
    val width: Int = 1920,
    val height: Int = 1080,
    /** Frames with legs and arms reported left for right. */
    val swappedFrames: Set<Int> = emptySet(),
    /** Frames with no runner found. */
    val droppedFrames: Set<Int> = emptySet(),
    /** Shoulders apart across the picture, share of leg length (near 0 side-on). */
    val shoulderSpread: Double = 0.03,
)

data class RunnerTruth(
    val nearLandingsMs: List<Double>,
    val nearToeOffsMs: List<Double>,
    val farLandingsMs: List<Double>,
    val contactMs: Double,
    val kneeFlexionAtLandingDeg: Double,
    val shinAtLandingDeg: Double,
    val hipExtensionAtToeOffDeg: Double,
)

private const val THIGH = 0.53
private const val SHANK = 0.47
private const val SWING_CLEARANCE = 0.25
private const val TOE_OFF_FOOT_DEG = -35.0

private data class V(val f: Double, val u: Double) {
    operator fun plus(o: V) = V(f + o.f, u + o.u)
    operator fun minus(o: V) = V(f - o.f, u - o.u)
    operator fun times(k: Double) = V(f * k, u * k)
}

private fun rot(v: V, deg: Double): V {
    val a = deg * PI / 180
    return V(v.f * cos(a) - v.u * sin(a), v.f * sin(a) + v.u * cos(a))
}

class SyntheticRunner(val spec: RunnerSpec) {
    private val L = spec.legPx
    val strideMs = 120_000.0 / spec.cadenceSpm
    private val contactMs = spec.duty * strideMs
    private val firstNearLandingMs = 130.0
    private val heel = V(-0.07, -0.09)
    private val toe = V(0.22, -0.09)

    private fun landingOffset(near: Boolean) = if (near) firstNearLandingMs else firstNearLandingMs + strideMs / 2

    /** Foot state at time t: ankle position relative to the hips (forward), the sole's height, the foot angle. */
    private fun foot(tMs: Double, near: Boolean): Triple<Double, Double, Double> {
        val s = (((tMs - landingOffset(near)) % strideMs) + strideMs) % strideMs / strideMs
        val a = spec.landAhead * L
        val b = spec.toeOffBehind * L
        return if (s < spec.duty) {
            val p = s / spec.duty
            val angle = when {
                p < 0.2 -> spec.strikeDeg * (1 - p / 0.2)
                p < 0.65 -> 0.0
                else -> TOE_OFF_FOOT_DEG * (p - 0.65) / 0.35
            }
            Triple(a - (a + b) * p, 0.0, angle)
        } else {
            val q = (s - spec.duty) / (1 - spec.duty)
            val smooth = q * q * (3 - 2 * q)
            // Cubic Hermite from toe-off to landing whose speed at both ends is the belt's: the foot leaves moving back and
            // lands already moving back (swing-leg retraction), with no corner at landing.
            val swingMs = (1 - spec.duty) * strideMs
            val beltPerSwing = -(a + b) / contactMs * swingMs
            val h00 = 2 * q * q * q - 3 * q * q + 1; val h10 = q * q * q - 2 * q * q + q
            val h01 = -2 * q * q * q + 3 * q * q; val h11 = q * q * q - q * q
            val fwd = h00 * -b + h10 * beltPerSwing + h01 * a + h11 * beltPerSwing
            Triple(fwd, SWING_CLEARANCE * L * sin(PI * q), TOE_OFF_FOOT_DEG + (spec.strikeDeg - TOE_OFF_FOOT_DEG) * smooth)
        }
    }

    private fun hipHeight(tMs: Double): Double {
        val midStance = landingOffset(true) + contactMs / 2
        return 1.0 * L - spec.oscillation * L / 2 * cos(2 * PI * (tMs - midStance) / (strideMs / 2))
    }

    /** Hip, knee, ankle, heel and toe of one leg, world frame. */
    private fun leg(tMs: Double, near: Boolean): List<V> {
        val hip = V(0.0, hipHeight(tMs))
        val (fwd, sole, angle) = foot(tMs, near)
        val h = rot(heel * L, angle); val tt = rot(toe * L, angle)
        val ankle = V(fwd, sole - min(h.u, tt.u))
        val d = hypot(ankle.f - hip.f, ankle.u - hip.u).coerceAtMost((THIGH + SHANK) * L * 0.9999)
        val phi = atan2(ankle.u - hip.u, ankle.f - hip.f)
        val alpha = acos(((THIGH * L).let { it * it } + d * d - (SHANK * L).let { it * it }) / (2 * THIGH * L * d))
        val knee = hip + V(cos(phi + alpha), sin(phi + alpha)) * (THIGH * L)
        return listOf(hip, knee, ankle, ankle + h, ankle + tt)
    }

    private fun kneeFlexion(l: List<V>): Double {
        val u = l[0] - l[1]; val v = l[2] - l[1]
        return 180 - acos((u.f * v.f + u.u * v.u) / (hypot(u.f, u.u) * hypot(v.f, v.u))) * 180 / PI
    }

    fun truth(): RunnerTruth {
        val end = spec.seconds * 1000
        fun times(start: Double) = generateSequence(start) { it + strideMs }.takeWhile { it < end }.toList()
        val landings = times(landingOffset(true))
        val atLanding = leg(landings.first(), true)
        val toeOff = landings.first() + contactMs
        val atToeOff = leg(toeOff - 1e-6, true)
        return RunnerTruth(
            nearLandingsMs = landings,
            nearToeOffsMs = landings.map { it + contactMs },
            farLandingsMs = times(landingOffset(false)),
            contactMs = contactMs,
            kneeFlexionAtLandingDeg = kneeFlexion(atLanding),
            shinAtLandingDeg = atan2(atLanding[2].f - atLanding[1].f, atLanding[1].u - atLanding[2].u) * 180 / PI,
            hipExtensionAtToeOffDeg = -atan2(atToeOff[1].f - atToeOff[0].f, atToeOff[0].u - atToeOff[1].u) * 180 / PI,
        )
    }

    fun clip(): PoseClip {
        val rnd = Random(spec.seed)
        fun noise() = spec.noisePx * rnd.nextGaussian()
        val n = (spec.seconds * spec.fps).toInt()
        val cx = spec.width / 2.0
        val frames = (0 until n).map { i ->
            val tMs = i * 1000.0 / spec.fps
            if (i in spec.droppedFrames) return@map PoseFrame(tMs, null)
            val nearLeg = leg(tMs, true)
            val farLeg = leg(tMs, false)
            val hip = nearLeg[0]
            val lean = spec.trunkLeanDeg * PI / 180
            val shoulder = hip + V(sin(lean), cos(lean)) * (0.6 * L)
            val nose = shoulder + V(0.12, 0.28) * L
            val ear = shoulder + V(-0.02, 0.30) * L
            fun arm(near: Boolean): List<V> {
                val phase = 2 * PI * (tMs - landingOffset(!near)) / strideMs
                val beta = 0.6 * sin(phase)
                val elbow = shoulder + V(sin(beta), -cos(beta)) * (0.35 * L)
                val wrist = elbow + V(sin(beta + 1.4), -cos(beta + 1.4)) * (0.3 * L)
                return listOf(elbow, wrist)
            }
            val nearArm = arm(true); val farArm = arm(false)
            val spread = spec.shoulderSpread * L / 2
            // World to picture: forward is `direction`, up is up the picture; the near side drawn a touch in front.
            fun px(v: V, vis: Double, dx: Double = 0.0) = PosePoint(cx + (v.f + dx) * spec.direction + noise(), spec.floorY - v.u + noise(), vis)
            val pts = MutableList(Landmark.COUNT) { PosePoint(cx, spec.floorY - L, 0.9) }
            val nearIsLeft = spec.nearSide == BodySide.LEFT
            fun put(left: Int, right: Int, near: PosePoint, far: PosePoint) {
                pts[if (nearIsLeft) left else right] = near
                pts[if (nearIsLeft) right else left] = far
            }
            pts[Landmark.NOSE] = px(nose, 0.95)
            put(Landmark.LEFT_EAR, Landmark.RIGHT_EAR, px(ear, 0.9), px(ear, 0.4))
            put(Landmark.LEFT_SHOULDER, Landmark.RIGHT_SHOULDER, px(shoulder, 0.95, spread), px(shoulder, 0.6, -spread))
            put(Landmark.LEFT_ELBOW, Landmark.RIGHT_ELBOW, px(nearArm[0], 0.9), px(farArm[0], 0.5))
            put(Landmark.LEFT_WRIST, Landmark.RIGHT_WRIST, px(nearArm[1], 0.9), px(farArm[1], 0.5))
            val legIdx = listOf(
                Landmark.LEFT_HIP to Landmark.RIGHT_HIP, Landmark.LEFT_KNEE to Landmark.RIGHT_KNEE,
                Landmark.LEFT_ANKLE to Landmark.RIGHT_ANKLE, Landmark.LEFT_HEEL to Landmark.RIGHT_HEEL,
                Landmark.LEFT_FOOT_INDEX to Landmark.RIGHT_FOOT_INDEX,
            )
            legIdx.forEachIndexed { k, (l, r) -> put(l, r, px(nearLeg[k], 0.95), px(farLeg[k], 0.7)) }
            if (i in spec.swappedFrames) for ((l, r) in Landmark.PAIRS) { val tmp = pts[l]; pts[l] = pts[r]; pts[r] = tmp }
            PoseFrame(tMs, pts)
        }
        return PoseClip(frames, spec.width, spec.height)
    }
}

private fun Random.nextGaussian(): Double {
    // Box-Muller.
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
}
