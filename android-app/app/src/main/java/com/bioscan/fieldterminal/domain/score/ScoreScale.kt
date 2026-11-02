package com.bioscan.fieldterminal.domain.score

import com.bioscan.fieldterminal.domain.comparison.inverseNormalCdf
import com.bioscan.fieldterminal.domain.comparison.normalCdf

// Scoring system (docs/scoring-system/README.md): the one 0-150 scale every score in the app is shown on.
//
// A score says where a day sits in the person's own history. The anchors below are fixed points on that history (its
// percentiles); between them the score is a straight line in z (bell-curve) space, so it moves evenly for a value that
// moves evenly in standard deviations. 100 is the top of normal (the 90th percentile of the person's own days), and the
// second lap of the ring runs to 150 (about the best 0.3 %).

enum class ScoreBand(val label: String) {
    LOW("LOW"),
    BELOW_NORMAL("BELOW NORMAL"),
    NORMAL("NORMAL"),
    PRIMED("PRIMED"),
    SUPERCHARGED("SUPERCHARGED"),
}

object ScoreScale {
    const val MAX = 150.0
    const val TOP_OF_NORMAL = 100.0
    const val TYPICAL = 80.0
    const val LOW_BELOW = 40.0
    const val NORMAL_FROM = 60.0
    const val SUPERCHARGED_FROM = 125.0

    /** z of the top of normal (the 90th percentile): a value exactly on target maps here. */
    val Z_TOP_OF_NORMAL: Double = inverseNormalCdf(0.90)

    // (percentile, score) anchors. The lowest anchor is where the scale reaches 0.
    private val ANCHORS: List<Pair<Double, Double>> = listOf(
        0.001 to 0.0,
        0.10 to LOW_BELOW,
        0.25 to NORMAL_FROM,
        0.50 to TYPICAL,
        0.90 to TOP_OF_NORMAL,
        0.97 to SUPERCHARGED_FROM,
        0.997 to MAX,
    )
    private val Z_ANCHORS: List<Pair<Double, Double>> = ANCHORS.map { (p, s) -> inverseNormalCdf(p) to s }

    /** The score of a value z standard deviations from the person's typical day. */
    fun fromZ(z: Double, upside: Boolean = true): Double {
        val score = when {
            z <= Z_ANCHORS.first().first -> 0.0
            z >= Z_ANCHORS.last().first -> MAX
            else -> {
                val i = Z_ANCHORS.indexOfLast { it.first <= z }
                val (z0, s0) = Z_ANCHORS[i]
                val (z1, s1) = Z_ANCHORS[i + 1]
                s0 + (s1 - s0) * (z - z0) / (z1 - z0)
            }
        }
        return if (upside) score else minOf(score, TOP_OF_NORMAL)
    }

    /** The score of a day at percentile [p] (0-1) of the person's own history. */
    fun fromPercentile(p: Double, upside: Boolean = true): Double =
        fromZ(inverseNormalCdf(p.coerceIn(0.0005, 0.9995)), upside)

    /** The z a score corresponds to: the inverse of [fromZ] on the upside scale. */
    fun toZ(score: Double): Double {
        val s = score.coerceIn(0.0, MAX)
        val i = Z_ANCHORS.indexOfLast { it.second <= s }.coerceAtMost(Z_ANCHORS.size - 2)
        val (z0, s0) = Z_ANCHORS[i]
        val (z1, s1) = Z_ANCHORS[i + 1]
        return z0 + (z1 - z0) * (s - s0) / (s1 - s0)
    }

    fun band(score: Double): ScoreBand = when {
        score < LOW_BELOW -> ScoreBand.LOW
        score < NORMAL_FROM -> ScoreBand.BELOW_NORMAL
        score < TOP_OF_NORMAL -> ScoreBand.NORMAL
        score < SUPERCHARGED_FROM -> ScoreBand.PRIMED
        else -> ScoreBand.SUPERCHARGED
    }

    /**
     * Where [value] sits among [history] as a percentile (0-1), with ties counted half and a half-observation of
     * smoothing at each end so neither 0 nor 1 is ever returned.
     */
    fun rankPercentile(value: Double, history: Collection<Double>): Double {
        val below = history.count { it < value }
        val equal = history.count { it == value }
        return (below + 0.5 * equal + 0.5) / (history.size + 1.0)
    }

    /** Percentile of a z value, for "better than N of your last 100 days". */
    fun percentileOfZ(z: Double): Double = normalCdf(z)
}
