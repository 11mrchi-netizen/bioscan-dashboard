package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.training.definition.ConditioningProtocolDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.generate.CondSlot
import com.bioscan.fieldterminal.domain.training.generate.WeekKind
import com.bioscan.fieldterminal.domain.training.generate.countedWeeks
import com.bioscan.fieldterminal.domain.training.generate.text
import com.bioscan.fieldterminal.domain.training.generate.weeksForBlocks
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

// DAV-345. The steps of the build path: pick a strength template, pick a conditioning template,
// decide how they share the week, then which weeks are deloads. Every choice is a suggestion the
// lifter can change; warnings explain, they never block.

private fun DayOfWeek.label() = getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()

private fun budgetSummary(p: ConditioningProtocolDef): String {
    val b = p.budget ?: return p.protocolType.replace('_', ' ')
    return listOfNotNull(
        b.lowIntensityMinutesPerWeek?.let { "LIC ${it.text()} min/week" },
        b.highIntensityPerWeek?.let { "HIC ${it.text()}/week" },
        b.highIntensityEveryNWeeks?.let { "HIC every $it weeks" },
        b.sessionsPerWeek?.let { "${it.text()} sessions/week" },
        b.minConditioningSessionsPerWeek?.let { "at least $it sessions/week" },
        b.sessionMinMinutes?.let { "sessions of $it+ min" },
    ).joinToString(" · ")
}

// ---------------- Strength template ----------------
@Composable
internal fun StrengthStep(state: TrainingPlannerState) {
    val modules = state.catalog?.definitions.orEmpty().filterIsInstance<StrengthModuleDef>()
    var method by remember { mutableStateOf("all") }
    Label("NAME")
    FieldTextField(state.name, { state.name = it }, "Block name")
    Label("STRENGTH TEMPLATE")
    Body("Pick the lifting program for this block. You choose how it shares the week with conditioning next.", muted = true)
    val methods = listOf("all") + modules.map { methodologyOf(it.key) }.distinct().sorted()
    Chips(methods, setOf(method), { methodologyLabels[it] ?: it.uppercase() }) { method = it }
    modules.filter { method == "all" || methodologyOf(it.key) == method }
        .sortedWith(compareBy({ methodologyOf(it.key) }, { it.family }, { it.title }))
        .forEach { m ->
            val selected = m.key == state.strengthKey
            ProgramCard(
                m.title,
                "${methodologyLabels[methodOf(m)] ?: ""} · ${m.sessionsPerWeek} days/week · ${m.domain.replace('_', ' ')} · blocks of ${m.blockLengths.joinToString("/")} weeks",
                m.notes.firstOrNull { !it.startsWith("Weighted") && !it.startsWith("Test 1RMs") },
                selected,
            ) { state.pickStrength(m.key) }
            if (selected) ModuleOptions(state, m)
        }
}

private fun methodOf(m: StrengthModuleDef) = methodologyOf(m.key)

// ---------------- Conditioning template ----------------
@Composable
internal fun ProtocolStep(state: TrainingPlannerState) {
    val protocols = state.catalog?.definitions.orEmpty().filterIsInstance<ConditioningProtocolDef>()
    val strength = state.strengthModule
    fun pairs(p: ConditioningProtocolDef) = strength != null && (p.key in strength.compatibleConditioning || strength.key in p.pairsWellWith)
    Label("CONDITIONING TEMPLATE")
    Body("The protocol sets the weekly budget of low and high intensity work. Pick none for a strength-only block.", muted = true)
    ProgramCard("None", "strength only", null, state.protocolKey == null) { state.pickProtocol(null) }
    protocols.sortedWith(compareByDescending<ConditioningProtocolDef> { pairs(it) }.thenBy { methodologyOf(it.key) }.thenBy { it.title }).forEach { p ->
        ProgramCard(
            p.title + if (pairs(p)) "  ·  PAIRS WELL" else "",
            "${methodologyLabels[methodologyOf(p.key)] ?: ""} · ${budgetSummary(p)}",
            p.notes.firstOrNull(),
            p.key == state.protocolKey,
        ) { state.pickProtocol(p.key) }
    }
}

// ---------------- The week ----------------
@Composable
internal fun LayoutStep(state: TrainingPlannerState) {
    val strength = state.strengthModule
    val protocol = state.protocol
    Label("START DATE")
    DateField("Start", state.startDate) { state.startDate = it; state.resetLayout() }
    Label("THE WEEK")
    Body("Tap a day to place a strength session or a conditioning session on it. A day with both is a two-a-day.", muted = true)
    val start = state.startDate.dayOfWeek
    val ordered = state.layout.sortedBy { (it.weekday.value - start.value + 7) % 7 }
    ordered.forEach { d ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(d.weekday.label(), style = FTType.Label, color = FT.TextSecondary, modifier = Modifier.width(40.dp))
            if (strength != null) {
                val assigned = state.layout.mapNotNull { it.strengthSession }.toSet()
                val text = d.strengthSession?.let { strength.sessions.getOrNull(it)?.label ?: "S${it + 1}" }
                DayChip(text ?: "STRENGTH", d.strengthSession != null, FT.DomainTraining, Modifier.weight(1f)) {
                    val options = listOf<Int?>(null) + strength.sessions.indices.filter { it !in assigned || it == d.strengthSession }
                    val next = options[(options.indexOf(d.strengthSession) + 1) % options.size]
                    state.setDay(d.weekday) { it.copy(strengthSession = next) }
                }
            }
            if (protocol != null) {
                val kinds = listOf<String?>(null, "lic", "hic", "wc")
                DayChip(d.conditioning?.kind?.uppercase() ?: "CONDITIONING", d.conditioning != null, FT.DomainHeart, Modifier.weight(1f)) {
                    val next = kinds[(kinds.indexOf(d.conditioning?.kind) + 1) % kinds.size]
                    state.setDay(d.weekday) { it.copy(conditioning = next?.let { k -> CondSlot(k) }) }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val l = state.layout
    FTCard(title = "THIS WEEK") {
        FTMetricRow("Strength days", "${l.count { it.strengthSession != null }}" + (strength?.let { " of ${it.sessionsPerWeek}" } ?: ""))
        FTMetricRow("Conditioning days", "${l.count { it.conditioning != null }} (${l.count { it.conditioning?.kind == "lic" }} LIC, ${l.count { it.conditioning?.kind == "hic" }} HIC, ${l.count { it.conditioning?.kind == "wc" }} WC)")
        FTMetricRow("Rest days", "${l.count { it.isRest }}")
        protocol?.let { Text(budgetSummary(it), style = FTType.Caption, color = FT.TextMuted) }
        state.layoutWarnings.forEach { Text("! $it", style = FTType.BodySmall, color = FT.Warning) }
        if (state.layoutWarnings.isEmpty()) Text("No clashes found.", style = FTType.BodySmall, color = FT.TextSecondary)
        Spacer(Modifier.height(4.dp))
        AmberButton("RESET TO SUGGESTION") { state.resetLayout() }
    }
}

@Composable
private fun DayChip(label: String, active: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier.defaultMinSize(minHeight = 44.dp)
            .border(FT.BorderWidth, if (active) accent else FT.GlassBorder, shape)
            .background(if (active) accent.copy(alpha = 0.14f) else Color.Transparent, shape)
            .clickable { onClick() }.padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label.take(22), style = FTType.Label, color = if (active) FT.TextPrimary else FT.TextMuted) }
}

// ---------------- Length and deloads ----------------
@Composable
internal fun TimelineStep(state: TrainingPlannerState) {
    val len = state.blockLength()
    val t = state.timeline
    Label("LENGTH")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AmberButton("-", enabled = state.weeks > 1) { state.changeWeeks(state.weeks - 1) }
        Text("${state.weeks} WEEKS", style = FTType.MetricMedium, color = FT.TextPrimary)
        AmberButton("+", enabled = state.weeks < 52) { state.changeWeeks(state.weeks + 1) }
    }
    if (state.strengthModule != null) {
        Label("OR BY BLOCKS OF $len WEEKS")
        Chips(listOf("1", "2", "3", "4"), emptySet(), { "$it BLOCK" + if (it != "1") "S" else "" }) { n ->
            state.changeWeeks(weeksForBlocks(n.toInt(), len, startWithTest = state.startWithTest)); state.resetTimeline()
        }
        Label("MAX TEST")
        Chips(listOf("yes", "no"), setOf(if (state.startWithTest) "yes" else "no"), { if (it == "yes") "BEGIN WITH A TEST WEEK" else "NO TEST WEEK" }) { state.startWithTest = it == "yes"; state.resetTimeline() }
    }
    Label("THE WEEKS")
    Body("Tap a week to change it: normal, deload (does not count toward the block, pauses the strength cycle, no hard conditioning) or a max test week.", muted = true)
    t.chunked(6).forEachIndexed { row, kinds ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEachIndexed { j, k ->
                val i = row * 6 + j
                val accent = when (k) { WeekKind.Normal -> FT.DomainTraining; WeekKind.Deload -> FT.Warning; WeekKind.Test -> FT.Info }
                DayChip("${i + 1} ${k.label.take(1)}", k != WeekKind.Normal, accent, Modifier.weight(1f)) { state.toggleWeek(i) }
            }
            repeat(6 - kinds.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Spacer(Modifier.height(12.dp))
    FTCard(title = "BLOCK") {
        FTMetricRow("Calendar weeks", "${t.size}")
        FTMetricRow("Counted weeks", "${countedWeeks(t)}")
        FTMetricRow("Deload weeks", "${t.count { it == WeekKind.Deload }}")
        FTMetricRow("Test weeks", "${t.count { it == WeekKind.Test }}")
        Text("Blocks of $len weeks; the strength template restarts after each block and the maxes can step up.", style = FTType.Caption, color = FT.TextMuted)
    }
}
