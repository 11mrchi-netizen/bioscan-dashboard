package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// DAV-345. Wire shapes for the programming engine's tables. Every field that can be absent
// has a default so a partial jsonb or a new column never crashes a read (the
// training_cycles.focus lesson).

@Serializable
data class TrainingDefinitionRow(
    val id: Long = 0,
    val methodology: String,
    val kind: String,
    val key: String,
    val version: Int = 1,
    val title: String = "",
    val body: JsonElement,
    val status: String = "draft",
)

@Serializable
data class NewTrainingDefinitionRow(
    val methodology: String,
    val kind: String,
    val key: String,
    val version: Int,
    @SerialName("schema_version") val schemaVersion: Int,
    val title: String,
    val body: JsonElement,
    @SerialName("source_ref") val sourceRef: String? = null,
    val status: String = "draft",
)

@Serializable
data class TrainingSettingsRow(
    @SerialName("plates_kg") val platesKg: List<Double> = listOf(1.25, 2.5, 5.0, 10.0, 15.0, 20.0),
    @SerialName("default_start_time") val defaultStartTime: String = "07:00:00",
    @SerialName("default_duration_min") val defaultDurationMin: Int = 60,
    @SerialName("second_start_time") val secondStartTime: String = "20:00:00",
    @SerialName("second_duration_min") val secondDurationMin: Int = 120,
    @SerialName("weighted_percent_base") val weightedPercentBase: String = "added",
)

@Serializable
data class AthleteMaxRow(
    val id: Long = 0,
    @SerialName("exercise_key") val exerciseKey: String,
    @SerialName("exercise_name") val exerciseName: String,
    val kind: String,
    val value: Double,
    val unit: String,
    @SerialName("as_of") val asOf: String,
    val source: String,
)

@Serializable
data class NewAthleteMaxRow(
    @SerialName("exercise_key") val exerciseKey: String,
    @SerialName("exercise_name") val exerciseName: String,
    val kind: String,
    val value: Double,
    val unit: String,
    @SerialName("as_of") val asOf: String,
    val source: String,
    val derivation: JsonElement,
    @SerialName("block_id") val blockId: Long? = null,
)

@Serializable
data class LoggedStrengthSessionRow(
    @SerialName("start_time") val startTime: String,
    val details: JsonElement? = null,
)

@Serializable
data class NewTrainingBlockRow(
    val name: String,
    @SerialName("template_key") val templateKey: String? = null,
    @SerialName("template_version") val templateVersion: Int? = null,
    val components: JsonElement,
    @SerialName("definition_refs") val definitionRefs: JsonElement,
    @SerialName("definition_snapshot") val definitionSnapshot: JsonElement,
    val choices: JsonElement,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val status: String = "scheduled",
    val origin: String = "generated",
)

@Serializable
data class NewBlockDomainRow(
    @SerialName("block_id") val blockId: Long,
    val domain: String,
    val role: String,
)

@Serializable
data class NewPlannedSessionRow(
    @SerialName("block_id") val blockId: Long,
    @SerialName("sequence_no") val sequenceNo: Int,
    @SerialName("week_index") val weekIndex: Int,
    @SerialName("day_slot") val daySlot: Int,
    @SerialName("slot_in_day") val slotInDay: Int,
    val domain: String,
    @SerialName("work_kind") val workKind: String,
    @SerialName("module_ref") val moduleRef: String,
    val title: String,
    val prescription: JsonElement,
    @SerialName("original_date") val originalDate: String,
    @SerialName("scheduled_date") val scheduledDate: String,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
)

@Serializable
data class IdRow(val id: Long)
