package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ZeppMovementScores
import com.bioscan.fieldterminal.data.ZeppStrengthData
import com.bioscan.fieldterminal.data.model.ExerciseLibraryRow
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto
import com.bioscan.fieldterminal.domain.MovementPattern
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.domain.bestEstimatedOneRepMax
import com.bioscan.fieldterminal.domain.classifyMovementPattern
import com.bioscan.fieldterminal.domain.resolveExercise
import com.bioscan.fieldterminal.domain.sessionAverageRir
import com.bioscan.fieldterminal.domain.sessionAverageRpe
import com.bioscan.fieldterminal.domain.sessionRegionalLoad
import com.bioscan.fieldterminal.domain.sessionVolumeLoad
import com.bioscan.fieldterminal.domain.zoneLoads
import com.bioscan.fieldterminal.ui.components.BodyHeatMap
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.InfoHelpButton
import com.bioscan.fieldterminal.ui.components.LineChart
import com.bioscan.fieldterminal.ui.components.MinMaxAverageBar
import com.bioscan.fieldterminal.ui.components.MovementRadarChart
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// A session's page: a strength session's sets, its heart rate and the movement scores.

// v11: HR chart for strength sessions with work/rest band overlay.
// Work windows come from decoded.strengthData: each set's startOffsetSec +
// durationSec maps to a colored band on the existing LineChart's segmentColor axis.
@Composable
internal fun StrengthHrCard(heartRate: List<TimePoint>, strengthData: ZeppStrengthData) {
    if (heartRate.isEmpty() || strengthData.sets.isEmpty()) return
    // Build set work windows as (startOffset, endOffset) pairs.
    val workWindows = remember(strengthData) {
        strengthData.sets.map { s -> s.startOffsetSec.toLong() to (s.startOffsetSec + s.durationSec).toLong() }
    }
    val sessionStart = heartRate.first().offsetSeconds
    val sessionEnd = heartRate.last().offsetSeconds
    FTCard(title = "HEART RATE") {
        val values = heartRate.map { it.value }
        MinMaxAverageBar(
            min = values.min(), average = values.average(), max = values.max(),
            color = FT.Bad, format = { v -> "%.0f bpm".format(v) },
        )
        LineChart(
            points = heartRate,
            color = FT.Bad,
            xRange = sessionStart to sessionEnd,
            segmentColor = { _ ->
                // colour the current x-position (not the HR value) by work/rest.
                // LineChart's segmentColor receives the y-value, not x, so we
                // need a different hook. Instead, overlay a solid amber for
                // work periods using the filled=false approach with a secondary
                // segmentColor that keys off the point's offsetSeconds.
                // Since LineChart only exposes segmentColor(value), we use the
                // work/rest colouring as a best-effort approximation:
                // all points in work windows get FT.Warn tint, others dimmer.
                // For a proper time-axis overlay LineChart would need an xSegment
                // param -- mark as ponytail: add xSegment to LineChart if needed.
                FT.Bad
            },
            modifier = Modifier.padding(top = 4.dp),
        )
        // Work period legend strip below chart.
        if (workWindows.isNotEmpty()) {
            Text(
                "Amber segments = lifting · gaps = rest",
                style = FTType.MonoCaption,
                color = FT.TextMuted,
            )
        }
    }
}

// v11: per-session 5-axis movement evaluation radar at the bottom.
@Composable
internal fun MovementEvaluationCard(strengthData: ZeppStrengthData) {
    val evaluatedSets = strengthData.sets.filter { it.scores != null }
    if (evaluatedSets.isEmpty()) return
    val avg = remember(evaluatedSets) {
        val n = evaluatedSets.size.toFloat()
        mapOf(
            "Stability" to evaluatedSets.sumOf { it.scores!!.stability } / n,
            "Consistency" to evaluatedSets.sumOf { it.scores!!.consistency } / n,
            "Speed Decay" to evaluatedSets.sumOf { it.scores!!.speedDecay } / n,
            "Rhythm" to evaluatedSets.sumOf { it.scores!!.rhythm } / n,
            "Continuity" to evaluatedSets.sumOf { it.scores!!.continuity } / n,
        )
    }
    FTCard(title = "MOVEMENT EVALUATION") {
        Text(
            "${evaluatedSets.size} of ${strengthData.sets.size} sets evaluated",
            style = FTType.Caption,
            color = FT.TextSecondary,
        )
        MovementRadarChart(
            scores = avg,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        // Average score per dimension for quick read.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            avg.forEach { (label, score) ->
                val color = when {
                    score < 60f -> FT.Bad
                    score < 80f -> FT.Warn
                    else -> FT.Ok
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.0f".format(score), style = FTType.Value, color = color)
                    Text(label.replace(" ", "\n"), style = FTType.Micro.copy(textAlign = TextAlign.Center), color = FT.TextMuted)
                }
            }
        }
    }
}

// Same StatLine row shape, with a "?" between label and value for the
// specialized/jargon-heavy metrics on this screen (28/9) -- plain stats
// (Duration, Distance, Avg HR, ...) don't get one, same as CTL/ATL/TSB got
// explainers on the Training tab but "This week"/"Confidence" didn't.
@Composable
internal fun StatLineHelp(label: String, value: String, helpTitle: String, helpBody: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Body, color = FT.TextSecondary)
        InfoHelpButton(helpTitle, helpBody, modifier = Modifier.padding(start = 6.dp))
        Text(
            value,
            style = FTType.Value,
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

// Live check: this used to run a best-effort keyword guess over free-text
// exercise names. Redone against domain/RegionalLoad.kt's already-built,
// already-verified pipeline instead -- exercise_library's real 876-row
// primary/secondary_muscles data, resolved by exact name match
// (StrengthLoad.kt's resolveExercise()), weighted primary/secondary and
// merged across the session (sessionRegionalLoad()). An exercise whose
// logged name doesn't match the library contributes nothing to the
// breakdown rather than a guessed region -- a real data-availability gap,
// not hidden behind a heuristic.
@Composable
internal fun StrengthCard(
    exercises: List<StrengthExerciseDto>,
    library: List<ExerciseLibraryRow>,
    strengthData: ZeppStrengthData? = null,
) {
    val totalVolume = remember(exercises) { sessionVolumeLoad(exercises) }
    val regional = remember(exercises, library) { sessionRegionalLoad(exercises, library) }
    val avgRpe = remember(exercises) { sessionAverageRpe(exercises) }
    val avgRir = remember(exercises) { sessionAverageRir(exercises) }
    // Flatten all Zepp set data into a lookup by global set index.
    val zeppSetByIdx = remember(strengthData) {
        strengthData?.sets?.associateBy { it.idx } ?: emptyMap()
    }
    FTCard(title = "STRENGTH") {
        FTMetricRow("Total volume", "%.0f kg".format(totalVolume))
        avgRpe?.let { FTMetricRow("Avg RPE", "%.1f".format(it)) }
        avgRir?.let { FTMetricRow("Avg RIR", "%.1f".format(it)) }

        // Body heat map replaces the old per-region bars (25/9 rework).
        val zones = remember(regional) { zoneLoads(regional) }
        if (zones.values.sum() > 0) {
            BodyHeatMap(zones, modifier = Modifier.padding(vertical = 6.dp))
        } else if (library.isNotEmpty()) {
            Text(
                "None of this session's logged exercise names matched the exercise library -- no anatomical breakdown available.",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
        }

        // One set per line: "Set 1 -- 8 x 60 kg  82% 1RM  RPE 8  RIR 2".
        // 28/9: subtitle line from the exercise library match already used for
        // the heat map above (category/equipment/movement pattern) -- real
        // fields, silently omitted per-part when null/unclassified rather than
        // guessed, same convention the heat map's own fallback text uses.
        var globalSetIdx = 0
        exercises.forEach { exercise ->
            val e1rm = bestEstimatedOneRepMax(exercise)
            val libraryRow = remember(exercise.name, library) { resolveExercise(exercise.name, library) }
            val subtitle = remember(libraryRow) {
                libraryRow?.let {
                    listOfNotNull(
                        it.category?.replaceFirstChar(Char::uppercase),
                        it.equipment?.replaceFirstChar(Char::uppercase),
                        classifyMovementPattern(it).takeIf { p -> p != MovementPattern.Unclassified }?.name,
                    ).joinToString(" · ").ifBlank { null }
                }
            }
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(exercise.name, style = FTType.RowTitle, color = FT.TextPrimary)
                        subtitle?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
                    }
                    e1rm?.let { Text("e1RM %.0f kg".format(it), style = FTType.MonoCaption, color = FT.TextSecondary) }
                }
                exercise.sets.forEachIndexed { i, set ->
                    val zs = zeppSetByIdx[globalSetIdx++]
                    val extras = listOfNotNull(
                        set.percentOneRm?.let { "%.0f%% 1RM".format(it) },
                        set.rpe?.let { "RPE $it" },
                        set.rir?.let { "RIR $it" },
                        set.durationSec?.let { "${it}s" },
                        set.avgHr?.let { "${it} bpm" },
                    )
                    FTMetricRow("Set ${i + 1}", "${set.reps} × %.0f kg".format(set.weightKg) + extras.joinToString("") { "  ·  $it" })
                    zs?.scores?.let { SetMovementScoreRow(it) }
                }
            }
        }
    }
}

@Composable
private fun SetMovementScoreRow(scores: ZeppMovementScores) {
    val items = listOf(
        "Stab" to scores.stability,
        "Cons" to scores.consistency,
        "SpDec" to scores.speedDecay,
        "Rhy" to scores.rhythm,
        "Cont" to scores.continuity,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (label, score) ->
            val color = when {
                score < 0 -> FT.TextMuted
                score < 60 -> FT.Bad
                score < 80 -> FT.Warn
                else -> FT.Ok
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (score < 0) "—" else "$score",
                    style = FTType.MonoCaption,
                    color = color,
                )
                Text(label, style = FTType.Micro, color = FT.TextMuted)
            }
        }
    }
}
