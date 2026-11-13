package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.routine.AppWeekTotal
import com.bioscan.fieldterminal.data.routine.RoutinePageData
import com.bioscan.fieldterminal.data.routine.RoutinePageRepository
import com.bioscan.fieldterminal.domain.routine.CalendarState
import com.bioscan.fieldterminal.domain.routine.DailyLine
import com.bioscan.fieldterminal.domain.routine.LogStatus
import com.bioscan.fieldterminal.domain.routine.RIBBON_SLOTS
import com.bioscan.fieldterminal.domain.routine.RibbonDay
import com.bioscan.fieldterminal.domain.routine.RibbonKind
import com.bioscan.fieldterminal.domain.routine.RoutineActivity
import com.bioscan.fieldterminal.domain.routine.RoutineLog
import com.bioscan.fieldterminal.domain.routine.adherence
import com.bioscan.fieldterminal.domain.routine.bestStreak
import com.bioscan.fieldterminal.domain.routine.changeWords
import com.bioscan.fieldterminal.domain.routine.compareWindows
import com.bioscan.fieldterminal.domain.routine.keptByFourWeeks
import com.bioscan.fieldterminal.domain.routine.minutesWords
import com.bioscan.fieldterminal.domain.routine.practiceCalendar
import com.bioscan.fieldterminal.domain.routine.practicesInWindow
import com.bioscan.fieldterminal.domain.routine.ribbonTotals
import com.bioscan.fieldterminal.domain.routine.scheduleWords
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTChevron
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.rememberCacheVersion
import com.bioscan.fieldterminal.ui.screens.detail.HistorySpec
import com.bioscan.fieldterminal.ui.screens.detail.MetricHistoryOverlay
import com.bioscan.fieldterminal.ui.screens.settings.PlacesCard
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// ME › ROUTINE (docs/routine-intelligence/README.md, "ME › ROUTINE"): the long view of the routine, top to bottom:
//   THE LAST 28 DAYS  a 24-hour ribbon a day (tap a day for its map);
//   TIME              28-day daily averages of logged time, training, walking, cycling, commuting, outdoor daylight,
//                     phone video and social, sleep; each opens its history;
//   PLACES            hours a day by kind of place, each opening its history; naming places and one-off stops below;
//   PHONE             screen time, the hour before sleep, night unlocks, video, social, games; the most used apps;
//   PRACTICES         each practice of the last 28 days; tap for its calendar;
//   THIS MONTH AGAINST THE ONE BEFORE, and REGULARITY.
// A line needs 7 recorded days in its 28 for an average; a day a stream did not record is missing, never zero.

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)

private fun ribbonColor(k: RibbonKind): Color = when (k) {
    RibbonKind.TRAINING -> FT.Metric.TimeTraining
    RibbonKind.LOGGED -> FT.Metric.TimeLogged
    RibbonKind.ASLEEP -> FT.Metric.SleepDuration
    RibbonKind.MOVE -> FT.Metric.Commute
    RibbonKind.HOME -> FT.Metric.TimeHome
    RibbonKind.WORK -> FT.Metric.TimeWork
    RibbonKind.OUT -> FT.Metric.TimeOut
    RibbonKind.UNKNOWN -> FT.GlassBorder
}

/** Each line's one hue (the ribbon's kind of time where it has one). */
private fun lineColor(key: String): Color = when {
    key.startsWith("activity:") -> FT.Metric.TimeLogged
    key.startsWith("session:") -> FT.Metric.TimeTraining
    key.startsWith("trip:") -> FT.Metric.ActiveTravel
    key == "outdoor_daylight" -> FT.Metric.Daylight
    key == "commute" -> FT.Metric.Commute
    key == "sleep" -> FT.Metric.SleepDuration
    key == "phone:bed" -> FT.Metric.PhoneSleep
    key == "phone:night" -> FT.Metric.NightScreen
    key.startsWith("phone:") -> FT.Metric.TimePhone
    key == "place:home" -> FT.Metric.TimeHome
    key == "place:work" -> FT.Metric.TimeWork
    key.startsWith("place:") -> FT.Metric.TimeOut
    key == "reg:bedtime" || key == "reg:wake" -> FT.Metric.SleepScore
    key == "reg:similarity" -> FT.Metric.TimeHome
    else -> FT.Metric.TimeOut
}

private fun hours(h: Double) = if (h < 1) "${(h * 60).roundToInt()} min" else "%.1f h".format(h)

private class LineFormat(val unit: String, val format: (Double) -> String)

private fun formatOf(key: String): LineFormat = when {
    key.startsWith("place:") -> LineFormat("h", ::hours)
    key == "phone:night" -> LineFormat("unlocks", { "%.1f".format(it) })
    key == "reg:bedtime" || key == "reg:wake" -> LineFormat("min", { "± ${it.roundToInt()} min" })
    key == "reg:similarity" -> LineFormat("%", { "${it.roundToInt()} %" })
    key == "reg:places" -> LineFormat("places", { "%.1f".format(it) })
    else -> LineFormat("min", ::minutesWords)
}

@Composable
fun RoutinePage(onOpenDay: (LocalDate) -> Unit) {
    var data by remember { mutableStateOf<RoutinePageData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<DailyLine?>(null) }
    var calendar by remember { mutableStateOf<RoutineActivity?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val cacheVersion = rememberCacheVersion()
    LaunchedEffect(reload, cacheVersion) {
        try {
            data = RoutinePageRepository(SupabaseClientProvider.client).load()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
    }

    Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        error?.let {
            Text("Couldn't load: $it", style = FTType.BodySmall, color = FT.Bad)
            FTButton("RETRY") { reload++ }
        }
        val d = data
        if (d == null) {
            if (error == null) Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            RoutinePageContent(d, onOpenDay, onOpenHistory = { history = it }, onOpenCalendar = { calendar = it })
        }
    }

    history?.let { l ->
        val f = formatOf(l.key)
        MetricHistoryOverlay(HistorySpec(l.label, l.series, f.unit, f.format, lineColor(l.key)), data?.today?.minusDays(1) ?: LocalDate.now()) { history = null }
    }
    calendar?.let { a -> data?.let { d -> PracticeCalendarSheet(a, d.logs, d.today) { calendar = null } } }
}

/** The page's cards from loaded [d] ([placesEditor]: the PLACES naming list, which loads its own rows). */
@Composable
fun RoutinePageContent(
    d: RoutinePageData,
    onOpenDay: (LocalDate) -> Unit,
    onOpenHistory: (DailyLine) -> Unit,
    onOpenCalendar: (RoutineActivity) -> Unit,
    placesEditor: Boolean = true,
) {
    val open: (DailyLine) -> Unit = onOpenHistory
    val calendarOf: (RoutineActivity) -> Unit = onOpenCalendar
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val through = d.today.minusDays(1)
        RibbonCard(d.ribbons, onOpenDay)
        LinesCard(
            "TIME", d.time, through, onOpen = open,
            info = "A daily average over the last 28 complete days: what you logged, training from the watch, walking and " +
                "cycling in trips, commuting door to door, time outdoors in daylight, phone video and social apps, sleep. " +
                "Tap one for its history.",
            empty = "Nothing yet: log time on Lab › ROUTINE, or turn on Locations and Phone use in Setup.",
        )
        LinesCard(
            "PLACES", d.places, through, onOpen = open,
            info = "Hours a day by kind of place, over the days Locations recorded at least half of. Name places, and mark a " +
                "restaurant or a shop as a one-off stop so later stays nearby are not counted as it, in the list below.",
            empty = "No recorded days yet. Turn on Locations in Setup › Profile & goals › Data capture.",
            unitCaption = "H A DAY",
        )
        if (placesEditor) PlacesCard(showDay = false)
        PhoneCard(d.phone, d.apps, through, open)
        PracticesCard(d.activities, d.logs, d.today, calendarOf)
        ComparisonCard(d.time + d.places + d.phone + d.regularity, through)
        LinesCard(
            "REGULARITY", d.regularity, through, onOpen = open,
            info = "How regular the days are: the spread of bedtimes and wake times over each 7 nights (lower is more " +
                "regular), how much a day's hours by place look like the same weekday in the 4 weeks before, and places a day.",
            empty = "Builds up with sleep and Locations data.",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RibbonCard(days: List<RibbonDay>, onOpenDay: (LocalDate) -> Unit) {
    FTCard(
        title = "THE LAST 28 DAYS",
        info = "Each day from midnight to midnight, in 15-minute pieces: training, time you logged, sleep, on the move, work, " +
            "elsewhere, home, and grey where nothing recorded. Where two overlap, the first in that list shows. Tap a day for its map.",
    ) {
        if (days.all { d -> d.spans.all { it.kind == RibbonKind.UNKNOWN } }) {
            Text("Nothing recorded yet.", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(48.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00", "06", "12", "18", "24").forEach { Text(it, style = FTType.MonoCaption, color = FT.TextMuted) }
            }
        }
        days.forEach { day ->
            Row(
                Modifier.fillMaxWidth().ftClickable(onClickLabel = "Open the day's map") { onOpenDay(day.date) }.padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(day.date.format(DAY).uppercase(), style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.width(48.dp))
                Canvas(
                    Modifier.weight(1f).height(10.dp).semantics {
                        contentDescription = "${day.date}: " + day.spans.filter { it.kind != RibbonKind.UNKNOWN }
                            .joinToString(", ") { "${it.kind.label} ${it.from / 4}:%02d to ${it.to / 4}:%02d".format((it.from % 4) * 15, (it.to % 4) * 15) }
                    },
                ) {
                    val w = size.width / RIBBON_SLOTS
                    day.spans.forEach { s ->
                        drawRoundRect(ribbonColor(s.kind), Offset(s.from * w, 0f), Size((s.to - s.from) * w - 0.5f, size.height), CornerRadius(1.5f))
                    }
                }
            }
        }
        val totals = ribbonTotals(days)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonKind.entries.forEach { k ->
                val h = (totals[k] ?: 0.0) / 60 / days.size
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(ribbonColor(k), RoundedCornerShape(2.dp)))
                    Text("${k.label.uppercase()} ${hours(h).uppercase()}", style = FTType.MonoCaption, color = FT.TextSecondary)
                }
            }
        }
        Text("Legend: hours a day, on average over these 28 days.", style = FTType.Caption, color = FT.TextMuted)
    }
}

@Composable
private fun LinesCard(
    title: String,
    lines: List<DailyLine>,
    through: LocalDate,
    onOpen: (DailyLine) -> Unit,
    info: String,
    empty: String,
    unitCaption: String = "A DAY",
    extra: @Composable (() -> Unit)? = null,
) {
    FTCard(title = title, info = info) {
        if (lines.isEmpty()) {
            Text(empty, style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Text("28-DAY AVERAGE · $unitCaption", style = FTType.MonoCaption, color = FT.TextMuted)
            }
            lines.sortedByDescending { l -> l.average(through) ?: -1.0 }.forEach { l -> LineRow(l, through, onOpen) }
        }
        extra?.invoke()
    }
}

@Composable
private fun LineRow(l: DailyLine, through: LocalDate, onOpen: (DailyLine) -> Unit) {
    val avg = l.average(through)
    val days = l.daysIn(through)
    val f = formatOf(l.key)
    Row(
        Modifier.fillMaxWidth().ftClickable(onClickLabel = "Open its history") { onOpen(l) }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).background(lineColor(l.key), RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(l.label.uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary, maxLines = 1)
            Text("${l.source.label} · $days OF 28 DAYS", style = FTType.MonoCaption, color = FT.TextSecondary)
        }
        Text(avg?.let(f.format) ?: "—", style = FTType.Telemetry, color = lineColor(l.key))
        FTChevron(tint = FT.TextSecondary)
    }
}

@Composable
private fun PhoneCard(lines: List<DailyLine>, apps: List<AppWeekTotal>, through: LocalDate, onOpen: (DailyLine) -> Unit) {
    LinesCard(
        "PHONE", lines, through, onOpen,
        info = "From Android's usage data, on the days it was read: screen time, phone use in the hour before sleep, " +
            "unlocks between falling asleep and waking, and time in video, social and game apps. Tap one for its history.",
        empty = "No phone days yet. Turn on Phone use in Setup › Profile & goals › Data capture.",
        extra = if (apps.isEmpty()) null else ({
            Text("MOST USED · 28 DAYS", style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.padding(top = 8.dp))
            apps.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text((a.label ?: a.pkg).uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${a.category.uppercase()} · ${minutesWords(a.minutes / 28)} A DAY".uppercase(), style = FTType.MonoCaption, color = FT.TextSecondary)
                }
            }
        }),
    )
}

@Composable
private fun PracticesCard(activities: List<RoutineActivity>, logs: List<RoutineLog>, today: LocalDate, onOpen: (RoutineActivity) -> Unit) {
    FTCard(
        title = "PRACTICES",
        info = "Each practice of the last 28 days: the scheduled days it was done, the time logged and its streaks. Tap one " +
            "for its 12-week calendar. Practices are added and changed on Lab › ROUTINE.",
    ) {
        val list = practicesInWindow(activities, logs, today)
        if (list.isEmpty()) {
            Text("No practices yet. Add one on Lab › ROUTINE.", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        list.forEach { a ->
            val kept = adherence(a, logs, today)
            val minutes = logs.filter { it.activityId == a.id && it.status == LogStatus.DONE && it.date.isAfter(today.minusDays(28)) }.sumOf { it.length }
            Row(
                Modifier.fillMaxWidth().ftClickable(onClickLabel = "Open its calendar") { onOpen(a) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.name.uppercase() + if (a.archived) " · REMOVED" else "", style = FTType.LabelCaps, color = FT.TextPrimary)
                    Text(
                        listOfNotNull(
                            scheduleWords(a.days).uppercase(),
                            "${minutesWords(minutes).uppercase()} IN 28 DAYS",
                            kept.streak.takeIf { it > 1 }?.let { "$it IN A ROW" },
                        ).joinToString(" · "),
                        style = FTType.MonoCaption, color = FT.TextSecondary,
                    )
                }
                Text(
                    if (kept.scheduled > 0) "${kept.done}/${kept.scheduled}" else "${logs.count { it.activityId == a.id && it.status == LogStatus.DONE && it.date.isAfter(today.minusDays(28)) }}×",
                    style = FTType.Telemetry, color = FT.Metric.TimeLogged,
                )
                FTChevron(tint = FT.TextSecondary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PracticeCalendarSheet(a: RoutineActivity, logs: List<RoutineLog>, today: LocalDate, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cal = practiceCalendar(a, logs, today, weeks = 12)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = FT.SheetShape, containerColor = FT.Surface, contentColor = FT.TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(a.name.uppercase(), style = FTType.SectionTitle, color = FT.TextPrimary)
            Text("${scheduleWords(a.days)}${a.time?.let { " · $it" } ?: ""}${a.targetMin?.let { " · $it min" } ?: ""}", style = FTType.BodySmall, color = FT.TextSecondary)
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(48.dp))
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Text(it, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.weight(1f))
                }
            }
            cal.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(week.first().first.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)).uppercase(), style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.width(48.dp))
                    week.forEach { (_, state) ->
                        Box(Modifier.weight(1f).padding(2.dp).height(18.dp).let { m ->
                            when (state) {
                                CalendarState.DONE -> m.background(FT.Ok, RoundedCornerShape(3.dp))
                                CalendarState.SKIPPED -> m.background(FT.Warn.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                                CalendarState.MISSED -> m.border(FT.BorderWidth, FT.Bad, RoundedCornerShape(3.dp))
                                CalendarState.OFF -> m.background(FT.GlassBorder.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                                CalendarState.AHEAD -> m
                            }
                        })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend(FT.Ok, "DONE"); Legend(FT.Warn, "SKIPPED"); Legend(FT.Bad, "MISSED"); Legend(FT.GlassBorder, "NOT SCHEDULED")
            }
            Text("KEPT, 4 WEEKS AT A TIME", style = FTType.LabelCaps, color = FT.TextMuted)
            keptByFourWeeks(a, logs, today).forEach { (end, k) ->
                Row(Modifier.fillMaxWidth()) {
                    Text("TO ${end.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)).uppercase()}", style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                    Text(if (k.scheduled > 0) "${k.done} OF ${k.scheduled} DAYS" else "—", style = FTType.MonoCaption, color = FT.TextPrimary)
                }
            }
            val now = adherence(a, logs, today)
            Text("STREAK ${now.streak} · BEST ${bestStreak(a, logs, today)} (SCHEDULED DAYS IN A ROW)", style = FTType.LabelCaps, color = FT.TextPrimary)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(c, RoundedCornerShape(2.dp)))
        Text(label, style = FTType.MonoCaption, color = FT.TextSecondary)
    }
}

@Composable
private fun ComparisonCard(lines: List<DailyLine>, through: LocalDate) {
    FTCard(
        title = "THE LAST 4 WEEKS AGAINST THE 4 BEFORE",
        info = "Each line's daily average over the last 28 days beside the 28 before, where both have at least 7 recorded " +
            "days. What changed, not why: what goes with what is for the evidence board.",
    ) {
        val changes = lines.mapNotNull { l -> compareWindows(l, through)?.let { l to it } }
        if (changes.isEmpty()) {
            Text("Needs 7 recorded days in each of the two 28-day windows; it fills in as history builds up.", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTCard
        }
        changes.sortedByDescending { (_, c) -> abs(c.relative ?: 0.0) }.forEach { (l, c) ->
            val f = formatOf(l.key)
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l.label.uppercase(), style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                Column(horizontalAlignment = Alignment.End) {
                    Text(f.format(c.recent), style = FTType.Telemetry, color = lineColor(l.key))
                    Text(changeWords(c, f.format, sameWithin = if (f.unit == "min") 1.0 else 0.05), style = FTType.MonoCaption, color = FT.TextSecondary)
                }
            }
        }
    }
}
