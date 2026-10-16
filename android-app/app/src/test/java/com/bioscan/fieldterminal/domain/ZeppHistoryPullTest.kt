package com.bioscan.fieldterminal.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ZeppHistoryPullTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the server's row decodes, extra columns ignored`() {
        val row = json.decodeFromString<ZeppHistoryPullRow>(
            """{"user_id":"u","next_end":"2025-03-01","workouts":120,"band_days":540,"weeks_done":80,"oldest_with_data":"2025-03-09",
               "done":false,"error":null,"running_since":null,"updated_at":"2026-10-16T09:00:00+00:00","empty_streak":1}""",
        )
        assertEquals(120, row.workouts)
        assertEquals("2025-03-09", row.oldestWithData)
        assertEquals(60L, row.secondsSinceUpdate(OffsetDateTime.parse("2026-10-16T09:01:00Z")))
    }

    @Test
    fun `the settings line says where the pull is and what it waits on`() {
        val base = ZeppHistoryPullRow(nextEnd = "2025-03-01", workouts = 120, bandDays = 540, weeksDone = 80, oldestWithData = "2025-03-09")
        assertTrue(base.describe().startsWith("Pulling on the server, now at 2025-03-01 back to 2025-03-09 (120 workouts, 540 days"))
        assertTrue(base.copy(done = true).describe().startsWith("Done: Zepp history back to 2025-03-09"))
        assertTrue(base.copy(error = "Zepp refused the app token").describe().startsWith("Paused back to 2025-03-09"))
        assertTrue(ZeppHistoryPullRow(nextEnd = "2026-10-16").describe().startsWith("Starting"))
    }
}
