package com.bioscan.fieldterminal.data

import android.content.Context
import com.bioscan.fieldterminal.domain.HistoryPullState
import kotlinx.serialization.json.Json

// Where the Zepp history pull keeps its progress (domain/ZeppHistoryPull.kt): written by ZeppHistoryWorker after every
// window, read by Settings. The heartbeat says the worker is alive; without one for a few minutes the pull is shown as
// stopped and can be carried on.
class ZeppHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): HistoryPullState? =
        prefs.getString(KEY_STATE, null)?.let { runCatching { json.decodeFromString<HistoryPullState>(it) }.getOrNull() }

    fun save(state: HistoryPullState) {
        prefs.edit().putString(KEY_STATE, json.encodeToString(HistoryPullState.serializer(), state)).putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    fun beat() {
        prefs.edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    fun stopped() {
        prefs.edit().remove(KEY_HEARTBEAT).apply()
    }

    /** True while the worker has reported in the last few minutes. */
    fun running(nowMs: Long = System.currentTimeMillis()): Boolean =
        prefs.getLong(KEY_HEARTBEAT, 0L).let { it > 0 && nowMs - it < ALIVE_MS }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "zepp_history_pull"
        const val KEY_STATE = "state"
        const val KEY_HEARTBEAT = "heartbeat"
        const val ALIVE_MS = 3 * 60_000L
    }
}
