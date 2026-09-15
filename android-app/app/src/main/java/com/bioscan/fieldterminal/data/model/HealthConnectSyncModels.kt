package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase G2. One minimal, single-metric row class per upsert call --
// deliberately NOT one shared "god row" with every wearable_daily/
// body_metrics column as nullable fields. kotlinx.serialization encodes
// nulls explicitly by default, and Postgrest's upsert applies every column
// present in the request body on conflict -- a shared row with unrelated
// fields left null would silently overwrite real data (e.g. a Wellness
// Project-sourced steps count) with null the moment any other metric synced
// for that date. Each of these touches exactly the one column it names,
// nothing else on that row.
@Serializable
data class StepsUpsertRow(val date: String, val steps: Int)

@Serializable
data class ActiveCaloriesUpsertRow(val date: String, @SerialName("calories_active") val caloriesActive: Int)

@Serializable
data class TotalCaloriesUpsertRow(val date: String, @SerialName("calories_total") val caloriesTotal: Int)

@Serializable
data class Vo2MaxUpsertRow(val date: String, val vo2max: Double)

@Serializable
data class BmrUpsertRow(val date: String, val bmr: Double)

@Serializable
data class RestingHeartRateUpsertRow(val date: String, val rhr: Double)

@Serializable
data class HrvUpsertRow(val date: String, val hrv: Double)

@Serializable
data class Spo2UpsertRow(val date: String, @SerialName("spo2_avg") val spo2Avg: Double)

@Serializable
data class WeightUpsertRow(val date: String, @SerialName("weight_kg") val weightKg: Double)

@Serializable
data class BodyFatUpsertRow(val date: String, @SerialName("body_fat_pct") val bodyFatPct: Double)

@Serializable
data class HeightUpsertRow(val date: String, @SerialName("height_cm") val heightCm: Double)

// Sleep is the one exception to "single column per upsert": a
// SleepSessionRecord already gives one real, coherent row (bedtime/wake
// time/stage minutes) per night in a single reading, so there's no
// cross-metric null-overwrite risk the way there is when 5 independent
// record types each cover part of one wearable_daily row. `score` is
// deliberately absent -- Health Connect has no equivalent of a device's
// proprietary sleep score, and this must never null out a value the old
// Wellness Project sync wrote.
@Serializable
data class SleepUpsertRow(
    val date: String,
    val hours: Double,
    val bedtime: String,
    @SerialName("wake_time") val wakeTime: String,
    @SerialName("deep_min") val deepMin: Double? = null,
    @SerialName("rem_min") val remMin: Double? = null,
    @SerialName("light_min") val lightMin: Double? = null,
)

@Serializable
data class RespiratoryRateUpsertRow(val date: String, @SerialName("respiratory_rate") val respiratoryRate: Double)
