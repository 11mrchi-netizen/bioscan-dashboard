package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.WeatherSource
import com.bioscan.fieldterminal.domain.fuel.predictSweatRate
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZonedDateTime

// Today's sweat rate for the daily fluid range (fuel planning phase 4): the person's sweat profile (their sweat tests, or
// the prior) at today's daytime temperature at home (MapSettingsStore's home location; 18 °C when it is not set or the
// weather cannot be read). One definition for the Status FUEL preview and the Fuel hydration card, so they agree.

data class TodaySweat(val mlPerHour: Double, val environmentKnown: Boolean, val tempC: Double?)

@Serializable
private data class WeightOnlyRow(@SerialName("weight_kg") val weightKg: Double? = null)

private const val FALLBACK_TEMP_C = 18.0

suspend fun todaySweatRate(supabase: SupabaseClient, home: Pair<Double, Double>?): TodaySweat? = runCatching {
    val weight = ReadCache.get("fuel.latest_weight", tables = setOf("body_metrics")) {
        supabase.postgrest.from("body_metrics").select(Columns.list("weight_kg")) {
            filter { filterNot("weight_kg", FilterOperator.IS, "null") }
            order("date", Order.DESCENDING)
            limit(1)
        }.decodeList<WeightOnlyRow>().firstOrNull()?.weightKg
    } ?: 70.0
    val profile = SweatTestsRepository(supabase).profile(weight)
    val temp = home?.let { (lat, lon) ->
        runCatching {
            val day = LocalDate.now()
            val zone = ZonedDateTime.now().zone
            val series = WeatherRepository().fetchHourly(lat, lon, day.atTime(9, 0).atZone(zone), day.atTime(19, 0).atZone(zone))
            series.hours.takeIf { series.source == WeatherSource.FORECAST && it.isNotEmpty() }?.map { it.tempC }?.average()
        }.getOrNull()
    }
    val estimate = predictSweatRate(profile, Intensity.MODERATE, "run", temp ?: FALLBACK_TEMP_C, weight)
    TodaySweat(estimate.mlPerHour, temp != null, temp)
}.getOrNull()
