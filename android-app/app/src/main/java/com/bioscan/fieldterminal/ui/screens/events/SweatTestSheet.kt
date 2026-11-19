package com.bioscan.fieldterminal.ui.screens.events

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SweatTestsRepository
import com.bioscan.fieldterminal.domain.events.formatHoursMinutes
import com.bioscan.fieldterminal.domain.events.parseHoursMinutes
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.SweatTest
import com.bioscan.fieldterminal.domain.fuel.bodyMassLossPct
import com.bioscan.fieldterminal.domain.fuel.sweatRateMlPerHour
import com.bioscan.fieldterminal.domain.fuel.sweatTestProblems
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTDropdown
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// A sweat test (domain/fuel/SweatProfile.kt): weigh in before and after a session, note what was drunk. Hydration plans
// are built from these; without any they use a wide estimate.

private val SESSION_TYPES = listOf("run" to "RUN", "ride" to "RIDE", "hike" to "HIKE", "swim" to "SWIM", "strength" to "STRENGTH", "other" to "OTHER")

private fun String.num(): Double? = replace(',', '.').trim().toDoubleOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SweatTestSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    initialType: String = "run",
    initialDurationMin: Double? = null,
    initialDate: LocalDate = LocalDate.now(),
    sessionId: Long? = null,
) {
    val repo = remember { SweatTestsRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(initialDate) }
    var type by remember { mutableStateOf(initialType) }
    var intensity by remember { mutableStateOf(Intensity.MODERATE) }
    var duration by remember { mutableStateOf(initialDurationMin?.let { formatHoursMinutes(it) }.orEmpty()) }
    var before by remember { mutableStateOf("") }
    var after by remember { mutableStateOf("") }
    var fluid by remember { mutableStateOf("") }
    var urine by remember { mutableStateOf("") }
    var temp by remember { mutableStateOf("") }
    var humidity by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val test = run {
        val d = parseHoursMinutes(duration)
        val b = before.num()
        val a = after.num()
        if (d == null || b == null || a == null) null
        else SweatTest(date, type, intensity, d, b, a, fluid.num() ?: 0.0, urine.num() ?: 0.0, temp.num(), humidity.num())
    }
    val problems = test?.let(::sweatTestProblems).orEmpty()
    val flow = rememberFlowState()
    // Two pages, split by when the numbers exist (Logging Flows Redesign Plan): THE SESSION (what, how long, the air), then
    // WEIGHTS AND FLUIDS after it, with the sweat rate shown live under them.
    val pages = listOf(
        FlowPage("THE SESSION", ready = parseHoursMinutes(duration) != null),
        FlowPage("WEIGHTS AND FLUIDS"),
    )
    FTFlow(
        title = "SWEAT TEST", pages = pages, state = flow, onClose = onDismiss,
        canSave = test != null && problems.isEmpty(), saving = saving, wash = FT.DomainTraining,
        error = error ?: problems.firstOrNull()?.takeIf { flow.page >= 1 },
        onSave = {
            saving = true
            scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                repo.add(test!!, sessionId, notes)
                onSaved()
            }
        },
    ) { page ->
        when (page) {
            0 -> {
                Text(
                    "Weigh yourself without clothes, dried off and after the toilet, just before and just after a session of at least " +
                        "30 minutes. A few tests in different weather make hydration plans yours.",
                    style = FTType.BodySmall, color = FT.TextSecondary,
                )
                DateField("DATE", date) { date = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Label("SESSION"); FTDropdown(SESSION_TYPES, type, "Type") { type = it } }
                    Column(Modifier.weight(1f)) {
                        Label("EFFORT")
                        FTDropdown(Intensity.entries.map { it.name to it.label }, intensity.name, "Effort") { k -> intensity = Intensity.valueOf(k) }
                    }
                }
                Column { Label("DURATION"); FieldTextField(duration, { duration = it }, "h:mm") }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Label("AIR °C"); FieldTextField(temp, { temp = it }, "e.g. 18", keyboardType = KeyboardType.Decimal) }
                    Column(Modifier.weight(1f)) { Label("HUMIDITY %"); FieldTextField(humidity, { humidity = it }, "e.g. 60", keyboardType = KeyboardType.Number) }
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Label("WEIGHT BEFORE KG"); FieldTextField(before, { before = it }, "e.g. 70.4", keyboardType = KeyboardType.Decimal) }
                    Column(Modifier.weight(1f)) { Label("WEIGHT AFTER KG"); FieldTextField(after, { after = it }, "e.g. 69.6", keyboardType = KeyboardType.Decimal) }
                }
                Text("What went in and out between the two weigh-ins:", style = FTType.Caption, color = FT.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Label("DRANK ML"); FieldTextField(fluid, { fluid = it }, "0", keyboardType = KeyboardType.Number) }
                    Column(Modifier.weight(1f)) { Label("URINE ML (IF ANY)"); FieldTextField(urine, { urine = it }, "0", keyboardType = KeyboardType.Number) }
                }
                if (test != null && problems.isEmpty()) {
                    val rate = sweatRateMlPerHour(test)!!
                    Text("SWEAT RATE %.2f L/H".format(rate / 1000), style = FTType.LabelCaps, color = FT.TextPrimary)
                    val loss = bodyMassLossPct(test)
                    Text(
                        "You lost %.1f %% of your weight%s.".format(loss, if (loss > 2) ": above 2 %, where performance starts to drop; drink more next time" else ""),
                        style = FTType.Caption, color = FT.TextSecondary,
                    )
                }
            }
        }
        if (page == 1) Column { Label("NOTES"); FieldTextField(notes, { notes = it }, "Kit, sun, wind", singleLine = false) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
}
