package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.BloodPressureRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.bp.BpCategory
import com.bioscan.fieldterminal.domain.bp.BpOverview
import com.bioscan.fieldterminal.domain.bp.ClinicHomePattern
import com.bioscan.fieldterminal.domain.bp.MIN_USABLE_DAYS
import com.bioscan.fieldterminal.domain.bp.PROTOCOL_DAYS
import com.bioscan.fieldterminal.domain.bp.READINGS_PER_SITTING
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Risk profile R0: Lab › TESTS › BLOOD PRESSURE. Where the 7-day home series stands today, the latest usable home mean
// with its ACC/AHA category, the clinic reading from the last lab draw beside it, and when the next series is due.
// Readings are saved like any entry (Log shows them); cuff readings in Health Connect arrive with the sync.

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private fun categoryColor(c: BpCategory) = when (c) {
    BpCategory.NORMAL -> FT.Ok
    BpCategory.ELEVATED, BpCategory.STAGE_1 -> FT.Warn
    BpCategory.STAGE_2 -> FT.Bad
}

@Composable
internal fun BloodPressureCard(reloadKey: Int, onStart: () -> Unit) {
    var overview by remember { mutableStateOf<BpOverview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reloadKey) {
        try {
            overview = BloodPressureRepository(SupabaseClientProvider.client).loadOverview()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "error"
        }
    }

    FTCard(title = "BLOOD PRESSURE") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Home series: 2 readings 1 minute apart, morning and evening, for $PROTOCOL_DAYS days. The first day is left out of the mean.",
                    style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f),
                )
                FTButton("LOG", emphasis = FTButtonEmphasis.Primary, onClick = onStart)
            }
            val o = overview
            when {
                error != null -> Text("Couldn't load readings ($error).", style = FTType.Caption, color = FT.Bad)
                o == null -> Text("Loading…", style = FTType.Caption, color = FT.TextSecondary)
                else -> BpBody(o, LocalDate.now())
            }
        }
    }
}

@Composable
private fun BpBody(o: BpOverview, today: LocalDate) {
    o.latestCrisis?.let {
        Text(
            "A reading above 180/120 (${it.systolic}/${it.diastolic}). Measure again after 5 minutes' rest; if it stays this high, or with " +
                "chest pain, shortness of breath, weakness or trouble speaking, get medical help now.",
            style = FTType.BodySmall, color = FT.Bad,
        )
    }

    val p = o.protocol
    val protocolLine = when {
        p.day != null && !p.complete ->
            "Day ${p.day} of $PROTOCOL_DAYS · morning ${p.morningReadings}/$READINGS_PER_SITTING · evening ${p.eveningReadings}/$READINGS_PER_SITTING"
        p.complete -> "This series has $PROTOCOL_DAYS days. Done."
        o.readings.isEmpty() -> "No readings yet. Start a series with tomorrow morning's readings, or allow blood pressure in Setup › Connected services to import your cuff app's."
        else -> "No series running."
    }
    Text(protocolLine, style = FTType.Label, color = FT.TextPrimary)

    val home = o.latestUsable
    if (home != null) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${home.systolic!!.roundToInt()}/${home.diastolic!!.roundToInt()}", style = FTType.MetricMedium, color = FT.Metric.BloodPressure)
            Text("mmHg · home mean", style = FTType.Caption, color = FT.TextSecondary)
        }
        home.category?.let { Text(it.label, style = FTType.CaptionStrong, color = categoryColor(it)) }
        Text(
            "${home.readingCount} readings over ${home.countedDays.size} days, ${home.countedDays.first().format(DAY)} to ${home.last.format(DAY)}.",
            style = FTType.Caption, color = FT.TextSecondary,
        )
    } else if (o.readings.isNotEmpty()) {
        Text(
            "No home mean yet: it needs $MIN_USABLE_DAYS days of readings after the first day.",
            style = FTType.Caption, color = FT.TextSecondary,
        )
    }

    o.clinic?.let { c ->
        Text(
            "Clinic ${c.systolic.roundToInt()}/${c.diastolic.roundToInt()} mmHg on ${c.date.format(DAY)} (lab draw).",
            style = FTType.Caption, color = FT.TextSecondary,
        )
    }
    o.pattern?.let { pattern ->
        Text(pattern.label, style = FTType.CaptionStrong, color = if (pattern == ClinicHomePattern.CONSISTENT) FT.TextPrimary else FT.Warn)
        Text(pattern.explain, style = FTType.Caption, color = FT.TextSecondary)
    }
    o.nextSeriesDue?.let { due ->
        val line = if (due.isAfter(today)) "Next series due ${due.format(DAY)}." else "A new series is due (the last was more than 3 months ago)."
        Text(line, style = FTType.Caption, color = FT.TextSecondary)
    }
}
