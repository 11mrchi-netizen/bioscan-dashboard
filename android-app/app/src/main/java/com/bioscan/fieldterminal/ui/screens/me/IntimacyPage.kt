package com.bioscan.fieldterminal.ui.screens.me

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.IntimacyRepository
import com.bioscan.fieldterminal.data.PeopleRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.NewStiTestRow
import com.bioscan.fieldterminal.data.model.StiTestRow
import com.bioscan.fieldterminal.domain.PartnerSort
import com.bioscan.fieldterminal.domain.PartnerSummary
import com.bioscan.fieldterminal.domain.STI_LOOKBACK_DAYS
import com.bioscan.fieldterminal.domain.StiVocab
import com.bioscan.fieldterminal.domain.barrier
import com.bioscan.fieldterminal.domain.browsePartners
import com.bioscan.fieldterminal.domain.day
import com.bioscan.fieldterminal.domain.intimacyStats
import com.bioscan.fieldterminal.domain.partnerSummaries
import com.bioscan.fieldterminal.domain.partnersBefore
import com.bioscan.fieldterminal.domain.stiStatus
import com.bioscan.fieldterminal.domain.tally
import com.bioscan.fieldterminal.ui.components.ActivityHeatmap
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.DayActivity
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.FormLabel
import com.bioscan.fieldterminal.ui.screens.MultiChipSection
import com.bioscan.fieldterminal.ui.screens.NewPartnerSheet
import com.bioscan.fieldterminal.ui.screens.SaveButton
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.screens.detail.Chip
import com.bioscan.fieldterminal.ui.screens.detail.EmptyLine
import com.bioscan.fieldterminal.ui.screens.detail.Fmt
import com.bioscan.fieldterminal.ui.screens.detail.LinkRow
import com.bioscan.fieldterminal.ui.screens.detail.Loading
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.screens.detail.ShareBars
import com.bioscan.fieldterminal.ui.screens.detail.StackedBars
import com.bioscan.fieldterminal.ui.screens.detail.StatTiles
import com.bioscan.fieldterminal.ui.screens.detail.shortDate
import com.bioscan.fieldterminal.ui.screens.status.SexualHealthCorrelationsCard
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// ME › INTIMACY. One tab, three views: the overview (stats, trends, what goes with what, STI tests), the partners
// browser, and one partner's profile. The views switch inside the tab (system back steps out of them) so ME's
// sub-tab row stays where it is. Self-quantification only: counts and your own ratings, no clinical meaning implied.

private const val STATS_DAYS = 90L
private const val SELF_Q = "Self-quantification only: counts and your own ratings, with no clinical meaning implied."
private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun ago(date: LocalDate, today: LocalDate): String = when (val d = ChronoUnit.DAYS.between(date, today)) {
    0L -> "today"
    1L -> "yesterday"
    else -> "$d days ago"
}

@Composable
fun IntimacyPage() {
    val page = remember { IntimacyPageState() }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(page.guard.attempt, reload) { page.load() }

    // "" = overview, "partners" = the browser, anything else = that person's id.
    var view by rememberSaveable { mutableStateOf("") }
    BackHandler(view.isNotEmpty()) { view = if (view == "partners") "" else "partners" }

    // Keyed by the view, so each one has its own scroll position and opens at its top.
    key(view) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val encounters = page.encounters
        val people = page.people
        if (encounters == null || people == null) {
            Loading(page.guard)
            return@Column
        }
        val partners = remember(people, encounters) { partnerSummaries(people, encounters) }
        val names = remember(people) { people.associate { it.id to it.name } }
        when (view) {
            "" -> Overview(page, encounters, partners, names, onOpenPartners = { view = "partners" }, onChanged = { reload++ })
            "partners" -> PartnerBrowser(partners, onBack = { view = "" }, onOpen = { view = it.toString() })
            else -> partners.firstOrNull { it.person.id.toString() == view }
                ?.let { PartnerProfile(it, onBack = { view = "partners" }, onChanged = { reload++ }) }
                ?: run { view = "partners" }
        }
    }
    }
}

@Composable
private fun BackRow(title: String, onBack: () -> Unit, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("BACK", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.ftClickable { onBack() })
        Text(title, style = FTType.SectionTitle, color = FT.DomainUser, modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = FTType.LabelCaps, color = FT.DomainLog, modifier = Modifier.ftClickable { onAction() })
    }
}

// ---- overview ----

@Composable
private fun Overview(
    page: IntimacyPageState,
    encounters: List<LogEncounterRow>,
    partners: List<PartnerSummary>,
    names: Map<Long, String>,
    onOpenPartners: () -> Unit,
    onChanged: () -> Unit,
) {
    val today = LocalDate.now()
    val from = today.minusDays(STATS_DAYS - 1)
    val stats = remember(encounters, today) { intimacyStats(encounters, from, today) }
    val recent = remember(encounters, today) { encounters.filter { e -> e.sexual && e.day?.let { !it.isBefore(from) } == true } }

    Section("LAST $STATS_DAYS DAYS", info = SELF_Q) {
        StatTiles(
            listOf(
                Triple("ENCOUNTERS", "${stats.encounters}", "${stats.sexual} with sex"),
                Triple("PARTNERS", "${stats.partners}", "${stats.newPartners} new"),
                Triple("ROUNDS", "${stats.rounds}", "${stats.orgasms} orgasms"),
                Triple(
                    "WITH A BARRIER",
                    if (stats.withBarrier + stats.withoutBarrier == 0) "—" else "${stats.withBarrier} of ${stats.withBarrier + stats.withoutBarrier}",
                    if (stats.protectionUnknown > 0) "${stats.protectionUnknown} not recorded" else null,
                ),
                Triple("YOUR RATING", stats.avgRating?.let { "%.1f".format(it) } ?: "—", "average, out of 5"),
            ),
        )
    }

    Section("BY WEEK", info = "Active days per week, with a partner and solo. Tap a week to read it.") {
        val columns = (11 downTo 0).map { k ->
            val start = today.minusDays((k * 7 + 6).toLong())
            val end = today.minusDays((k * 7).toLong())
            val inWeek = page.activity.filter { r -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { !it.isBefore(start) && !it.isAfter(end) } == true }
            shortDate(start) to listOf(inWeek.count { it.activityType == "intercourse" }.toDouble(), inWeek.count { it.activityType == "masturbation" }.toDouble())
        }
        StackedBars(columns, listOf(FT.Metric.SexPartnered, FT.Metric.SexSolo), listOf("With partner", "Solo"), Fmt.int, "days", lastPartial = true)
    }

    Section("CALENDAR · 12 WEEKS", info = "Each square a day; red with a partner, blue solo, darker = more that day.") {
        val byDate = page.activity.mapNotNull { r -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { it to r } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, rows) ->
                DayActivity(
                    intercourseCount = rows.filter { it.activityType == "intercourse" }.sumOf { it.instances.size },
                    masturbationCount = rows.filter { it.activityType == "masturbation" }.sumOf { it.instances.size },
                )
            }
        ActivityHeatmap(byDate)
    }

    Section("WHAT · LAST $STATS_DAYS DAYS", info = "How many encounters each one was logged on. Entries copied from the old day rows carry none of these.") {
        val lists = listOf(
            "FORMAT" to tally(recent) { it.format },
            "ACTIVITIES" to tally(recent) { it.activities },
            "POSITIONS" to tally(recent) { it.positions },
            "TOYS" to tally(recent) { it.toys },
            "EJACULATION" to tally(recent) { it.ejaculationLocation },
        ).filter { it.second.isNotEmpty() }
        if (lists.isEmpty()) EmptyLine("Nothing tagged in this range yet.")
        lists.forEach { (label, counts) ->
            FormLabel(label)
            val top = counts.take(6)
            ShareBars(top.map { (name, n) -> Triple(name, n.toDouble(), "$n") }, top.first().second.toDouble(), FT.Category.Arousal.c500)
        }
    }

    if (page.activity.isNotEmpty()) SexualHealthCorrelationsCard(page.wellbeing, page.activity, page.wearable, page.sleep)

    StiSection(page, encounters, names, onChanged)

    val seen = partners.count { p -> p.last?.let { !it.isBefore(from) } == true }
    LinkRow("PARTNERS", "${partners.size} people · $seen seen in the last $STATS_DAYS days", onOpenPartners)
    Spacer(Modifier.height(24.dp))
}

// ---- STI tests ----

@Composable
private fun StiSection(page: IntimacyPageState, encounters: List<LogEncounterRow>, names: Map<Long, String>, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()

    Section("STI TESTS", info = "Your own record of tests and results. Nothing here says when to test or what a result means: that is for a clinician.") {
        val tests = page.tests
        when {
            page.testsError != null -> EmptyLine("Couldn't load the tests (${page.testsError}).")
            tests == null -> EmptyLine("Loading…")
            else -> {
                val status = remember(tests, encounters) { stiStatus(tests, encounters) }
                if (tests.isEmpty()) EmptyLine("No test logged yet.")
                status.lastTest?.let { FTMetricRow("Last test", "${it.format(FULL_DATE)} · ${ago(it, today)}") }
                val sinceLabel = if (status.lastTest == null) "on record" else "since then"
                FTMetricRow("Partners $sinceLabel", "${status.partnersSince}")
                FTMetricRow("Encounters without a barrier $sinceLabel", "${status.withoutBarrierSince}")
                if (status.protectionUnknownSince > 0) FTMetricRow("Protection not recorded", "${status.protectionUnknownSince}")

                status.open.forEach { t ->
                    val date = runCatching { LocalDate.parse(t.testDate) }.getOrNull()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${t.infection} · positive, not marked treated", style = FTType.RowTitle, color = FT.Bad, modifier = Modifier.weight(1f))
                            Text("MARK TREATED", style = FTType.LabelCaps, color = FT.DomainLog, modifier = Modifier.ftClickable {
                                scope.launch { runCatching { IntimacyRepository(SupabaseClientProvider.client).setStiTreated(t.id, today) }; page.loadTests() }
                            })
                        }
                        val before = date?.let { partnersBefore(it, encounters) }.orEmpty()
                        if (before.isNotEmpty()) {
                            Text(
                                "Partners in the $STI_LOOKBACK_DAYS days before: ${before.joinToString(", ") { names[it] ?: "#$it" }}",
                                style = FTType.BodySmall, color = FT.TextSecondary,
                            )
                        }
                    }
                }

                if (status.latest.isNotEmpty()) FormLabel("LATEST RESULT PER INFECTION")
                status.latest.forEach { t ->
                    val date = runCatching { LocalDate.parse(t.testDate).format(FULL_DATE) }.getOrDefault(t.testDate)
                    FTMetricRow(t.infection, "${t.result}${if (t.treatedOn != null) " · treated" else ""} · $date")
                }

                if (tests.isNotEmpty()) {
                    Text(if (showAll) "HIDE ALL TESTS" else "ALL TESTS · ${tests.size}", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.ftClickable { showAll = !showAll })
                }
                if (showAll) {
                    tests.forEach { t ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(t.testDate, t.infection, t.result, t.site, t.labName).joinToString(" · "),
                                style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f),
                            )
                            Text("DELETE", style = FTType.Micro, color = FT.Bad, modifier = Modifier.ftClickable {
                                scope.launch { runCatching { IntimacyRepository(SupabaseClientProvider.client).deleteStiTest(t.id) }; page.loadTests() }
                            })
                        }
                    }
                }
                Text("+ LOG A TEST", style = FTType.LabelCaps, color = FT.DomainLog, modifier = Modifier.ftClickable { adding = true })
            }
        }
    }

    if (adding) {
        StiTestSheet(onDismiss = { adding = false }, onSaved = { adding = false; onChanged() })
    }
}

// One sheet logs a whole panel: the infections tested, one result for all of them. A mixed panel is two saves.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StiTestSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(LocalDate.now()) }
    var infections by remember { mutableStateOf(emptySet<String>()) }
    var result by remember { mutableStateOf<String?>("negative") }
    var site by remember { mutableStateOf<String?>(null) }
    var lab by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("STI TEST", style = FTType.SectionTitle, color = FT.DomainLog)
            DateField("TEST DATE", date, { date = it })
            MultiChipSection("TESTED FOR", StiVocab.infections, infections) { infections = it }
            Column { FormLabel("RESULT"); TextChipRow(StiVocab.results, result) { result = it } }
            Column { FormLabel("SAMPLE (OPTIONAL)"); TextChipRow(StiVocab.sites, site, perRow = 3) { site = it } }
            Column { FormLabel("LAB OR CLINIC (OPTIONAL)"); FieldTextField(lab, { lab = it }, "") }
            Column { FormLabel("NOTES (OPTIONAL)"); FieldTextField(notes, { notes = it }, "", singleLine = false) }
            error?.let { Text("Couldn't save ($it).", style = FTType.Caption, color = FT.Bad) }
            val picked = result
            SaveButton(saving, infections.isNotEmpty() && picked != null) {
                if (saving || picked == null) return@SaveButton
                saving = true
                scope.launch {
                    try {
                        IntimacyRepository(SupabaseClientProvider.client).addStiTests(
                            infections.map { NewStiTestRow(date.toString(), it, picked, site, lab.trim().ifBlank { null }, notes.trim().ifBlank { null }) },
                        )
                        onSaved()
                    } catch (e: Exception) {
                        error = e.message
                        saving = false
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---- partners ----

@Composable
private fun PartnerBrowser(partners: List<PartnerSummary>, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(PartnerSort.Recent) }
    val shown = remember(partners, query, sort) { browsePartners(partners, query, sort) }
    val today = LocalDate.now()

    BackRow("PARTNERS · ${shown.size}", onBack)
    FieldTextField(query, { query = it }, "Search by name…")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PartnerSort.entries.forEach { s -> Chip(s.label, sort == s) { sort = s } }
    }
    if (shown.isEmpty()) EmptyLine("No one matches.")
    // ponytail: a plain column inside ME's scrolling page (a few hundred rows); a lazy list of its own when it gets slow.
    shown.forEach { p ->
        val person = p.person
        LinkRow(
            person.name,
            listOfNotNull(
                person.relationship,
                person.country,
                person.score?.let { "score $it" },
                if (p.encounters.isEmpty()) "no encounter logged" else "${p.encounters.size} encounter${if (p.encounters.size == 1) "" else "s"}",
                p.last?.let { "last ${ago(it, today)}" },
            ).joinToString(" · "),
        ) { onOpen(person.id) }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun PartnerProfile(p: PartnerSummary, onBack: () -> Unit, onChanged: () -> Unit) {
    val person = p.person
    var editing by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    BackRow(person.name.uppercase(), onBack, action = "EDIT", onAction = { editing = true })

    Section("PROFILE") {
        val rows = listOfNotNull(
            person.relationship?.let { "Relationship" to it },
            person.whereMet?.let { "Where met" to it },
            person.firstMetDate?.let { "First met" to it },
            person.period?.let { "Period" to it },
            person.gender?.let { "Gender" to it },
            person.ageRange?.let { "Age range" to it },
            person.country?.let { "Country" to it },
            person.bodyType?.let { "Body type" to it },
            person.availability?.let { "Availability" to it },
            person.score?.let { "Score" to "$it" },
        )
        if (rows.isEmpty()) EmptyLine("Nothing on file yet. EDIT adds it.")
        rows.forEach { (label, value) -> FTMetricRow(label, value) }
        person.activities?.takeIf { it.isNotEmpty() }?.let { FormLabel("ACTIVITIES"); Text(it.joinToString(", "), style = FTType.BodySmall, color = FT.TextSecondary) }
        person.interestedIn?.takeIf { it.isNotEmpty() }?.let { FormLabel("INTERESTED IN"); Text(it.joinToString(", "), style = FTType.BodySmall, color = FT.TextSecondary) }
        person.notes?.takeIf { it.isNotBlank() }?.let { FormLabel("NOTES"); Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
    }

    Section("WITH YOU", info = SELF_Q) {
        if (p.encounters.isEmpty()) {
            EmptyLine("No encounter logged with ${person.name}.")
        } else {
            val sexual = p.encounters.filter { it.sexual }
            FTMetricRow("Encounters", "${p.encounters.size} · ${p.sexual} with sex")
            p.first?.let { FTMetricRow("First", it.format(FULL_DATE)) }
            p.last?.let { FTMetricRow("Last", "${it.format(FULL_DATE)} · ${ago(it, today)}") }
            p.avgRating?.let { FTMetricRow("Your rating", "%.1f / 5".format(it)) }
            val known = sexual.filter { it.barrier != null }
            if (known.isNotEmpty()) FTMetricRow("With a barrier", "${known.count { it.barrier == true }} of ${known.size}")
            tally(sexual) { it.activities }.take(5).takeIf { it.isNotEmpty() }?.let { top ->
                FTMetricRow("Most often", top.joinToString(", ") { it.first })
            }
        }
    }

    if (p.encounters.isNotEmpty()) {
        Section("HISTORY") {
            p.encounters.forEach { e ->
                FTMetricRow(
                    e.day?.format(FULL_DATE) ?: e.date,
                    listOfNotNull(
                        if (e.sexual) e.format?.joinToString("/") ?: "sex" else e.encounterType ?: "meetup",
                        e.rounds?.takeIf { e.sexual }?.let { "$it round${if (it == 1) "" else "s"}" },
                        e.myRating?.let { "$it/5" },
                    ).joinToString(" · "),
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))

    if (editing) {
        NewPartnerSheet(
            initialName = person.name,
            repo = remember { PeopleRepository(SupabaseClientProvider.client) },
            onDismiss = { editing = false },
            existing = person,
            onCreated = { editing = false; onChanged() },
        )
    }
}
