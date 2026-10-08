package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.domain.training.definition.ConditioningProtocolDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.TemplateDef
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.TimeField
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.LocalTime

private val PLATE_CHOICES = listOf(0.5, 1.25, 2.5, 5.0, 10.0, 15.0, 20.0, 25.0)

private fun plateText(p: Double) = if (p == Math.floor(p)) p.toInt().toString() else p.toString()
private fun time(s: String) = runCatching { LocalTime.parse(s) }.getOrDefault(LocalTime.of(7, 0))

// DAV-345. Settings > Training programs: the program library, what the lifter owns (plates) and
// the default session times. Starting a block from a program opens the setup flow at its modules.
@Composable
fun TrainingSettingsScreen(onBack: () -> Unit, onStart: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { TrainingPlannerState(TrainingProgramRepository(SupabaseClientProvider.client), scope) }
    LaunchedEffect(Unit) { state.load() }

    Column(Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("TRAINING PROGRAMS", color = FT.TextPrimary, fontFamily = RobotoMono, fontSize = 15.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 8.dp))
            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FT.DomainTraining) }
            } else {
                state.error?.let { Text(it, color = FT.Critical, fontFamily = Inter, fontSize = 13.sp) }
                ImportCard(state)
                ProgramLibrary(state, onStart)
                EquipmentCard(state)
                ScheduleCard(state)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ProgramLibrary(state: TrainingPlannerState, onStart: (String) -> Unit) {
    val templates = state.catalog?.definitions.orEmpty().filterIsInstance<TemplateDef>()
    val strength = state.catalog?.definitions.orEmpty().count { it is StrengthModuleDef }
    val protocols = state.catalog?.definitions.orEmpty().count { it is ConditioningProtocolDef }
    if (templates.isEmpty() && strength == 0) return
    var method by remember { mutableStateOf("all") }
    val methods = listOf("all") + templates.map { methodologyOf(it.key) }.distinct().sorted()
    FTCard(title = "BUILD A BLOCK") {
        Body("Pick one of $strength strength templates and one of $protocols conditioning templates, then decide the days, the length and the deload weeks.", muted = true)
        AmberButton("BUILD A BLOCK") { onStart("build") }
    }
    FTCard(title = "PUBLISHED PROGRAMS") {
        Body("Fixed week-by-day programs where strength and conditioning are already placed. Tap START to plan one.", muted = true)
        Chips(methods, setOf(method), { methodologyLabels[it] ?: it.uppercase() }) { method = it }
        templates.filter { method == "all" || methodologyOf(it.key) == method }.sortedBy { it.title }.forEach { t ->
            ProgramRow(t.title, "${t.weeks} weeks") { onStart(t.key) }
        }
    }
}

@Composable
private fun ProgramRow(title: String, meta: String, onStart: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = FT.TextPrimary, fontFamily = Inter, fontSize = 14.sp)
            Text(meta, color = FT.TextSecondary, fontFamily = RobotoMono, fontSize = 11.sp)
        }
        AmberButton("START", onClick = onStart)
    }
}

@Composable
private fun EquipmentCard(state: TrainingPlannerState) {
    var plates by remember(state.settings) { mutableStateOf(state.settings.platesKg.toSet()) }
    var base by remember(state.settings) { mutableStateOf(state.settings.weightedPercentBase) }
    FTCard(title = "EQUIPMENT") {
        Body("Plates you own, per side. Loads round to the nearest weight these make with the bar (20 kg; trap bar 25 kg).", muted = true)
        Chips(PLATE_CHOICES.map(::plateText), plates.map(::plateText).toSet(), { "$it kg" }) { t ->
            val v = t.toDouble()
            plates = if (v in plates) plates - v else plates + v
        }
        Label("WEIGHTED PULL-UP PERCENTAGES")
        Body("Added weight follows your own logs; total load follows the books (bodyweight included).", muted = true)
        Chips(listOf("added", "total"), setOf(base), { if (it == "added") "ADDED WEIGHT" else "TOTAL LOAD" }) { base = it }
        Spacer(Modifier.height(8.dp))
        AmberButton("SAVE EQUIPMENT", enabled = plates.isNotEmpty()) { state.saveSettings(state.settings.copy(platesKg = plates.sorted(), weightedPercentBase = base)) }
        state.settingsMessage?.let { Body(it, muted = true) }
    }
}

@Composable
private fun ScheduleCard(state: TrainingPlannerState) {
    val s = state.settings
    var first by remember(s) { mutableStateOf(time(s.defaultStartTime)) }
    var firstMin by remember(s) { mutableStateOf(s.defaultDurationMin.toString()) }
    var second by remember(s) { mutableStateOf(time(s.secondStartTime)) }
    var secondMin by remember(s) { mutableStateOf(s.secondDurationMin.toString()) }
    FTCard(title = "SESSION TIMES") {
        Body("Defaults for a block's calendar slots. Each session can still be moved afterwards.", muted = true)
        Label("FIRST SESSION")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TimeField("Start", first) { first = it } }
            Box(Modifier.width(110.dp)) { FieldTextField(firstMin, { firstMin = it }, "min", keyboardType = KeyboardType.Number) }
        }
        Label("SECOND SESSION (SAME DAY)")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TimeField("Start", second) { second = it } }
            Box(Modifier.width(110.dp)) { FieldTextField(secondMin, { secondMin = it }, "min", keyboardType = KeyboardType.Number) }
        }
        Spacer(Modifier.height(8.dp))
        AmberButton("SAVE TIMES", enabled = firstMin.toIntOrNull() != null && secondMin.toIntOrNull() != null) {
            state.saveSettings(
                s.copy(
                    defaultStartTime = first.toString() + ":00", defaultDurationMin = firstMin.toInt(),
                    secondStartTime = second.toString() + ":00", secondDurationMin = secondMin.toInt(),
                )
            )
        }
    }
}
