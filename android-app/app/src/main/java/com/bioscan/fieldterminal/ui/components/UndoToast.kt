package com.bioscan.fieldterminal.ui.components

import com.bioscan.fieldterminal.data.catching
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// One toast at a time above the dock, for two things:
//   * UNDO: a delete is held for [UNDO_WINDOW_MS] before it reaches the server. UNDO cancels it; the screen already
//     hid the item (optimistic), so undoing only shows it again. A newer toast commits the older one at once.
//   * a failed optimistic save: the screen already showed the change; it is put back and RETRY offered.
// The work runs in the app's own scope, so closing the sheet or leaving the page does not cancel a pending delete.

const val UNDO_WINDOW_MS = 5_000L
const val NOTICE_MS = 4_000L

data class Toast(val id: Long, val message: String, val action: String?, val onAction: (() -> Unit)?)

object Toasts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val current = MutableStateFlow<Toast?>(null)
    private var nextId = 0L
    private var pending: Pair<Long, suspend () -> Unit>? = null
    private var timer: Job? = null

    /**
     * Holds [commit] for the undo window, then runs it. [onUndo] restores what the screen hid; [onFailed] does the same
     * when the commit itself fails (the toast then says so).
     */
    fun undoable(message: String, commit: suspend () -> Unit, onUndo: () -> Unit, onFailed: (Throwable) -> Unit = { onUndo() }) {
        flush()
        val id = ++nextId
        pending = id to {
            catching { commit() }.onFailure { e ->
                onFailed(e)
                notice("Couldn't finish: ${e.message ?: "try again"}")
            }
        }
        current.value = Toast(id, message, "UNDO") {
            if (pending?.first == id) { pending = null; timer?.cancel(); onUndo() }
            dismiss(id)
        }
        timer = scope.launch {
            delay(UNDO_WINDOW_MS)
            runPending(id)
            dismiss(id)
        }
    }

    /** A plain notice, optionally with one action (RETRY). */
    fun notice(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val id = ++nextId
        current.value = Toast(id, message, action, onAction?.let { a -> { a(); dismiss(id) } })
        scope.launch { delay(NOTICE_MS); dismiss(id) }
    }

    /**
     * An optimistic change: [apply] updates the screen now, [save] runs in the background, and on failure [revert]
     * puts it back with a RETRY toast that tries the whole thing again.
     */
    fun optimistic(apply: () -> Unit, save: suspend () -> Unit, revert: () -> Unit, what: String = "Couldn't save") {
        apply()
        scope.launch {
            catching { save() }.onFailure {
                revert()
                notice(what, "RETRY") { optimistic(apply, save, revert, what) }
            }
        }
    }

    /** Commits a held delete now (a newer toast, or the app going to the background). */
    fun flush() {
        val p = pending ?: return
        timer?.cancel()
        scope.launch { runPending(p.first) }
    }

    private suspend fun runPending(id: Long) {
        val p = pending?.takeIf { it.first == id } ?: return
        pending = null
        p.second()
    }

    private fun dismiss(id: Long) { if (current.value?.id == id) current.value = null }
}

@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val toast by Toasts.current.collectAsState()
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(toast != null, enter = if (FTMotion.reduced) androidx.compose.animation.EnterTransition.None else fadeIn(FTMotion.tweenBase()) + slideInVertically(FTMotion.tweenBase()) { it / 2 }, exit = if (FTMotion.reduced) androidx.compose.animation.ExitTransition.None else fadeOut(FTMotion.tweenFast()) + slideOutVertically(FTMotion.tweenFast()) { it / 2 }) {
            val t = toast ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(FT.RadiusModule)
            Row(
                Modifier.fillMaxWidth().background(FT.Surface, shape).border(FT.BorderWidth, FT.GlassBorder, shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(t.message, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                if (t.action != null && t.onAction != null) {
                    Text(t.action, style = FTType.LabelCaps, color = FT.Emerald,
                        modifier = Modifier.ftClickable(onClickLabel = t.action) { t.onAction.invoke() }.padding(4.dp))
                }
            }
        }
    }
}
