package com.bioscan.fieldterminal.data.routine

import com.bioscan.fieldterminal.domain.routine.Trip
import com.bioscan.fieldterminal.domain.routine.TripLeg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

// The trip insert bug (Nov 2026): with the Supabase client's JSON (defaults left out), a still trip's active_min = 0 was
// dropped from its object, and PostgREST's multi-row insert sent null for it, failing every mixed batch. Every row must
// carry every column.
class TripInsertRowTest {
    @Test
    fun everyRowCarriesEveryColumnEvenAtZeroAndFalse() {
        val json = Json { ignoreUnknownKeys = true } // encodeDefaults is false, as in the client
        val still = Trip(0, 60_000, "a", "a", 0.0, listOf(TripLeg("still", 1.0)))
        val walk = Trip(120_000, 600_000, "a", "b", 0.6, listOf(TripLeg("walking", 8.0)))
        val body = json.parseToJsonElement(
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(TripInsertRow.serializer()), listOf(still.toRow(false), walk.toRow(true))),
        ).jsonArray
        val expected = setOf("start_at", "end_at", "from_place_id", "to_place_id", "distance_km", "legs", "active_min", "is_commute")
        body.forEach { assertEquals(expected, it.jsonObject.keys) }
    }
}
