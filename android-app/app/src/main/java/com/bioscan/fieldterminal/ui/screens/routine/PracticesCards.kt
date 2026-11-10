package com.bioscan.fieldterminal.ui.screens.routine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.NewLog
import com.bioscan.fieldterminal.data.routine.PracticesRepository
import com.bioscan.fieldterminal.data.routine.RoutineTimerStore
import com.bioscan.fieldterminal.data.routine.RunningTimer
import com.bioscan.fieldterminal.domain.routine.ActivityCategory
import com.bioscan.fieldterminal.domain.routine.ActivityKind
import com.bioscan.fieldterminal.domain.routine.LogSource
import com.bioscan.fieldterminal.domain.routine.LogStatus
import com.bioscan.fieldterminal.domain.routine.PracticeState
import com.bioscan.fieldterminal.domain.routine.PracticeToday
import com.bioscan.fieldterminal.domain.routine.RoutineActivity
import com.bioscan.fieldterminal.domain.routine.RoutineLog
import com.bioscan.fieldterminal.domain.routine.TimeLine
import com.bioscan.fieldterminal.domain.routine.TimeSource
import com.bioscan.fieldterminal.domain.routine.adherence
import com.bioscan.fieldterminal.domain.routine.blockMinutes
import com.bioscan.fieldterminal.domain.routine.inferredTime
import com.bioscan.fieldterminal.domain.routine.loggedTime
import com.bioscan.fieldterminal.domain.routine.minutesWords
import com.bioscan.fieldterminal.domain.routine.practicesToday
import com.bioscan.fieldterminal.domain.routine.scheduleWords
import com.bioscan.fieldterminal.domain.routine.weekTime
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTSwitch
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// Routine R7 on Lab › ROUTINE (domain/routine/Practices.kt):
//   PRACTICES       today's practices with DONE / START / SKIP, how each has been kept over 28 days, ADD A PRACTICE;
//   TIME            the running timer (STOP, DISCARD), one tap to start timing anything, ADD TIME AFTERWARDS, today's blocks;
//   THE WEEK'S TIME what was logged and what other data shows (watch, location, phone, sleep), today and the daily average
//                   of the 7 days before.
// What was logged is a view of what you entered, not a finding: what goes with what is for N=1's evidence board, later.

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

private fun parseTime(text: String): LocalTime? {
    val t = text.trim().replace('.', ':')
    if (t.isEmpty()) return null
    return runCatching { LocalTime.parse(if (t.length == 4 && ':' in t) "0$t" else t) }.getOrNull()
        ?: t.toIntOrNull()?.takeIf { it in 0..23 }?.let { LocalTime.of(it, 0) }
}

@Composable
fun RoutineTimeSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { PracticesRepository(SupabaseClientProvider.client) }
    var activities by remember { mutableStateOf<List<RoutineActivity>?>(null) }
    var logs by remember { mutableStateOf<List<RoutineLog>>(emptyList()) }
    var week by remember { mutableStateOf<List<TimeLine>>(emptyList()) }
    var timer by remember { mutableStateOf(RoutineTimerStore.current(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<RoutineActivity?>(null) }
    var newKind by remember { mutableStateOf<ActivityKind?>(null) }
    var logging by remember { mutableStateOf<Pair<RoutineLog?, Long?>?>(null) }
    val cacheVersion = com.bioscan.fieldterminal.ui.components.rememberCacheVersion()

    LaunchedEffect(reload, cacheVersion) {
        scope.launchSafe(onError = { error = describeLoadFailure(it) }) {
            val today = LocalDate.now()
            val a = repo.activities()
            val l = repo.logsSince(today.minusDays(28))
            activities = a
            logs = l
            timer = RoutineTimerStore.current(context)
            week = weekTime(loggedTime(repo.activities(includeArchived = true), l) + inferredTime(repo.inferred(today.minusDays(7))), today)
            error = null
        }
    }
    fun act(block: suspend () -> Unit) = scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { timer = RoutineTimerStore.current(context); reload++ }) { block() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        error?.let { Text("Couldn't load or save: $it", style = FTType.BodySmall, color = FT.Critical) }
        val list = activities
        PracticesCard(
            list, logs,
            onDone = { p -> act { repo.saveLog(NewLog(p.activity.id, LocalDate.now(), null, null, p.activity.targetMin?.toDouble(), LogStatus.DONE, LogSource.MANUAL, null, "reminder:${p.activity.id}:${LocalDate.now()}")) } },
            onSkip = { p -> act { repo.saveLog(NewLog(p.activity.id, LocalDate.now(), null, null, null, LogStatus.SKIPPED, LogSource.MANUAL, null, "reminder:${p.activity.id}:${LocalDate.now()}")) } },
            onStart = { a -> act { RoutineTimerStore.start(context, a.id, a.name) } },
            onEdit = { editing = it },
            onAdd = { newKind = ActivityKind.PRACTICE },
        )
        TimeCard(
            list, logs, timer,
            onStart = { a -> act { RoutineTimerStore.start(context, a.id, a.name) } },
            onStop = { act { RoutineTimerStore.stop(context) } },
            onDiscard = { RoutineTimerStore.discard(context); timer = null },
            onNewActivity = { newKind = ActivityKind.PASTIME },
            onAddAfter = { if (activities.isNullOrEmpty()) newKind = ActivityKind.PASTIME else logging = null to null },
            onOpenLog = { logging = it to it.activityId },
        )
        WeekTimeCard(week)
    }

    val sheetActivity = editing ?: newKind?.let { RoutineActivity(0, "", it, if (it == ActivityKind.PRACTICE) ActivityCategory.MIND else ActivityCategory.SCREEN) }
    sheetActivity?.let { a ->
        ActivitySheet(
            a, isNew = editing == null,
            onDismiss = { editing = null; newKind = null },
            onSave = { saved -> act { repo.saveActivity(if (editing == null) null else saved.id, saved) }; editing = null; newKind = null },
            onArchive = { act { repo.archive(a.id) }; editing = null; newKind = null },
        )
    }
    logging?.let { (log, _) ->
        val acts = activities.orEmpty()
        if (acts.isNotEmpty()) {
            LogSheet(
                acts, log,
                onDismiss = { logging = null },
                onSave = { id, new -> act { if (id == null) repo.saveLog(new) else repo.updateLog(id, new) }; logging = null },
                onDelete = { id -> act { repo.deleteLog(id) }; logging = null },
            )
        }
    }
}

@Composable
private fun PracticesCard(
    activities: List<RoutineActivity>?,
    logs: List<RoutineLog>,
    onDone: (PracticeToday) -> Unit,
    onSkip: (PracticeToday) -> Unit,
    onStart: (RoutineActivity) -> Unit,
    onEdit: (RoutineActivity) -> Unit,
    onAdd: () -> Unit,
) {
    FTCard(
        title = "PRACTICES",
        info = "What you do on purpose, on a schedule: meditation every morning, sauna on Tuesdays and Thursdays. With its " +
            "reminder on, a notification comes at its time with DONE, START (times it) and SKIP. Tap a practice to change it.",
        infoDetails = "DONE logs the planned length; START runs the timer and logs what it measures. A skip is kept as a " +
            "choice, so how often a practice is kept counts only the days it was scheduled. Reminders can be turned off in " +
            "Setup › Notifications › PRACTICES.",
    ) {
        if (activities == null) {
            Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        val today = LocalDate.now()
        val list = practicesToday(activities, logs, today, LocalTime.now())
        if (list.isEmpty()) Text("No practices yet. Add one, with its days and time.", style = FTType.BodySmall, color = FT.TextSecondary)
        list.forEach { p ->
            val a = p.activity
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth().ftClickable(onClickLabel = "Change this practice") { onEdit(a) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(a.time?.format(HM) ?: "--:--", style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name.uppercase() + (a.targetMin?.let { " · $it MIN" } ?: ""), style = FTType.LabelCaps, color = FT.TextPrimary)
                        val kept = adherence(a, logs, today).takeIf { a.isScheduledPractice() }
                        Text(
                            listOfNotNull(
                                scheduleWords(a.days).uppercase().takeIf { a.isScheduledPractice() },
                                kept?.takeIf { it.scheduled > 0 }?.let { "${it.done} OF ${it.scheduled} DAYS" },
                                kept?.takeIf { it.streak > 1 }?.let { "${it.streak} IN A ROW" },
                                "REMINDER".takeIf { a.remind },
                            ).joinToString(" · ").ifBlank { "NO SCHEDULE" },
                            style = FTType.MonoCaption, color = FT.TextSecondary,
                        )
                    }
                    Text(
                        if (p.state == PracticeState.DONE) "DONE · ${minutesWords(p.minutesDone).uppercase()}" else p.state.label,
                        style = FTType.LabelCaps, color = when (p.state) {
                            PracticeState.DONE -> FT.Ok
                            PracticeState.DUE -> FT.Warn
                            else -> FT.TextSecondary
                        },
                    )
                }
                if (p.state == PracticeState.DUE || p.state == PracticeState.LATER || p.state == PracticeState.ANYTIME) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FTButton("DONE", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) { onDone(p) }
                        FTButton("START", modifier = Modifier.weight(1f)) { onStart(a) }
                        if (p.state != PracticeState.ANYTIME) FTButton("SKIP", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.weight(1f)) { onSkip(p) }
                    }
                }
            }
        }
        FTButton("ADD A PRACTICE", modifier = Modifier.fillMaxWidth(), onClick = onAdd)
    }
}

private fun RoutineActivity.isScheduledPractice() = kind == ActivityKind.PRACTICE && !days.isNullOrEmpty()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeCard(
    activities: List<RoutineActivity>?,
    logs: List<RoutineLog>,
    timer: RunningTimer?,
    onStart: (RoutineActivity) -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onNewActivity: () -> Unit,
    onAddAfter: () -> Unit,
    onOpenLog: (RoutineLog) -> Unit,
) {
    FTCard(
        title = "TIME",
        info = "Time no other data shows: TV, reading, a hobby. Start a timer when you begin (it keeps running with the app " +
            "closed, with STOP in its notification), or add a block afterwards. Time after midnight counts for the evening before.",
    ) {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(timer) { while (timer != null) { kotlinx.coroutines.delay(30_000); tick++ } }
        if (timer != null) {
            val min = remember(tick, timer) { Duration.between(timer.start, LocalDateTime.now()).toMinutes().toDouble() }
            Text("${timer.name.uppercase()} · SINCE ${timer.start.format(HM)}", style = FTType.LabelCaps, color = FT.TextPrimary)
            Text(minutesWords(min), style = FTType.Telemetry, color = FT.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FTButton("STOP AND SAVE", emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f), onClick = onStop)
                FTButton("DISCARD", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.weight(1f), onClick = onDiscard)
            }
        } else {
            Text("START TIMING", style = FTType.LabelCaps, color = FT.TextMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                activities.orEmpty().sortedBy { it.kind != ActivityKind.PASTIME }.forEach { a -> Chip(a.name.uppercase(), on = false) { onStart(a) } }
                Chip("+ NEW", on = false, onClick = onNewActivity)
            }
        }
        FTButton("ADD TIME AFTERWARDS", modifier = Modifier.fillMaxWidth().padding(top = 6.dp), onClick = onAddAfter)
        val byId = activities.orEmpty().associateBy { it.id }
        val todays = logs.filter { it.date == LocalDate.now() && it.status == LogStatus.DONE }.sortedBy { it.start }
        if (todays.isNotEmpty()) {
            Text("TODAY", style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp))
            todays.forEach { l ->
                Row(
                    Modifier.fillMaxWidth().ftClickable(onClickLabel = "Change this block") { onOpenLog(l) }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (l.start != null) "${l.start.format(HM)}-${l.end?.format(HM) ?: "…"}" else "—",
                        style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.width(84.dp),
                    )
                    Text((byId[l.activityId]?.name ?: "Archived").uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                    Text(minutesWords(l.length).uppercase(), style = FTType.MonoCaption, color = FT.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun WeekTimeCard(lines: List<TimeLine>) {
    FTCard(
        title = "THE WEEK'S TIME",
        info = "Where time went: what you logged, and what other data already shows: training from the watch, walking and " +
            "cycling from trips and time outdoors in daylight (Locations), phone use by kind of app, and sleep. Today so far, " +
            "and the daily average over the 7 days before (a day with none counts as zero).",
        infoDetails = "Nothing here is added together: a walk can also be time outdoors, and the phone can be on during TV. " +
            "Running in trips is left to the watch's sessions, which already count it. A stream that is off shows nothing.",
    ) {
        if (lines.isEmpty()) {
            Text("Nothing yet: log some time, or turn on Locations and Phone use in Setup › Profile & goals › Data capture.", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Text("TODAY", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.width(64.dp))
            Text("7-DAY AVG", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.width(72.dp))
        }
        lines.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.label.uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary, maxLines = 1)
                    Text(l.source.label + if (l.daysWith > 0) " · ${l.daysWith} OF 7 DAYS" else "", style = FTType.MonoCaption, color = FT.TextSecondary)
                }
                Text(if (l.todayMin >= 1) minutesWords(l.todayMin) else "—", style = FTType.Telemetry, color = FT.TextPrimary, modifier = Modifier.width(64.dp))
                Text(if (l.dailyAverageMin >= 1) minutesWords(l.dailyAverageMin) else "—", style = FTType.Telemetry, color = FT.TextSecondary, modifier = Modifier.width(72.dp))
            }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Text(
        text, style = FTType.LabelCaps, color = if (on) FT.PlatinumInk else FT.TextSecondary,
        modifier = Modifier
            .border(FT.BorderWidth, if (on) Color.Transparent else FT.GlassBorder, shape)
            .background(if (on) FT.PlatinumSolid else Color.Transparent, shape)
            .ftClickable(shape = shape, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Label(text: String) = Text(text, color = FT.TextMuted, style = FTType.LabelCaps, modifier = Modifier.padding(bottom = 6.dp))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ActivitySheet(initial: RoutineActivity, isNew: Boolean, onDismiss: () -> Unit, onSave: (RoutineActivity) -> Unit, onArchive: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var category by remember { mutableStateOf(initial.category) }
    var target by remember { mutableStateOf(initial.targetMin?.toString().orEmpty()) }
    var days by remember { mutableStateOf(initial.days ?: if (isNew) (1..7).toSet() else emptySet()) }
    var time by remember { mutableStateOf(initial.time?.format(HM).orEmpty()) }
    var remind by remember { mutableStateOf(if (isNew) initial.kind == ActivityKind.PRACTICE else initial.remind) }
    val targetMin = target.trim().toIntOrNull()
    val parsedTime = parseTime(time)
    val problem = when {
        name.isBlank() -> "Give it a name."
        target.isNotBlank() && (targetMin == null || targetMin !in 1..600) -> "Planned length: 1 to 600 minutes."
        time.isNotBlank() && parsedTime == null -> "Time as HH:MM, e.g. 07:00."
        kind == ActivityKind.PRACTICE && remind && parsedTime == null -> "A reminder needs a time."
        else -> null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isNew) "ADD" else "CHANGE", style = FTType.SectionTitle, color = FT.TextPrimary)
            Column { Label("NAME"); FieldTextField(name, { name = it }, if (kind == ActivityKind.PRACTICE) "e.g. Meditation" else "e.g. TV, Reading") }
            Column {
                Label("WHAT IT IS")
                SegmentedToggle(ActivityKind.entries, kind, { it.label }, { kind = it })
                Text(
                    if (kind == ActivityKind.PRACTICE) "Done on purpose, can be scheduled and reminded." else "Time spent, only logged.",
                    style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column {
                Label("KIND")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityCategory.entries.forEach { c -> Chip(c.label, c == category) { category = c } }
                }
            }
            if (kind == ActivityKind.PRACTICE) {
                Column { Label("PLANNED LENGTH, MIN (OPTIONAL)"); FieldTextField(target, { target = it }, "e.g. 10", keyboardType = KeyboardType.Number) }
                Column {
                    Label("DAYS")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..7).forEach { d ->
                            Chip(DayOfWeek.of(d).name.take(3), d in days) { days = if (d in days) days - d else days + d }
                        }
                    }
                    Text(if (days.isEmpty()) "No days: an anytime practice, no reminder." else scheduleWords(days), style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                Column { Label("TIME (OPTIONAL)"); FieldTextField(time, { time = it }, "e.g. 07:00", keyboardType = KeyboardType.Text) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("REMIND ME AT THAT TIME", style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                    FTSwitch(remind && days.isNotEmpty(), onCheckedChange = { remind = it }, enabled = days.isNotEmpty())
                }
            } else {
                Column { Label("USUAL LENGTH, MIN (OPTIONAL)"); FieldTextField(target, { target = it }, "e.g. 60", keyboardType = KeyboardType.Number) }
            }
            problem?.let { Text(it, style = FTType.Caption, color = FT.Warn) }
            FTButton("SAVE", enabled = problem == null, emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth()) {
                onSave(
                    initial.copy(
                        name = name.trim(), kind = kind, category = category, targetMin = targetMin,
                        days = days.takeIf { kind == ActivityKind.PRACTICE && it.isNotEmpty() },
                        time = parsedTime.takeIf { kind == ActivityKind.PRACTICE },
                        remind = kind == ActivityKind.PRACTICE && remind && days.isNotEmpty() && parsedTime != null,
                    ),
                )
            }
            if (!isNew) FTButton("REMOVE (KEEPS ITS HISTORY)", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.fillMaxWidth(), onClick = onArchive)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LogSheet(activities: List<RoutineActivity>, existing: RoutineLog?, onDismiss: () -> Unit, onSave: (Long?, NewLog) -> Unit, onDelete: (Long) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = LocalDate.now()
    var activityId by remember { mutableStateOf(existing?.activityId ?: activities.firstOrNull { it.kind == ActivityKind.PASTIME }?.id ?: activities.first().id) }
    var date by remember { mutableStateOf(existing?.date ?: today) }
    var start by remember { mutableStateOf(existing?.start?.format(HM).orEmpty()) }
    var end by remember { mutableStateOf(existing?.end?.format(HM).orEmpty()) }
    var minutes by remember { mutableStateOf(existing?.minutes?.takeIf { existing.start == null }?.let { "%.0f".format(it) }.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    val s = parseTime(start)
    val e = parseTime(end)
    // An end before the start runs past midnight: it ends the next morning.
    val startAt = s?.let { date.atTime(it).let { t -> if (it.isBefore(LocalTime.of(4, 0))) t.plusDays(1) else t } }
    val endAt = e?.let { et -> startAt?.let { st -> st.toLocalDate().atTime(et).let { if (it.isBefore(st)) it.plusDays(1) else it } } }
    val typed = minutes.trim().replace(',', '.').toDoubleOrNull()
    val length = blockMinutes(startAt, endAt) ?: typed
    val problem = when {
        start.isNotBlank() && s == null -> "Start as HH:MM."
        end.isNotBlank() && e == null -> "End as HH:MM."
        length == null -> "A start and an end, or the minutes."
        length <= 0 || length > 1440 -> "Between 1 minute and 24 hours."
        else -> null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (existing == null) "ADD TIME" else "CHANGE TIME", style = FTType.SectionTitle, color = FT.TextPrimary)
            Column {
                Label("WHAT")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activities.forEach { a -> Chip(a.name.uppercase(), a.id == activityId) { activityId = a.id } }
                }
            }
            Column {
                Label("DAY")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..6).map { today.minusDays(it.toLong()) }.forEach { d ->
                        Chip(if (d == today) "TODAY" else if (d == today.minusDays(1)) "YESTERDAY" else d.format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)).uppercase(), d == date) { date = d }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { Label("FROM"); FieldTextField(start, { start = it }, "21:30") }
                Column(Modifier.weight(1f)) { Label("TO"); FieldTextField(end, { end = it }, "22:45") }
            }
            Column { Label("OR MINUTES"); FieldTextField(minutes, { minutes = it }, "e.g. 45", keyboardType = KeyboardType.Number) }
            Column { Label("NOTE (OPTIONAL)"); FieldTextField(note, { note = it }, "", singleLine = false) }
            length?.takeIf { problem == null }?.let { Text(minutesWords(it).uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary) }
            problem?.let { Text(it, style = FTType.Caption, color = FT.Warn) }
            FTButton("SAVE", enabled = problem == null, emphasis = FTButtonEmphasis.Primary, modifier = Modifier.fillMaxWidth()) {
                val useBlock = startAt != null && endAt != null
                onSave(
                    existing?.id,
                    NewLog(
                        activityId, date, if (useBlock) startAt else null, if (useBlock) endAt else null, length,
                        LogStatus.DONE, existing?.source ?: LogSource.MANUAL, note, "manual:" + UUID.randomUUID().toString(),
                    ),
                )
            }
            if (existing != null) FTButton("DELETE", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.fillMaxWidth()) { onDelete(existing.id) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
