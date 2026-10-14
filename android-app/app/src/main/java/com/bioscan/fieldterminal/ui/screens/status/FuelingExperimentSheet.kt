package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Composable
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.FuelingRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider

// Fueling N-of-1: phase A you fuel as usual, phase B you use this product on long sessions. It registers an intervention
// (nutrition / fueling_recipe) and an experiment pointing at it, through the same pre-registration sheet as supplements.
// The outcomes compared are the daily wearable and wellbeing measures; the in-run carb-rate analysis
// (analysis-core/fuelingExperiment.ts) is not connected to the scheduled analysis yet (docs/endurance-fueling/README.md).
@Composable
fun FuelingExperimentSheet(productId: Long, productName: String, onDismiss: () -> Unit, onRegistered: () -> Unit) {
    ExperimentSheet(
        subjectName = "Fueling: $productName",
        intro = "Phase A: you fuel as you usually do. Phase B: you use $productName on your long sessions and log each one. Day 1 of A is today. You pick the one outcome now and the plan is locked once registered. It compares your daily measures (sleep, HRV, resting heart rate, mood...) between the phases, not the in-run carb rate: that analysis is not connected yet.",
        register = { name, primary, protocol, preview ->
            val interventionId = FuelingRepository(SupabaseClientProvider.client).createFuelingIntervention(productId, productName)
            ExperimentsRepository(SupabaseClientProvider.client).register(
                interventionId = interventionId, name = name, primary = primary, secondary = emptyList(), protocol = protocol, preview = preview,
            )
        },
        onDismiss = onDismiss,
        onRegistered = onRegistered,
    )
}
