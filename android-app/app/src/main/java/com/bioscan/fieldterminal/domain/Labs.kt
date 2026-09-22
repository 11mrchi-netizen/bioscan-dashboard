package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LabResultRow

enum class MarkerDirection { UP, DOWN, FLAT, UNKNOWN }

data class MarkerComparison(
    val name: String,
    val unit: String?,
    val refLow: Double?,
    val refHigh: Double?,
    val earlierDisplay: String?, // formatted value or value_text from the earlier draw, null if not tested
    val latestDisplay: String?,  // same, from the latest draw
    val latestFlag: String?,     // 'normal' | 'low' | 'high' | 'watch' | null -- drives the latest value's color
    val direction: MarkerDirection,
    val earlierValue: Double?, // DAV-102: raw numeric value (not the display string) for dot-plot positioning
    val latestValue: Double?,  // against a reference range -- null for qualitative/non-numeric results
)

// Real markers merged across the two draws by name -- NOT a port of
// index.html's Labs panel (that entire panel is hardcoded prose, same
// pattern found in Step 7's Training panel: DASHBOARD_DATA.labs is fetched
// with real structured data but the panel's render() never reads it, and
// draw() is literally empty). Step 9's own scope explicitly wants the real
// lab_draws/lab_results tables with reference ranges shown, which the web
// panel mostly doesn't even display. See ROADMAP.md P8 Step 9.
//
// A marker present in only one draw shows "—" for the other, matching how
// commonly a lab panel isn't rechecked every draw (confirmed true of this
// account's real data: the two draws only partially overlap in what they
// tested). Direction/color come from real computable facts (numeric change,
// and the latest draw's own stored `flag`), not the mockup's seemingly
// hand-picked highlighting.
fun mergeMarkersAcrossDraws(earlierResults: List<LabResultRow>, latestResults: List<LabResultRow>): List<MarkerComparison> {
    val byName = linkedMapOf<String, Pair<LabResultRow?, LabResultRow?>>()
    for (r in earlierResults) byName[r.markerName] = r to byName[r.markerName]?.second
    for (r in latestResults) byName[r.markerName] = byName[r.markerName]?.first to r

    return byName.entries.map { (name, pair) ->
        val (earlier, latest) = pair
        val unit = latest?.unit ?: earlier?.unit
        val refLow = latest?.refLow ?: earlier?.refLow
        val refHigh = latest?.refHigh ?: earlier?.refHigh

        val direction = if (earlier?.value != null && latest?.value != null) {
            when {
                latest.value > earlier.value -> MarkerDirection.UP
                latest.value < earlier.value -> MarkerDirection.DOWN
                else -> MarkerDirection.FLAT
            }
        } else MarkerDirection.UNKNOWN

        MarkerComparison(
            name = name,
            unit = unit,
            refLow = refLow,
            refHigh = refHigh,
            earlierDisplay = displayValue(earlier),
            latestDisplay = displayValue(latest),
            latestFlag = latest?.flag,
            direction = direction,
            earlierValue = earlier?.value,
            latestValue = latest?.value,
        )
    }.sortedBy { it.name }
}

private fun displayValue(row: LabResultRow?): String? {
    if (row == null) return null
    row.value?.let { return formatNumber(it) }
    return row.valueText
}

private fun formatNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
