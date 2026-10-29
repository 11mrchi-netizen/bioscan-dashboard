package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Composable
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider

// "Test properly" for a repeatable intervention that has reached the top of the ladder: pairs of equal periods, with it in one
// and without in the other, the order inside each pair drawn at registration -- the same pre-registration sheet supplements
// and fueling use. The days it was done come from the events logged for it (runExperiments reads intervention_events), so
// log each time during the with-periods. Locks the outcome and the smallest change that would matter.
@Composable
fun EventExperimentSheet(interventionId: String, interventionName: String, initialPrimary: String, onDismiss: () -> Unit, onRegistered: () -> Unit) {
    ExperimentSheet(
        subjectName = interventionName,
        intro = "Pairs of equal periods: in one you do $interventionName as you have been, in the other you don't; the order inside each pair is drawn at random when you register, and every period starts with a washout. Log each time during the with-periods, because the days with a logged event are the with-days. Day 1 is tomorrow. The outcome and the smallest change that would matter are locked once registered.",
        initialPrimary = initialPrimary,
        register = { name, primary, protocol, preview ->
            ExperimentsRepository(SupabaseClientProvider.client).register(
                interventionId = interventionId, name = name, primary = primary, secondary = emptyList(), protocol = protocol, preview = preview,
            )
        },
        onDismiss = onDismiss,
        onRegistered = onRegistered,
    )
}
