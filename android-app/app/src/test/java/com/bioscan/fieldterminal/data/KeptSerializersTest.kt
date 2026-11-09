package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import com.bioscan.fieldterminal.domain.BodyMeasurement
import com.bioscan.fieldterminal.domain.BodySide
import com.bioscan.fieldterminal.domain.BodySite
import com.bioscan.fieldterminal.domain.LabReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

// ReadCache.kept keeps a disk copy only for types it can serialise: the pages' main types must round-trip.
class KeptSerializersTest {
    private inline fun <reified T> roundTrip(v: T) = assertEquals(v, Json.decodeFromString(serializer<T>(), Json.encodeToString(serializer<T>(), v)))

    @Test
    fun pageTypesRoundTrip() {
        roundTrip(listOf(BodyCheckinRecord(1, LocalDateTime.of(2026, 11, 4, 8, 0), measurements = listOf(BodyMeasurement(BodySite.WAIST_NAVEL, BodySide.NONE, listOf(84.0, 84.4))))))
        roundTrip(listOf(LabReading("Ferritin", 120.0, null, "ng/mL", null, LocalDate.of(2026, 4, 25))))
    }
}
