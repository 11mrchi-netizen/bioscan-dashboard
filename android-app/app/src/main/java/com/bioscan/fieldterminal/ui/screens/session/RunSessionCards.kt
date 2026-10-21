package com.bioscan.fieldterminal.ui.screens.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.RunPageSettingsStore
import com.bioscan.fieldterminal.data.ZeppWorkoutSummary
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetailRow
import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.SessionDetail
import com.bioscan.fieldterminal.domain.SessionSplit
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.domain.sessionAvgSpeedKmh
import com.bioscan.fieldterminal.domain.analysis.SessionStateObject
import com.bioscan.fieldterminal.domain.analysis.StateDimension
import com.bioscan.fieldterminal.domain.heartRateZoneBreakdown
import com.bioscan.fieldterminal.domain.heartRateZoneFor
import com.bioscan.fieldterminal.domain.initialZoneMethod
import com.bioscan.fieldterminal.domain.trail.elevationProfileWithOverlays
import com.bioscan.fieldterminal.domain.trail.gradeTimeSplit
import com.bioscan.fieldterminal.domain.zoneBounds
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.HeartRateZoneBars
import com.bioscan.fieldterminal.ui.components.HeartRateZoneMethodSwitch
import com.bioscan.fieldterminal.ui.components.InfoHelpButton
import com.bioscan.fieldterminal.ui.components.LineChart
import com.bioscan.fieldterminal.ui.components.SessionZoneInputs
import com.bioscan.fieldterminal.ui.components.StatTileSpec
import com.bioscan.fieldterminal.ui.components.TileGrid
import com.bioscan.fieldterminal.ui.components.TrailElevationChart
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.heartRateZoneColor
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.roundToInt

// The run session page (the owner's structure, Oct 2026): SUMMARY, RUN DYNAMICS (collapsible sections), TRAIL, SPLITS;
// the route stays in SessionDetailScreen. Every metric is a tile, two to a row (TileGrid). Rides, hikes and strength
// keep SessionDetailScreen's own cards.

private fun pace(minPerKm: Double): String = formatSplitPace((minPerKm * 60).roundToLong())
private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()

// ---- SUMMARY --------------------------------------------------------------------------------------------------------

@Composable
fun RunSummaryCard(header: ExerciseSessionDetailRow, detail: SessionDetail?, zepp: ZeppWorkoutSummary?) {
    val paused = detail?.pauses.orEmpty().sumOf { it.durationSec }
    val line = listOfNotNull(
        header.durationMin?.let { formatDuration(it) + if (paused >= 60) " (+${paused / 60} min paused)" else "" },
        header.details.runType?.replaceFirstChar(Char::uppercase),
        header.details.routeType?.replaceFirstChar(Char::uppercase),
        header.rpe?.let { "RPE $it/10" },
    ).joinToString(" · ")
    val active = header.caloriesActive ?: detail?.caloriesKcal?.lastOrNull()?.value
    FTCard(title = "SUMMARY") {
        // Distance big and right-aligned, with its context line under it (owner's layout).
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            header.distanceKm?.let { km ->
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("%.2f".format(km), style = FTType.DisplayMetric, color = FT.TextPrimary)
                    Text("KM", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            if (line.isNotBlank()) Text(line, style = FTType.BodySmall, color = FT.TextSecondary, textAlign = TextAlign.End)
        }
        TileGrid(
            listOfNotNull(
                header.elevationGainM?.let {
                    StatTileSpec("ELEVATION GAIN", "${it.roundToInt()}", "m", secondary = zepp?.smoothedAscentM?.let { s -> "Smoothed ascent ${s.roundToInt()} m" })
                },
                active?.let {
                    StatTileSpec("ACTIVE CALORIES", "${it.roundToInt()}", "kcal", secondary = header.caloriesTotal?.let { t -> "Total ${t.roundToInt()} kcal" })
                },
            ),
        )
        header.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
    }
}

// ---- RUN DYNAMICS ---------------------------------------------------------------------------------------------------

private enum class DynamicsSection(val title: String) { HEART_RATE("HEART RATE"), PACE("PACE"), POWER("POWER"), CADENCE("CADENCE"), VERTICAL_RATIO("VERTICAL RATIO") }

@Composable
fun RunDynamicsCard(
    header: ExerciseSessionDetailRow,
    detail: SessionDetail?,
    zepp: ZeppWorkoutSummary?,
    zones: SessionZoneInputs?,
    loading: Boolean,
    error: String?,
    hasTimeSeries: Boolean,
) {
    // Every section starts open, tiles then chart; a tap folds one, remembered for every run (RunPageSettingsStore).
    val context = LocalContext.current
    var folded by remember { mutableStateOf(RunPageSettingsStore.foldedSections(context)) }
    fun toggle(s: DynamicsSection) {
        folded = if (s.name in folded) folded - s.name else folded + s.name
        RunPageSettingsStore.saveFoldedSections(context, folded)
    }
    fun isOpen(s: DynamicsSection) = s.name !in folded

    val d = detail
    val pace = remember(d) { d?.speedKmh.orEmpty().mapNotNull { p -> if (p.value > 0.5) TimePoint(p.offsetSeconds, 60.0 / p.value) else null } }
    val all = listOfNotNull(d?.heartRate, pace, d?.powerW, d?.cadenceSpm, d?.verticalRatioPct).filter { it.isNotEmpty() }
    val xRange = if (all.isNotEmpty()) all.minOf { it.first().offsetSeconds } to all.maxOf { it.last().offsetSeconds } else null
    val pauses = d?.pauses.orEmpty().map { it.startOffset to it.endOffset }

    var zoneMethod by remember(zones) { mutableStateOf(zones?.let { initialZoneMethod(it.defaultMethod, it.anchors, it.sport) }) }
    val bounds = remember(zoneMethod, zones) { zones?.let { z -> zoneMethod?.let { zoneBounds(it, z.anchors, z.sport) } } }
    // The heart-rate zone at a moment, for colouring pace by effort.
    val hrSorted = remember(d) { d?.heartRate.orEmpty().sortedBy { it.offsetSeconds } }
    val zoneColorAt: ((Long) -> Color)? = bounds?.let { b ->
        { t: Long ->
            val i = hrSorted.binarySearchBy(t) { it.offsetSeconds }.let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
            hrSorted.getOrNull(i)?.let { heartRateZoneColor(heartRateZoneFor(it.value, b)) } ?: FT.Category.Activity.c500
        }
    }

    FTCard(title = "RUN DYNAMICS") {
        when {
            !hasTimeSeries && d == null -> Text("No time series for this session (logged before Health Connect and without a Zepp workout).", style = FTType.BodySmall, color = FT.TextSecondary)
            loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FT.Emerald) }
            error != null -> Text("Couldn't load the time series ($error).", style = FTType.BodySmall, color = FT.TextSecondary)
        }

        // HEART RATE
        val hr = d?.heartRate.orEmpty()
        val avgHr = header.avgHr ?: hr.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
        if (avgHr != null || hr.isNotEmpty()) {
            Section(DynamicsSection.HEART_RATE, isOpen(DynamicsSection.HEART_RATE), ::toggle) {
                TileGrid(
                    listOfNotNull(
                        avgHr?.let { StatTileSpec("AVG HR", "${it.roundToInt()}", "bpm", secondary = (header.maxHr ?: hr.maxOfOrNull { p -> p.value })?.let { m -> "Max ${m.roundToInt()} bpm" }) },
                        zepp?.hrDecouplingPct?.let { StatTileSpec("HR DECOUPLING", "%+.1f".format(it), "%", helpTitle = "HR decoupling", helpBody = HR_DECOUPLING_HELP) },
                    ),
                )
            }
            if (isOpen(DynamicsSection.HEART_RATE) && hr.size >= 2) {
                // Coloured by zone only (the LINE / ZONES switch is gone); the zone method stays switchable.
                LineChart(
                    points = hr,
                    color = FT.DomainHeart,
                    xRange = xRange,
                    segmentColor = bounds?.let { b -> { v: Double -> heartRateZoneColor(heartRateZoneFor(v, b)) } },
                    pauses = pauses,
                    valueFormat = { "%.0f bpm".format(it) },
                    unit = "bpm",
                )
                if (zones != null && bounds != null) {
                    HeartRateZoneMethodSwitch(zones, bounds.method, onSelect = { zoneMethod = it })
                    Text("TIME IN ZONES", style = FTType.LabelCaps, color = FT.TextMuted)
                    val breakdown = remember(hr, bounds, d?.pauses) { heartRateZoneBreakdown(hr, bounds, d?.pauses.orEmpty()) }
                    HeartRateZoneBars(breakdown, bounds)
                }
            }
            if (isOpen(DynamicsSection.HEART_RATE) && zepp != null && (zepp.lactateThresholdHrBpm != null || zepp.lactateThresholdPaceSecPerKm != null)) {
                // Zepp's own rolling estimate as of this run, not a test.
                val lt = listOfNotNull(zepp.lactateThresholdHrBpm?.let { "%.0f bpm".format(it) }, zepp.lactateThresholdPaceSecPerKm?.let { formatSplitPace(it.toLong()) })
                HelpRow("Lactate threshold (as of this run)", lt.joinToString("  ·  "), LACTATE_THRESHOLD_HELP)
            }
        }

        // PACE
        val avgPace = (
            sessionAvgSpeedKmh(header.type, header.avgSpeedKmh, header.distanceKm, header.durationMin)
                ?: d?.speedKmh?.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
            )?.takeIf { it > 0 }?.let { 60.0 / it }
        if (avgPace != null || pace.isNotEmpty()) {
            Section(DynamicsSection.PACE, isOpen(DynamicsSection.PACE), ::toggle) {
                TileGrid(
                    listOfNotNull(
                        avgPace?.let { StatTileSpec("AVG PACE", pace(it).removeSuffix(" /km"), "/km", secondary = zepp?.gapMinPerKm?.let { g -> "Grade-adjusted ${pace(g)}" }, helpTitle = zepp?.gapMinPerKm?.let { "Grade-adjusted pace" }, helpBody = zepp?.gapMinPerKm?.let { GAP_HELP }) },
                        zepp?.efficiencyFactor?.let { StatTileSpec("EFFICIENCY FACTOR", "%.2f".format(it), helpTitle = "Efficiency factor", helpBody = EF_SESSION_HELP) },
                    ),
                )
            }
            if (isOpen(DynamicsSection.PACE) && pace.size >= 2) {
                LineChart(
                    points = pace,
                    color = FT.Category.Activity.c500,
                    xRange = xRange,
                    segmentColorAt = zoneColorAt,
                    pauses = pauses,
                    valueFormat = { pace(it) },
                    invertY = true,
                    unit = if (zoneColorAt != null) "pace · coloured by heart-rate zone" else "pace · min:sec per km",
                )
            } else if (isOpen(DynamicsSection.PACE) && !loading) NoSeries("pace")
        }

        // POWER
        val power = d?.powerW.orEmpty()
        val avgPower = header.avgPowerW ?: power.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
        if (avgPower != null) {
            Section(DynamicsSection.POWER, isOpen(DynamicsSection.POWER), ::toggle) {
                TileGrid(listOf(StatTileSpec("AVG POWER", "${avgPower.roundToInt()}", "W")))
            }
            if (isOpen(DynamicsSection.POWER) && power.size >= 2) {
                LineChart(points = power, color = FT.Category.Activity.c300, xRange = xRange, pauses = pauses, valueFormat = { "%.0f W".format(it) }, unit = "W")
            } else if (isOpen(DynamicsSection.POWER) && !loading) NoSeries("power", "Power over time is read from Health Connect on this phone.")
        }

        // CADENCE
        val cadence = d?.cadenceSpm.orEmpty()
        val avgCadence = zepp?.avgCadenceSpm ?: cadence.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
        if (avgCadence != null || zepp?.avgStrideLengthCm != null) {
            Section(DynamicsSection.CADENCE, isOpen(DynamicsSection.CADENCE), ::toggle) {
                TileGrid(
                    listOfNotNull(
                        avgCadence?.let { StatTileSpec("AVG CADENCE", "${it.roundToInt()}", "spm", secondary = (zepp?.maxCadenceSpm ?: cadence.maxOfOrNull { p -> p.value })?.let { m -> "Max ${m.roundToInt()} spm" }) },
                        zepp?.avgStrideLengthCm?.let {
                            StatTileSpec(
                                "STRIDE LENGTH", "%.2f".format(it / 100), "m",
                                secondary = zepp.avgGroundContactMs?.let { g -> "Ground contact ${g.roundToInt()} ms" },
                                helpTitle = "Stride length and ground contact", helpBody = STRIDE_LENGTH_HELP + "\n\n" + GROUND_CONTACT_HELP,
                            )
                        },
                    ),
                )
            }
            if (isOpen(DynamicsSection.CADENCE) && cadence.size >= 2) {
                LineChart(points = cadence, color = FT.TextSecondary, xRange = xRange, pauses = pauses, valueFormat = { "%.0f spm".format(it) }, unit = "spm")
            } else if (isOpen(DynamicsSection.CADENCE) && !loading) NoSeries("cadence")
        }

        // VERTICAL RATIO
        val vr = d?.verticalRatioPct.orEmpty()
        val avgVr = zepp?.avgVerticalStrideRatioPct ?: vr.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
        if (avgVr != null) {
            Section(DynamicsSection.VERTICAL_RATIO, isOpen(DynamicsSection.VERTICAL_RATIO), ::toggle) {
                TileGrid(listOf(StatTileSpec("AVG VERTICAL RATIO", "%.1f".format(avgVr), "%", helpTitle = "Vertical ratio", helpBody = VERTICAL_RATIO_HELP)))
            }
            if (isOpen(DynamicsSection.VERTICAL_RATIO) && vr.size >= 2) {
                LineChart(points = vr, color = FT.TextSecondary, xRange = xRange, pauses = pauses, valueFormat = { "%.1f %%".format(it) }, unit = "% of stride")
            } else if (isOpen(DynamicsSection.VERTICAL_RATIO) && !loading) NoSeries("vertical ratio")
        }
        if (pauses.isNotEmpty()) Text("The charts run on moving time: a dashed line marks each pause and its length.", style = FTType.Caption, color = FT.TextMuted)
    }
}

// An open section whose average exists but whose per-second series does not says so, rather than leaving a gap.
@Composable
private fun NoSeries(what: String, why: String? = null) {
    Text(listOfNotNull("No $what over time for this run.", why).joinToString(" "), style = FTType.Caption, color = FT.TextMuted)
}

// A section's header (title and a chevron, tap to open or close) and its tiles; the caller draws its chart when open.
@Composable
private fun Section(section: DynamicsSection, isOpen: Boolean, onToggle: (DynamicsSection) -> Unit, tiles: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = if (isOpen) "Collapse ${section.title}" else "Expand ${section.title}") { onToggle(section) }
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(section.title, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f))
        Text(if (isOpen) "▴" else "▾", style = FTType.Label, color = FT.TextSecondary)
    }
    tiles()
}

// ---- TRAIL ----------------------------------------------------------------------------------------------------------

@Composable
fun RunTrailCard(state: SessionStateObject, routePoints: List<RoutePoint>, detail: SessionDetail) {
    fun dim(d: StateDimension) = state.dimensions.getValue(d).value
    val profile = remember(routePoints, detail) { elevationProfileWithOverlays(routePoints, detail.heartRate, detail.speedKmh, detail.cadenceSpm) }
    val split = remember(routePoints) { gradeTimeSplit(routePoints) }
    FTCard(title = "TRAIL") {
        TileGrid(
            listOfNotNull(
                dim(StateDimension.MOUNTAIN_INDEX)?.let {
                    StatTileSpec("MOUNTAIN INDEX", "%.0f".format(it), "m/km", secondary = dim(StateDimension.KM_EFFORT)?.let { k -> "KM-effort %.1f".format(k) },
                        helpTitle = "Mountain index and km-effort", helpBody = MOUNTAIN_INDEX_HELP + "\n\n" + KM_EFFORT_HELP)
                },
                dim(StateDimension.AVERAGE_VAM)?.let {
                    val gain = dim(StateDimension.ELEVATION_GAIN_M)?.roundToInt()
                    val loss = dim(StateDimension.ELEVATION_LOSS_M)?.roundToInt()
                    StatTileSpec("AVG VAM", "${it.roundToInt()}", "m/h",
                        secondary = listOfNotNull(gain?.let { g -> "+$g m" }, loss?.let { l -> "−$l m" }).joinToString(" · ").ifBlank { null },
                        helpTitle = "VAM", helpBody = VAM_HELP)
                },
            ),
        )
        if (profile.size >= 2) TrailElevationChart(profile, modifier = Modifier.padding(vertical = 4.dp))
        split?.let { GradeSplitDonut(it) }
        TwoColumns(
            "UPHILL" to "DOWNHILL",
            listOf(
                Triple("Efficiency", dim(StateDimension.UPHILL_EFFICIENCY)?.let { "%.2f".format(it) }, dim(StateDimension.DOWNHILL_EFFICIENCY)?.let { "%.2f".format(it) }),
                Triple("Consistency (CV)", dim(StateDimension.CLIMB_CONSISTENCY)?.let { "%.2f".format(it) }, dim(StateDimension.DESCENT_CONSISTENCY)?.let { "%.2f".format(it) }),
            ),
        )
        dim(StateDimension.PACE_DEGRADATION)?.let { HelpRow("Pace degradation", "%+.1f%%".format(it), PACE_DEGRADATION_HELP) }
        dim(StateDimension.VAM_DEGRADATION)?.let { HelpRow("VAM degradation", "%+.1f%%".format(it), VAM_DEGRADATION_HELP) }
    }
}

private val GRADE_COLORS = listOf(FT.Category.Activity.c700, FT.Category.Activity.c500, FT.Category.Activity.c300)

// Uphill / flat / downhill as shares of moving time; the legend carries the words and percentages.
@Composable
private fun GradeSplitDonut(split: com.bioscan.fieldterminal.domain.trail.GradeTimeSplit) {
    val parts = listOf("UPHILL" to split.uphillSec, "FLAT" to split.flatSec, "DOWNHILL" to split.downhillSec)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Canvas(Modifier.size(96.dp)) {
            val stroke = size.minDimension * 0.22f
            val arc = Size(size.width - stroke, size.height - stroke)
            var start = -90f
            parts.forEachIndexed { i, (_, sec) ->
                val sweep = (split.share(sec) * 360).toFloat()
                // A 2-degree gap between parts keeps them apart.
                if (sweep > 2f) drawArc(GRADE_COLORS[i], start + 1f, sweep - 2f, useCenter = false, style = Stroke(stroke), topLeft = Offset(stroke / 2, stroke / 2), size = arc)
                start += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("MOVING TIME BY GRADE", style = FTType.LabelCaps, color = FT.TextMuted)
            parts.forEachIndexed { i, (label, sec) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(GRADE_COLORS[i], CircleShape))
                    Text("$label ${(split.share(sec) * 100).roundToInt()}% · ${sec / 60} min", style = FTType.BodySmall, color = FT.TextSecondary)
                }
            }
            Text("Flat: −3 % to +3 % grade", style = FTType.Micro, color = FT.TextMuted)
        }
    }
}

// Uphill | downhill side by side: one row per measure, a column per direction.
@Composable
private fun TwoColumns(heads: Pair<String, String>, rows: List<Triple<String, String?, String?>>) {
    val shown = rows.filter { it.second != null || it.third != null }
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                InfoHelpButton("Uphill and downhill", GRADE_EFFICIENCY_HELP + "\n\n" + CONSISTENCY_HELP)
            }
            Text(heads.first, style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            Text(heads.second, style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        shown.forEach { (label, up, down) ->
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = FTType.Body, color = FT.TextSecondary, modifier = Modifier.weight(1.2f))
                Text(up ?: "—", style = FTType.Value, color = FT.TextPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text(down ?: "—", style = FTType.Value, color = FT.TextPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }
        Text("Efficiency: vertical m/h per bpm. Consistency: lower is more even.", style = FTType.Micro, color = FT.TextMuted)
    }
}

@Composable
private fun HelpRow(label: String, value: String, help: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Body, color = FT.TextSecondary)
        InfoHelpButton(label, help, modifier = Modifier.padding(start = 6.dp))
        Text(value, style = FTType.Value, color = FT.TextPrimary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

// ---- SPLITS ---------------------------------------------------------------------------------------------------------

// Moving pace per km and its heart rate, the fastest marked; a km with a pause in it shows its clock time too.
@Composable
fun RunSplitsCard(splits: List<SessionSplit>) {
    if (splits.isEmpty()) return
    val fastest = splits.minByOrNull { it.durationSec }?.km
    FTCard(title = "SPLITS", info = "Moving time per kilometre: a pause inside a kilometre is left out of its pace, and its clock time is shown beside it.") {
        splits.forEach { s ->
            val isFastest = s.km == fastest
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("KM ${s.km}", style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                s.elapsedSec?.let { Text("${formatSplitPace(it).removeSuffix(" /km")} with pause  ", style = FTType.Caption, color = FT.TextMuted) }
                Text(
                    formatSplitPace(s.durationSec) + (s.avgHr?.let { "  ·  %.0f bpm".format(it) } ?: "") + (if (isFastest) "  ★" else ""),
                    style = if (isFastest) FTType.Telemetry else FTType.Value,
                    color = if (isFastest) FT.Emerald else FT.TextPrimary,
                )
            }
        }
    }
}
