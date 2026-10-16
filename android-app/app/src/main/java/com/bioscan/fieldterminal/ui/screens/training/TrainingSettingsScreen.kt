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
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.domain.ZoneMethod
import com.bioscan.fieldterminal.domain.zoneMethodExplanation
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.TimeField
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalTime

private val PLATE_CHOICES = listOf(0.5, 1.25, 2.5, 5.0, 10.0, 15.0, 20.0, 25.0)

private fun plateText(p: Double) = if (p == Math.floor(p)) p.toInt().toString() else p.toString()
private fun time(s: String) = runCatching { LocalTime.parse(s) }.getOrDefault(LocalTime.of(7, 0))

// DAV-345. Setup › Training setup: what the lifter owns (plates) and the default session times.
// UX review P5 / P8: the program library and its import moved into PLAN A NEW BLOCK (TrainingPlannerScreen's first step),
// so Setup keeps only true settings and a block is started, seen and edited in Training › Plan alone.
@Composable
fun TrainingSettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { TrainingPlannerState(TrainingProgramRepository(SupabaseClientProvider.client), scope) }
    LaunchedEffect(Unit) { state.load() }

    Column(Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = onBack, title = "TRAINING SETUP", subtitle = "EQUIPMENT · SESSION TIMES", accent = FT.DomainTraining)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FT.DomainTraining) }
            } else {
                state.error?.let { Text(it, color = FT.Critical, style = FTType.BodySmall) }
                EquipmentCard(state)
                ScheduleCard(state)
                HeartRateZonesCard(state)
                Spacer(Modifier.height(24.dp))
            }
        }
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
        FTButton("SAVE EQUIPMENT", enabled = plates.isNotEmpty()) { state.saveSettings(state.settings.copy(platesKg = plates.sorted(), weightedPercentBase = base)) }
        state.settingsMessage?.let { Body(it, muted = true) }
    }
}

// The default for every session's heart-rate chart (domain/HeartRateZones.kt); a session can still be switched there.
@Composable
private fun HeartRateZonesCard(state: TrainingPlannerState) {
    FTCard(title = "HEART-RATE ZONES") {
        Body("Where a session's zone edges sit. A session's heart-rate chart opens on this and can be switched there.", muted = true)
        Chips(ZoneMethod.entries.map { it.key }, setOf(state.zoneMethod.key), { ZoneMethod.of(it).label }) { state.saveZoneMethod(ZoneMethod.of(it)) }
        Spacer(Modifier.height(4.dp))
        Body(zoneMethodExplanation(state.zoneMethod), muted = true)
        state.zoneMessage?.let { Body(it, muted = true) }
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
        FTButton("SAVE TIMES", enabled = firstMin.toIntOrNull() != null && secondMin.toIntOrNull() != null) {
            state.saveSettings(
                s.copy(
                    defaultStartTime = first.toString() + ":00", defaultDurationMin = firstMin.toInt(),
                    secondStartTime = second.toString() + ":00", secondDurationMin = secondMin.toInt(),
                )
            )
        }
    }
}
