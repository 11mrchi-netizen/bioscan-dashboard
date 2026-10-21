package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Performance pass, Stage 1 (error recovery). An exception that escapes a LaunchedEffect takes the whole app down, so an
// offline phone or a timed-out request used to crash the app or leave a spinner forever. Every screen load goes through
// a LoadGuard instead:
//
//   val guard = rememberLoadGuard()
//   LaunchedEffect(guard.attempt) {
//       guard.run { data = repo.load() }      // failures become guard.error, never an exception
//       isLoading = false
//   }
//   if (guard.error != null) LoadErrorCard(guard) else ...
//
// RETRY bumps `attempt`, which re-runs the effect.
@Stable
class LoadGuard {
    var error by mutableStateOf<String?>(null)
        private set
    var attempt by mutableIntStateOf(0)
        private set

    fun retry() {
        attempt++
    }

    // Runs [block]; cancellation propagates (a screen leaving composition is not an error), everything else is captured.
    suspend fun run(block: suspend () -> Unit) {
        error = null
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
    }
}

@Composable
fun rememberLoadGuard(): LoadGuard = remember { LoadGuard() }

// Plain words for the two cases a person can act on: no connection, and everything else.
fun describeLoadFailure(e: Throwable): String {
    var t: Throwable? = e
    while (t != null) {
        val name = t.javaClass.name
        if (t is java.io.IOException || name.contains("Timeout") || name.contains("UnknownHost") || name.contains("Connect")) {
            return "No connection to the server (offline or timed out). Your data is safe."
        }
        t = t.cause
    }
    return e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong loading this."
}

@Composable
fun LoadErrorCard(guard: LoadGuard, modifier: Modifier = Modifier, onRetry: () -> Unit = { guard.retry() }) {
    val message = guard.error ?: return
    Column(modifier = modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("COULDN'T LOAD", style = FTType.LabelCaps, color = FT.Critical)
        Text(message, style = FTType.BodySmall, color = FT.TextSecondary)
        FTButton(label = "RETRY", onClick = onRetry)
    }
}

// Runs independent loads at the same time, off the main thread (JSON decoding of a few hundred rows is not free on the UI
// thread). The first failure cancels the siblings and propagates to the enclosing guard.run { }.
suspend fun parallel(vararg blocks: suspend () -> Unit) = kotlinx.coroutines.coroutineScope {
    blocks.forEach { b -> launch(kotlinx.coroutines.Dispatchers.Default) { b() } }
}

// scope.launch { network call } crashes the app when the call throws (offline, timeout). launchSafe logs the failure,
// reports it through onError, and always runs onDone, so a busy flag set at the start of a click handler cannot stay stuck.
fun kotlinx.coroutines.CoroutineScope.launchSafe(
    onError: (Throwable) -> Unit = {},
    onDone: () -> Unit = {},
    block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
): kotlinx.coroutines.Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("launchSafe", "background action failed: ${e.message}", e)
        onError(e)
    } finally {
        onDone()
    }
}

// Optional enrichment (a max-HR ceiling, a static library): a failure yields null, never a crash or a stuck screen.
suspend fun <T> softLoad(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    android.util.Log.w("softLoad", "optional load failed: ${e.message}")
    null
}
