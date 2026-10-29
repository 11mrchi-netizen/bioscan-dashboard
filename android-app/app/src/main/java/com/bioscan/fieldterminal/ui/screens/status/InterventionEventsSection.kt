package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.InterventionEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.DrawRow
import com.bioscan.fieldterminal.data.model.EpisodicIntervention
import com.bioscan.fieldterminal.data.model.EventEvidenceRow
import com.bioscan.fieldterminal.data.model.EventRow
import com.bioscan.fieldterminal.data.model.ExpectationRow
import com.bioscan.fieldterminal.data.model.TrackedOutcomeRow
import com.bioscan.fieldterminal.domain.DrawState
import com.bioscan.fieldterminal.domain.ExpectedDirection
import com.bioscan.fieldterminal.domain.drawCoin
import com.bioscan.fieldterminal.domain.drawState
import com.bioscan.fieldterminal.domain.drawText
import com.bioscan.fieldterminal.domain.ladderHint
import com.bioscan.fieldterminal.domain.ladderLabel
import com.bioscan.fieldterminal.domain.promotionTarget
import com.bioscan.fieldterminal.domain.windowText
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// Lab › N=1: interventions you repeat (nasal strips, a lactose meal, a cold shower), the events logged for them, how each
// felt, and what the nightly analysis makes of them so far. The label climbs with the number of events that have data
// (anecdote → early signal → emerging → ready to test properly) and the wording stays "consistent with", never "caused":
// one try is never an answer. Loads its own data, like HealthN1Section.

private const val EVENTS_INFO =
    "Each event is read in the window you declared for its outcome and compared with the same window at the same clock time " +
        "on days without it. Nights with another intervention in the window, a flagged confounder or alcohol are left out. " +
        "Secondary and added-later outcomes are exploratory. Ready to test properly means a randomised experiment is worth setting up."

@Composable
fun InterventionEventsSection() {
    val repo = remember { InterventionEventsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    val series = rememberOutcomeSeries()
    var key by remember { mutableIntStateOf(0) }
    var interventions by remember { mutableStateOf<List<EpisodicIntervention>>(emptyList()) }
    var expectations by remember { mutableStateOf<List<ExpectationRow>>(emptyList()) }
    var events by remember { mutableStateOf<List<EventRow>>(emptyList()) }
    var evidence by remember { mutableStateOf<Map<String, EventEvidenceRow>>(emptyMap()) }
    var tracked by remember { mutableStateOf<List<TrackedOutcomeRow>>(emptyList()) }
    // intervention ids that already have a registered experiment
    var tested by remember { mutableStateOf<Set<String>>(emptySet()) }
    var promoting by remember { mutableStateOf<Pair<EpisodicIntervention, String>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var creatingTracked by remember { mutableStateOf(false) }
    var loggingValue by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf<Pair<EpisodicIntervention, String?>?>(null) }
    var draws by remember { mutableStateOf<List<DrawRow>>(emptyList()) }
    var rating by remember { mutableStateOf<Pair<EventRow, String>?>(null) }
    var parking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(key) {
        interventions = repo.loadInterventions()
        expectations = repo.loadExpectations()
        events = repo.loadRecentEvents()
        evidence = repo.loadEvidence()
        draws = repo.loadDraws()
        tracked = repo.loadTrackedOutcomes()
        tested = runCatching { ExperimentsRepository(SupabaseClientProvider.client).loadExperiments().mapNotNull { it.interventionId }.toSet() }.getOrDefault(emptySet())
    }

    val seriesLabels = series.associate { it.id to it.label }
    val trackedNames = tracked.associate { it.id to it.name }
    fun outcomeName(e: ExpectationRow) = if (e.outcomeKind == "tracked") trackedNames[e.outcomeKey] ?: "your outcome" else seriesLabels[e.outcomeKey] ?: e.outcomeKey

    FTCard(title = "REPEATABLE INTERVENTIONS", info = EVENTS_INFO) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (interventions.isEmpty()) {
                Text(
                    "Nothing here yet. Add something you try now and then, say what you expect it to change, then log it each time.",
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
            }
            interventions.forEach { iv ->
                val mine = events.filter { it.interventionId == iv.id }
                val unrated = mine.firstOrNull { it.feeling == null }
                val result = evidence[iv.id]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(iv.name, style = FTType.Label, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                        result?.let { LadderChip(ladderLabel(it.payload.ladder)) }
                    }
                    expectations.filter { it.interventionId == iv.id }.sortedBy { if (it.role == "primary") 0 else 1 }.forEach { e ->
                        val arrow = ExpectedDirection.entries.firstOrNull { it.key == e.direction }?.arrow ?: ""
                        val tag = if (e.exploratory) " · exploratory" else if (e.role == "watch") " · watching" else ""
                        Text("$arrow ${outcomeName(e)} (${windowText(e.windowStartH, e.windowEndH)})$tag", style = FTType.Caption, color = FT.TextMuted)
                    }
                    Text(
                        listOfNotNull(
                            "${mine.size} logged${if (mine.size >= 60) "+" else ""}",
                            mine.firstOrNull()?.localDate?.let { "last $it" },
                            result?.payload?.feeling?.takeIf { it.n > 0 }?.let { "felt ${it.mean?.let { m -> "%.1f".format(m) } ?: "–"} on average (${it.n} rated)" },
                        ).joinToString(" · "),
                        style = FTType.Caption, color = FT.TextMuted,
                    )
                    if (iv.drawEnabled) {
                        val today = LocalDate.now().toString()
                        val todays = draws.firstOrNull { it.interventionId == iv.id && it.localDate == today }
                        val state = drawState(todays?.result, todays?.eventId)
                        Text(drawText(state), style = FTType.Caption, color = FT.TextSecondary)
                        result?.payload?.draws?.takeIf { it.nDo + it.nSkip > 0 }?.let {
                            Text(
                                "${it.nDo} do and ${it.nSkip} skip draws; ${it.kept} done as drawn" +
                                    (if (it.missed > 0) ", ${it.missed} drawn but not logged" else "") + (if (it.broken > 0) ", ${it.broken} done on a skip day" else ""),
                                style = FTType.Caption, color = FT.TextMuted,
                            )
                        }
                    }
                    if (result == null) {
                        Text("Not analysed yet. The nightly analysis publishes this once events have data.", style = FTType.BodySmall, color = FT.TextSecondary)
                    } else {
                        val primary = result.payload.expectations.firstOrNull { it.role == "primary" && !it.exploratory } ?: result.payload.expectations.firstOrNull()
                        Text(ladderHint(result.payload.ladder, primary?.nWithData ?: 0), style = FTType.BodySmall, color = FT.TextSecondary)
                        result.payload.expectations.forEach { x ->
                            Text(x.summary, style = FTType.BodySmall, color = FT.TextPrimary)
                            x.randomised?.takeIf { it.diff != null }?.let {
                                Text(
                                    "Drawn days only (${it.nDo} do, ${it.nSkip} skip): ${"%+.2f".format(it.diff)}${x.unit?.let { u -> " $u" } ?: ""}" +
                                        if (it.lo != null && it.hi != null) " (${"%+.2f".format(it.lo)} to ${"%+.2f".format(it.hi)})" else "",
                                    style = FTType.Caption, color = FT.TextSecondary,
                                )
                            }
                        }
                        result.payload.notes.forEach { Text(it, style = FTType.Caption, color = FT.TextMuted) }
                        if (result.payload.ladder == "ready") {
                            val main = expectations.firstOrNull { it.interventionId == iv.id && it.role == "primary" && !it.exploratory }
                            val target = main?.let { promotionTarget(it.outcomeKind, it.outcomeKey, outcomeName(it)) }
                            when {
                                iv.id in tested -> Text("A randomised experiment is registered for this; see Daily tests and Experiments.", style = FTType.BodySmall, color = FT.TextSecondary)
                                target?.metric != null -> FTButton(label = "TEST PROPERLY") { promoting = iv to target.metric }
                                target?.reason != null -> Text(target.reason, style = FTType.BodySmall, color = FT.TextSecondary)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (iv.drawEnabled) {
                            val today = LocalDate.now().toString()
                            val todays = draws.firstOrNull { it.interventionId == iv.id && it.localDate == today }
                            when (drawState(todays?.result, todays?.eventId)) {
                                DrawState.NOT_DRAWN -> FTButton(label = "DRAW TODAY") {
                                    error = null
                                    scope.launchSafe(onError = { e -> error = e.message ?: "Could not draw" }) {
                                        // stored first; a second tap (or a second phone) hits the one-per-day rule and just reloads
                                        runCatching { repo.saveDraw(iv.id, today, drawCoin()) }
                                        draws = repo.loadDraws()
                                    }
                                }
                                DrawState.DO_PENDING -> FTButton(label = "LOG THE DRAWN ONE") { logging = iv to todays?.id }
                                else -> {}
                            }
                        }
                        // chosen by you, not the coin: always possible, counted as such (and as a broken draw on a skip day)
                        FTButton(label = if (iv.drawEnabled) "LOG ANYWAY" else "LOG IT") { logging = iv to null }
                        if (unrated != null) FTButton(label = "HOW IT FELT") { rating = unrated to iv.name }
                        FTButton(label = "PARK…") { parking = iv.id }
                    }
                    if (parking == iv.id) {
                        Text("Park this? It leaves the list; the events and what was found stay.", style = FTType.Caption, color = FT.TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FTButton(label = "PARK") {
                                parking = null
                                error = null
                                scope.launchSafe(onError = { e -> error = e.message ?: "Could not park" }) { repo.park(iv.id); key++ }
                            }
                            FTButton(label = "KEEP") { parking = null }
                        }
                    }
                }
            }
            error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FTButton(label = "NEW") { creating = true }
                FTButton(label = "NEW OUTCOME") { creatingTracked = true }
                if (tracked.isNotEmpty()) FTButton(label = "LOG VALUE") { loggingValue = true }
            }
        }
    }

    if (creating) InterventionSheet(tracked, onDismiss = { creating = false }, onCreated = { creating = false; key++ })
    if (creatingTracked) TrackedOutcomeSheet(onDismiss = { creatingTracked = false }, onCreated = { creatingTracked = false; key++ })
    if (loggingValue) TrackedValueSheet(tracked, onDismiss = { loggingValue = false }, onLogged = { loggingValue = false; key++ })
    logging?.let { (iv, drawId) -> LogEventSheet(iv, expectations, onDismiss = { logging = null }, onLogged = { logging = null; key++ }, drawId = drawId) }
    promoting?.let { (iv, metric) -> EventExperimentSheet(iv.id, iv.name, metric, onDismiss = { promoting = null }, onRegistered = { promoting = null; key++ }) }
    rating?.let { (event, name) -> RateEventSheet(event.id, name, onDismiss = { rating = null }, onRated = { rating = null; key++ }) }
}

// The ladder rung, in words; the words carry the meaning, not a colour.
@Composable
private fun LadderChip(text: String) {
    val strong = text.startsWith("READY") || text.startsWith("EMERGING")
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Text(
        text,
        style = FTType.LabelCaps,
        color = if (strong) FT.TextPrimary else FT.TextSecondary,
        modifier = Modifier.border(FT.BorderWidth, if (strong) FT.TextPrimary else FT.TextMuted, shape).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
