package com.bioscan.fieldterminal.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

// Shared pagination loop for every Health Connect sync repository -- reads
// every record of one type in [since, until], following pageToken until
// Health Connect reports none left. Factored out of
// HealthConnectDailySyncRepository (Phase G2) once HealthConnectExerciseSyncRepository
// (Phase G3) needed the exact same loop.
suspend fun <T : Record> HealthConnectClient.readAllRecords(type: KClass<T>, since: Instant, until: Instant): List<T> {
    val results = mutableListOf<T>()
    var pageToken: String? = null
    do {
        val response = readRecords(
            ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = TimeRangeFilter.between(since, until),
                pageToken = pageToken,
            ),
        )
        results += response.records
        pageToken = response.pageToken?.takeIf { it.isNotEmpty() }
    } while (pageToken != null)
    return results
}
