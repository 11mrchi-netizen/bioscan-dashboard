package com.bioscan.fieldterminal.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.LOG_WINDOW_DAYS
import com.bioscan.fieldterminal.data.LogFeed
import com.bioscan.fieldterminal.data.LogRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

// The Log: the feed of everything logged, newest first, loaded a window at a time.
@Stable
internal class LogState {
    var feed by mutableStateOf<LogFeed?>(null)
    var olderExhausted by mutableStateOf(false)
    var loadingOlder by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    suspend fun load(windowDays: Int) {
        error = null
        try {
            val since = LocalDate.now().minusDays(windowDays - 1L)
            val loaded = LogRepository(SupabaseClientProvider.client).loadFeed(since)
            // A widening that adds nothing older than the previous window ends LOAD OLDER.
            if (windowDays > LOG_WINDOW_DAYS) {
                val previousStart = since.plusDays(LOG_WINDOW_DAYS.toLong())
                olderExhausted = loaded.entries.none { it.timestamp.toLocalDate().isBefore(previousStart) }
            }
            feed = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        } finally {
            loadingOlder = false
        }
    }
}
