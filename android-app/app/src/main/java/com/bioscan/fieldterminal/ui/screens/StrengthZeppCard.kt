package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.StrengthImportRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.ZeppStrengthData
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetails
import com.bioscan.fieldterminal.domain.ExercisesSource
import com.bioscan.fieldterminal.domain.ZeppVersionState
import com.bioscan.fieldterminal.domain.exerciseSummary
import com.bioscan.fieldterminal.domain.exercisesSource
import com.bioscan.fieldterminal.domain.zeppExercises
import com.bioscan.fieldterminal.domain.zeppVersionState
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// A strength workout's exercises against Zepp's version (domain/StrengthImport.kt). Exercises the Zepp import wrote
// follow Zepp by themselves; exercises changed in the app stay as they are, and when Zepp's version moves on this card
// shows both and the person chooses. RE-IMPORT FROM ZEPP pulls the workout again, for a correction made in Zepp after
// the automatic re-sync of the last three days.
private const val INFO =
    "Exercises imported from Zepp follow Zepp: correct them in the Zepp app and the next sync updates them here. " +
        "Exercises you changed in this app are never overwritten by a sync; when Zepp's version changes after your edit, " +
        "both are shown here and you choose. The app re-syncs the last three days when it opens; for an older workout, " +
        "use RE-IMPORT FROM ZEPP."

@Composable
fun StrengthZeppCard(
    sessionId: Long,
    sessionDate: LocalDate,
    details: ExerciseSessionDetails,
    zepp: ZeppStrengthData?,
    /** Re-reads the session and Zepp's version after a change. */
    onChanged: suspend () -> Unit,
) {
    val repo = remember { StrengthImportRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var message by remember(sessionId) { mutableStateOf<String?>(null) }
    val state = zeppVersionState(details, zepp)

    fun run(action: suspend () -> String?) {
        busy = true
        message = null
        scope.launchSafe(onError = { e -> message = "Couldn't reach Zepp: ${describeLoadFailure(e)}" }, onDone = { busy = false }) {
            val result = action()
            onChanged()
            message = result
        }
    }

    FTCard(title = if (state == ZeppVersionState.ZEPP_DIFFERS) "ZEPP HAS A DIFFERENT VERSION" else "EXERCISES AND ZEPP", info = INFO) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state == ZeppVersionState.ZEPP_DIFFERS && zepp != null) {
                Text(
                    "You changed this workout's exercises in the app, and Zepp's version is different. Nothing is replaced until you choose.",
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
                Text("YOURS", style = FTType.LabelCaps, color = FT.TextSecondary)
                exerciseSummary(details.exercises.orEmpty()).forEach { Text(it, style = FTType.Caption, color = FT.TextPrimary) }
                Text("ZEPP", style = FTType.LabelCaps, color = FT.TextSecondary)
                exerciseSummary(zepp.zeppExercises().orEmpty()).forEach { Text(it, style = FTType.Caption, color = FT.TextPrimary) }
                if (confirming) {
                    FTConfirmDestructive(
                        message = "Replace your exercises with Zepp's? Your sets, reps, weights and per-set RPE for this workout are replaced.",
                        confirmLabel = "USE ZEPP'S",
                        busy = busy,
                        onCancel = { confirming = false },
                    ) {
                        run {
                            repo.useZeppVersion(sessionId, zepp)
                            confirming = false
                            "Now Zepp's version; later corrections in Zepp follow automatically."
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FTButton("KEEP MINE", enabled = !busy, modifier = Modifier.weight(1f)) {
                            val fingerprint = zepp.fingerprint ?: return@FTButton
                            run {
                                repo.keepMine(sessionId, fingerprint)
                                "Kept yours. You'll be asked again only if Zepp's version changes."
                            }
                        }
                        FTButton("USE ZEPP'S VERSION", enabled = !busy, emphasis = FTButtonEmphasis.Primary, modifier = Modifier.weight(1f)) { confirming = true }
                    }
                }
            } else {
                Text(
                    when {
                        state == ZeppVersionState.NO_ZEPP_VERSION -> "No Zepp exercise list is stored for this workout."
                        exercisesSource(details) == ExercisesSource.APP -> "Your own exercises, changed in the app; a sync never overwrites them."
                        else -> "From Zepp: a correction made in the Zepp app updates them on the next sync."
                    },
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
            }
            FTButton(if (busy) "WORKING…" else "RE-IMPORT FROM ZEPP", enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                run {
                    val fresh = repo.refetchFromZepp(sessionId, sessionDate)
                    when {
                        fresh?.zeppExercises() == null -> "Zepp has no strength sets for this workout."
                        exercisesSource(details) == ExercisesSource.APP -> "Fetched Zepp's current version; if it differs from yours, both are shown above."
                        else -> "Up to date with Zepp."
                    }
                }
            }
            message?.let { Text(it, style = FTType.Caption, color = FT.TextMuted) }
        }
    }
}
