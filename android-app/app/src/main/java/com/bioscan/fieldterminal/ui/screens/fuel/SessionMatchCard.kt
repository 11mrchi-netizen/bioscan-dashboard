package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.DebriefTarget
import com.bioscan.fieldterminal.data.FuelDebriefRepository
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.FuelSettingsStore
import com.bioscan.fieldterminal.data.SessionPlanLinksRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.planKey
import com.bioscan.fieldterminal.domain.fuel.LONG_SESSION_MIN
import com.bioscan.fieldterminal.domain.fuel.PlanSource
import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.PlannedItem
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Fuel › Plan's MATCHED SESSIONS (domain/fuel/SessionMatching.kt): each recording of the last days with the plan the app
// took it for and how sure it is. One to check says so and asks RIGHT / CHANGE; any other opens the same choice: a plan
// of that day or the days either side, NOT PLANNED, or back to the app's own match. The answer is kept
// (session_plan_links) and always wins.
//
// LONG SESSION FUEL: the fuel schedule of each long session of those days that is not an event (events have their own
// FUEL tab). A planned one keeps its schedule under the plan, so it stays after the run is recorded, and the DEBRIEF
// checks what was taken against it.

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

private fun size(item: PlannedItem): String =
    listOfNotNull("${item.durationMin.roundToInt()} MIN", item.distanceKm?.let { "%.1f KM".format(it) }).joinToString(" · ")

@Composable
fun MatchedSessionsCard(recent: List<PlannedDay>, today: LocalDate, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val done = recent.filter { !it.date.isAfter(today) }.flatMap { d -> d.items.filter { it.recorded }.map { d to it } }
        .sortedWith(compareByDescending<Pair<PlannedDay, PlannedItem>> { it.second.matchUncertain }.thenByDescending { it.first.date })
    if (done.isEmpty()) return
    var changing by remember { mutableStateOf<Pair<PlannedDay, PlannedItem>?>(null) }
    FTCard(
        title = "MATCHED SESSIONS",
        modifier = modifier,
        info = "Which plan each recorded session was. The app scores each recording against the day's plans (kind of " +
            "session, start, distance or length, and the day's longest for a long plan) and keeps the match once the day " +
            "is over. Tap one to change it: your answer always wins.",
        infoDetails = "Fuel schedules made for a planned session follow the recording it is matched to, so the debrief " +
            "checks them. A match under 50 % is shown as one to check.",
    ) {
        done.forEach { (day, item) -> MatchRow(day, item, onChange = { changing = day to item }, onChanged = onChanged) }
    }
    changing?.let { (day, item) ->
        MatchSheet(day, item, recent, onDismiss = { changing = null }, onSaved = { changing = null; onChanged() })
    }
}

@Composable
private fun MatchRow(day: PlannedDay, item: PlannedItem, onChange: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().ftClickable(onClickLabel = "Change the match", onClick = onChange).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("${day.date.format(DAY).uppercase()} · ${item.type.uppercase()} · ${size(item)}", style = FTType.MonoCaption, color = FT.TextSecondary)
        val how = when {
            item.planRef == null && item.matchManual -> "NOT PLANNED · YOUR ANSWER"
            item.planRef == null -> "NO PLAN FITS"
            item.matchManual -> "YOUR ANSWER"
            else -> "${if (item.matchStored) "KEPT" else "AUTO"} ${((item.matchConfidence ?: 1.0) * 100).roundToInt()} %"
        }
        Text(
            "→ " + (item.planTitle?.uppercase() ?: "EXTRA SESSION") + " · $how" + if (item.matchUncertain) " · CHECK" else "",
            style = FTType.LabelCaps, color = if (item.matchUncertain) FT.Warn else FT.TextPrimary,
        )
        if (item.matchUncertain) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FTButton("RIGHT", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) {
                    val id = item.sessionId ?: return@FTButton
                    scope.launchSafe(onError = { error = describeLoadFailure(it) }) {
                        SessionPlanLinksRepository(SupabaseClientProvider.client).setManual(id, item.planRef, item.planTitle, day.date)
                        onChanged()
                    }
                }
                FTButton("CHANGE", modifier = Modifier.weight(1f), onClick = onChange)
            }
        }
        error?.let { Text("Couldn't save: $it", style = FTType.Caption, color = FT.Critical) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchSheet(day: PlannedDay, item: PlannedItem, recent: List<PlannedDay>, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val repo = remember { SessionPlanLinksRepository(SupabaseClientProvider.client) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // The day's plans first, then the day before and after (a long run moved by a day).
    val options = recent.filter { abs(it.date.toEpochDay() - day.date.toEpochDay()) <= 1 }
        .sortedBy { if (it.date == day.date) 0 else 1 }
        .flatMap { d -> d.plans.map { d.date to it } }
    fun save(planRef: String?, title: String?, date: LocalDate?) {
        val id = item.sessionId ?: return
        saving = true
        scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
            repo.setManual(id, planRef, title, date)
            onSaved()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("WHICH PLAN WAS THIS?", style = FTType.SectionTitle, color = FT.DomainFuel)
            Text("${day.date.format(DAY)} · ${item.type} · ${size(item).lowercase()}", style = FTType.BodySmall, color = FT.TextSecondary)
            if (options.isEmpty()) Text("Nothing was planned on this day or the days either side.", style = FTType.Caption, color = FT.TextSecondary)
            options.forEach { (date, plan) ->
                val current = plan.sourceId == item.planRef
                Column(
                    Modifier.fillMaxWidth().ftClickable(onClickLabel = "Match to this plan") { if (!saving) save(plan.sourceId, plan.title, date) }.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        plan.title.uppercase() + if (current) " · NOW" else "",
                        style = FTType.LabelCaps, color = if (current) FT.PlatinumSolid else FT.TextPrimary,
                    )
                    Text(
                        "${date.format(DAY).uppercase()} · ${sourceWord(plan.source)} · ${plan.type.uppercase()} · ${size(plan)}",
                        style = FTType.MonoCaption, color = FT.TextSecondary,
                    )
                }
            }
            FTButton("NOT PLANNED", enabled = !saving, modifier = Modifier.fillMaxWidth()) { save(null, null, null) }
            if (item.matchManual) {
                FTButton("LET THE APP MATCH IT", enabled = !saving, emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.fillMaxWidth()) {
                    val id = item.sessionId ?: return@FTButton
                    saving = true
                    scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) { repo.clear(id); onSaved() }
                }
            }
            error?.let { Text("Couldn't save: $it", style = FTType.Caption, color = FT.Critical) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun sourceWord(s: PlanSource): String = when (s) {
    PlanSource.EVENT -> "EVENT"
    PlanSource.PLANNED_SESSION -> "TRAINING PLAN"
    PlanSource.CALENDAR -> "CALENDAR"
    PlanSource.RECORDED -> "RECORDED"
}

/** Where a long session's schedule is kept: under its plan (planned or recorded as one), else on the recording itself. */
internal fun scheduleKey(item: PlannedItem): Pair<String, String>? = when {
    item.eventId != null -> null
    !item.recorded -> "planned" to planKey(item.sourceId)
    item.planRef != null && !item.planRef.startsWith("event:") -> "planned" to planKey(item.planRef)
    item.sessionId != null -> "session" to item.sessionId.toString()
    else -> null
}

/** A long session that is not an event: the ones whose fuelling gets tried out. */
internal fun isLongNonEvent(item: PlannedItem): Boolean = item.durationMin >= LONG_SESSION_MIN && item.eventId == null

@Composable
fun LongSessionFuelCard(recent: List<PlannedDay>, today: LocalDate, modifier: Modifier = Modifier) {
    val sessions = recent.filter { !it.date.isAfter(today) }.flatMap { d -> d.items.filter(::isLongNonEvent).map { d to it } }
        .sortedByDescending { it.first.date }
    if (sessions.isEmpty()) return
    val context = LocalContext.current
    var debrief by remember { mutableStateOf<DebriefTarget?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sessions.forEach { (day, item) ->
            val key = scheduleKey(item) ?: return@forEach
            Text(
                "${day.date.format(DAY).uppercase()} · ${item.title.uppercase()} · ${size(item)}" + if (item.recorded) " · DONE" else "",
                style = FTType.LabelCaps, color = FT.TextPrimary,
            )
            androidx.compose.runtime.key(key) {
                FuelScheduleCard(
                    kind = key.first, refKey = key.second, durationMin = item.durationMin.roundToInt(),
                    toleratedGPerHour = FuelSettingsStore.get(context).toleratedGPerHour,
                    seed = { FuelPlanRepository(SupabaseClientProvider.client).seedSchedule(context, item) },
                )
            }
            if (item.recorded) {
                FTButton("DEBRIEF WHAT YOU TOOK", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth()) {
                    scope.launchSafe(onError = { error = describeLoadFailure(it) }) {
                        debrief = FuelDebriefRepository(SupabaseClientProvider.client).targetForRecording(item, day.date)
                    }
                }
            }
        }
        error?.let { Text("Couldn't open the debrief: $it", style = FTType.Caption, color = FT.Critical) }
    }
    debrief?.let { FuelDebriefSheet(it, onDismiss = { debrief = null }, onSaved = { debrief = null }) }
}
