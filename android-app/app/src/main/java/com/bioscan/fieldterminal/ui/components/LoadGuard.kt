package com.bioscan.fieldterminal.ui.components

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.NoSessionException
import com.bioscan.fieldterminal.data.ReadCache
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// An exception that escapes a LaunchedEffect takes the whole app down, so an
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

/**
 * [refreshOnUpdates]: load again when a background refresh of the read cache brings different data (the page opened from
 * the last data it showed, kept on disk). The reload reads the fresh copy from memory. TODAY passes false: it keeps its
 * own page and decides itself when to rebuild.
 */
@Composable
fun rememberLoadGuard(refreshOnUpdates: Boolean = true): LoadGuard {
    val guard = remember { LoadGuard() }
    if (refreshOnUpdates) RefreshOnCacheUpdates(guard)
    return guard
}

/** Loads [guard]'s page again when the read cache brings different data. For a guard owned by a state holder. */
@Composable
fun RefreshOnCacheUpdates(guard: LoadGuard) {
    LaunchedEffect(guard) {
        // A burst of refreshes landing together (a page reads several tables) reloads once.
        ReadCache.updates.collectLatest {
            delay(300)
            guard.retry()
        }
    }
}

/**
 * A page's main data: null while it loads; a failed load is on the guard (shown with [LoadErrorCard], RETRY loads again)
 * and the value already on screen stays. Loads again when [keys] change and when the read cache brings different data.
 *
 *   val (sessions, guard) = rememberGuarded { repo.loadSessions() }
 *   if (sessions == null) { LoadingOrError(guard, "Loading…"); return }
 */
@Composable
fun <T : Any> rememberGuarded(vararg keys: Any?, load: suspend () -> T): Pair<T?, LoadGuard> {
    val guard = rememberLoadGuard()
    var value by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(guard.attempt, *keys) { guard.run { value = load() } }
    return value to guard
}

/** What a page shows before its main data is there: the failure with RETRY, or the loading line. */
@Composable
fun LoadingOrError(guard: LoadGuard, loading: String) {
    if (guard.error != null) LoadErrorCard(guard) else Text(loading, style = FTType.BodySmall, color = FT.TextMuted)
}

/**
 * One line for a card whose own data could not be loaded: it says what, and RETRY loads it again. Draws nothing when the
 * load worked, so a card that hides itself when it has nothing to show still says when it could not find out.
 */
@Composable
fun LoadErrorLine(guard: LoadGuard, what: String, modifier: Modifier = Modifier) {
    if (guard.error == null) return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Couldn't load $what.", style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.weight(1f))
        Text("RETRY", style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Retry") { guard.retry() })
    }
}

/** produceState for a card's own data: a failure goes to [guard] (see [LoadErrorLine]) and the value stays as it was. */
@Composable
fun <T> produceGuarded(guard: LoadGuard, initial: T, vararg keys: Any?, load: suspend () -> T): State<T> =
    produceState(initial, guard.attempt, *keys) { guard.run { value = load() } }

/** A number that grows each time a background refresh brings different data: a key for loads outside a LoadGuard. */
@Composable
fun rememberCacheVersion(): Int {
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        ReadCache.updates.collectLatest {
            delay(300)
            version++
        }
    }
    return version
}

// Plain words for the two cases a person can act on: no connection, and everything else.
fun describeLoadFailure(e: Throwable): String {
    var t: Throwable? = e
    while (t != null) {
        if (t is NoSessionException) return t.message.orEmpty()
        val name = t.javaClass.name
        if (t is IOException || name.contains("Timeout") || name.contains("UnknownHost") || name.contains("Connect")) {
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
        Text("COULDN'T LOAD", style = FTType.LabelCaps, color = FT.Bad)
        Text(message, style = FTType.BodySmall, color = FT.TextSecondary)
        FTButton(label = "RETRY", onClick = onRetry)
    }
}

// Runs independent loads at the same time, off the main thread (JSON decoding of a few hundred rows is not free on the UI
// thread). The first failure cancels the siblings and propagates to the enclosing guard.run { }.
suspend fun parallel(vararg blocks: suspend () -> Unit) = coroutineScope {
    blocks.forEach { b -> launch(Dispatchers.Default) { b() } }
}

// scope.launch { network call } crashes the app when the call throws (offline, timeout). launchSafe logs the failure,
// reports it through onError, and always runs onDone, so a busy flag set at the start of a click handler cannot stay stuck.
fun CoroutineScope.launchSafe(
    onError: (Throwable) -> Unit = {},
    onDone: () -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("launchSafe", "background action failed: ${e.message}", e)
        onError(e)
    } finally {
        onDone()
    }
}
