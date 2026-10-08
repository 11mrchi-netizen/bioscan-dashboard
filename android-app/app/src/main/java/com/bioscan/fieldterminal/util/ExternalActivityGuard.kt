package com.bioscan.fieldterminal.util

import android.os.SystemClock

// A full-screen system activity (camera, photo picker, Google's barcode scanner) taking the
// foreground makes a ModalBottomSheet fire onDismissRequest although the user did not dismiss
// it, so the sheet closed and the lifter landed back on the opening page. Anything that
// launches such an activity wraps the launch in `around`; sheets ignore dismiss requests while
// `active` (and for a short grace period after, because the callback can fire on return).
object ExternalActivityGuard {
    private var depth = 0
    private var graceUntil = 0L

    val active: Boolean get() = depth > 0 || SystemClock.uptimeMillis() < graceUntil

    suspend fun <T> around(block: suspend () -> T): T {
        depth++
        try {
            return block()
        } finally {
            depth--
            graceUntil = SystemClock.uptimeMillis() + 1_000L
        }
    }
}
