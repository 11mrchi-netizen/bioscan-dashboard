package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import com.bioscan.fieldterminal.data.GearRulesRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.training.GEAR_LABEL
import com.bioscan.fieldterminal.domain.training.GEAR_TAGS
import com.bioscan.fieldterminal.domain.training.GearRule
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.launch
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Setup › Training setup › GEAR RULES: when you use straps, a belt and the rest, and from what weight ("straps on deadlifts from
// 140 kg"). A new set in the session editor pre-fills from these as its weight is typed; the web's PLAN › GEAR edits the same rows.
// A lift with no rule is left "not recorded" rather than logged as "no gear".
@Composable
internal fun GearRulesCard() {
    val repo = remember { GearRulesRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<GearRule>>(emptyList()) }
    var lift by remember { mutableStateOf("") }
    var gear by remember { mutableStateOf("straps") }
    var from by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() { rules = repo.load() }
    LaunchedEffect(Unit) { message = catching { reload() }.exceptionOrNull()?.let { "Could not read your gear rules." } }

    FTCard(title = "GEAR RULES") {
        Body("When you use gear on a lift, and from what weight. A new set pre-fills from these as you type its weight; you can always change it per set. Leave the weight empty for gear you always use on that lift.", muted = true)
        if (rules.isEmpty()) Body("No rules yet.", muted = true)
        rules.groupBy { it.movementKey }.forEach { (key, rs) ->
            Label(key.uppercase())
            rs.sortedBy { it.fromKg ?: 0.0 }.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Body(GEAR_LABEL[r.gear].orEmpty() + (r.fromKg?.let { " from ${if (it == Math.rint(it)) it.toLong().toString() else it.toString()} kg" } ?: " at any weight"))
                    Text(
                        "REMOVE", style = FTType.MonoCaption, color = FT.Bad,
                        modifier = Modifier.ftClickable { scope.launch { message = catching { repo.delete(r.movementKey, r.gear); reload() }.exceptionOrNull()?.let { "Not removed." } } },
                    )
                }
            }
        }
        Label("ADD A RULE")
        FieldTextField(lift, { lift = it }, "Lift, e.g. Deadlift")
        Spacer(Modifier.height(8.dp))
        Chips(GEAR_TAGS, setOf(gear), { GEAR_LABEL.getValue(it) }) { gear = it }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(160.dp)) { FieldTextField(from, { from = it }, "From kg (any)", keyboardType = KeyboardType.Number) }
        }
        Spacer(Modifier.height(8.dp))
        val kg = from.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        FTButton("SAVE RULE", enabled = lift.isNotBlank() && (from.isBlank() || kg != null)) {
            scope.launch {
                val failure = catching { repo.save(lift, gear, kg); reload() }.exceptionOrNull()
                message = failure?.let { "Not saved: ${it.message ?: "try again"}" } ?: "Saved: ${GEAR_LABEL.getValue(gear).lowercase()} on ${lift.trim()}."
                if (failure == null) { lift = ""; from = "" }
            }
        }
        message?.let { Body(it, muted = true) }
    }
}
