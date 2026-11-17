package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.IntimacyRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.model.LogEncounterRow
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.PersonProfileRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.StiTestRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.parallel

// ME › INTIMACY: the encounters and the people (the page cannot draw without them), and around them the daily series
// for the trend and correlation cards and the STI tests. Each of those may fail on its own without taking the page down.
@Stable
internal class IntimacyPageState {
    val guard = LoadGuard()

    var encounters by mutableStateOf<List<LogEncounterRow>?>(null)
    var people by mutableStateOf<List<PersonProfileRow>?>(null)
    var activity by mutableStateOf<List<LogSexualActivityRow>>(emptyList())
    var wellbeing by mutableStateOf<List<WellbeingAnalysisRow>>(emptyList())
    var wearable by mutableStateOf<List<WearableAnalysisRow>>(emptyList())
    var sleep by mutableStateOf<List<SleepAnalysisRow>>(emptyList())
    var tests by mutableStateOf<List<StiTestRow>?>(null)
    var testsError by mutableStateOf<String?>(null)

    suspend fun load() {
        guard.run {
            val client = SupabaseClientProvider.client
            val repo = IntimacyRepository(client)
            val analysis = AnalysisRepository(client)
            parallel(
                { encounters = repo.loadEncounters() },
                { people = repo.loadPeople() },
                { activity = catching { analysis.loadSexualActivityDaily() }.getOrDefault(emptyList()) },
                { wellbeing = catching { analysis.loadWellbeingDaily() }.getOrDefault(emptyList()) },
                { wearable = catching { analysis.loadWearableDaily() }.getOrDefault(emptyList()) },
                { sleep = catching { analysis.loadSleepDaily() }.getOrDefault(emptyList()) },
                { loadTests() },
            )
        }
    }

    suspend fun loadTests() {
        catching { IntimacyRepository(SupabaseClientProvider.client).loadStiTests() }
            .onSuccess { tests = it; testsError = null }
            .onFailure { testsError = describeLoadFailure(it) }
    }
}
