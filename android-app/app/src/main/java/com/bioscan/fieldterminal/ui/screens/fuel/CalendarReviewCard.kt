package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.CalendarOverridesRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventSport
import com.bioscan.fieldterminal.domain.fuel.CalendarOverride
import com.bioscan.fieldterminal.domain.fuel.CalendarReview
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.OverrideKind
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.screens.events.EventFormSheet
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter
import java.util.Locale

// Fuel › Plan's WHAT IS THIS? (fuel planning phase 11): training-colour calendar entries over four hours that the rules
// cannot read are left out of the plan and asked about here: make it an event, count it as a long session of a length
// and class the person gives, or ignore it. The answer is kept (calendar_event_overrides) and the plan reloads.

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

@Composable
fun CalendarReviewCard(reviews: List<CalendarReview>, onAnswered: () -> Unit, modifier: Modifier = Modifier) {
    if (reviews.isEmpty()) return
    var making by remember { mutableStateOf<CalendarReview?>(null) }
    FTCard(
        title = "WHAT IS THIS?",
        modifier = modifier,
        info = "A training-colour entry in your calendar that runs over four hours and doesn't say what it is. It isn't counted " +
            "in the plan until you say.",
    ) {
        reviews.forEach { r -> ReviewRow(r, onMakeEvent = { making = r }, onAnswered = onAnswered) }
    }
    making?.let { r ->
        EventFormSheet(
            initial = AthleteEvent(
                name = r.title, kind = EventKind.OTHER_HARD_DAY, sport = EventSport.RUN, date = r.start.toLocalDate(), start = r.start,
                expectedDurationMin = r.clockMin, calendarEventId = r.eventId, notes = r.description?.take(500),
            ),
            onDismiss = { making = null },
            onSaved = { making = null; onAnswered() },
        )
    }
}

@Composable
private fun ReviewRow(r: CalendarReview, onMakeEvent: () -> Unit, onAnswered: () -> Unit) {
    val repo = remember { CalendarOverridesRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var counting by remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf(3.0) }
    var intensity by remember { mutableStateOf(Intensity.LONG) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(o: CalendarOverride) = scope.launchSafe(onError = { error = describeLoadFailure(it) }) { repo.save(o); onAnswered() }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(r.title.uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary)
        Text(
            "${r.start.format(WHEN).uppercase()} · ${(r.clockMin / 60).toInt()} H IN THE CALENDAR",
            style = FTType.MonoCaption, color = FT.TextSecondary,
        )
        if (!counting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FTButton("MAKE IT AN EVENT", emphasis = FTButtonEmphasis.Secondary, modifier = Modifier.weight(1f), onClick = onMakeEvent)
                FTButton("A SESSION", emphasis = FTButtonEmphasis.Secondary, modifier = Modifier.weight(1f)) { counting = true }
                FTButton("IGNORE", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.weight(1f)) {
                    save(CalendarOverride(r.eventId, OverrideKind.IGNORE))
                }
            }
        } else {
            Text("HOW LONG IS THE TRAINING IN IT?", style = FTType.LabelCaps, color = FT.TextSecondary)
            SegmentedToggle(listOf(1.0, 2.0, 3.0, 4.0, 6.0), hours, { "${it.toInt()} H" }, { hours = it }, accentColor = FT.DomainFuel)
            SegmentedToggle(listOf(Intensity.EASY, Intensity.MODERATE, Intensity.HARD, Intensity.LONG), intensity, { it.label }, { intensity = it }, accentColor = FT.DomainFuel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FTButton("SAVE", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) {
                    save(CalendarOverride(r.eventId, OverrideKind.LONG_SESSION, hours * 60, intensity))
                }
                FTButton("BACK", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.weight(1f)) { counting = false }
            }
        }
        error?.let { Text("Couldn't save: $it", style = FTType.BodySmall, color = FT.Critical) }
    }
}
