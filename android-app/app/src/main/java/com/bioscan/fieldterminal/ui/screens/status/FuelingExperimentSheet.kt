package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Composable
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.FuelingRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider

// Fueling N-of-1, randomised (analysis review phase F): pairs of periods, fueling as usual in one and with this product on
// long sessions in the other, the order inside each pair drawn at registration. It registers an intervention (nutrition /
// fueling_recipe) and an experiment pointing at it, through the same pre-registration sheet as supplements.
// The outcomes compared are the daily wearable and wellbeing measures; the in-run carb-rate analysis
// (analysis-core/fuelingExperiment.ts) is not connected to the scheduled analysis yet (docs/endurance-fueling/README.md).
@Composable
fun FuelingExperimentSheet(productId: Long, productName: String, onDismiss: () -> Unit, onRegistered: () -> Unit) {
    ExperimentSheet(
        subjectName = "Fueling: $productName",
        intro = "Pairs of equal periods: in one you fuel as you usually do, in the other you use $productName on your long sessions and log each one; the order inside each pair is drawn at random when you register, and every period starts with a washout. Day 1 is tomorrow. You choose the outcome and the smallest change that would matter now; both are locked once registered. It compares your daily measures (sleep, HRV, resting heart rate, mood...), not the in-run carb rate: that analysis is not connected yet.",
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
