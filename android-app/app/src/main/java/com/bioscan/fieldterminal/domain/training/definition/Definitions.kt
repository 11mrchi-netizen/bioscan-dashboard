package com.bioscan.fieldterminal.domain.training.definition

import com.bioscan.fieldterminal.domain.training.LoadSpec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// DAV-344. The shapes of the program definitions stored (privately) in
// training_definitions.body. Strict on purpose: unknown fields are rejected, so a
// typo in a transcribed program fails loudly instead of being silently ignored. See
// docs/training-programming/02-schema-and-definitions.md. Program *content* never
// lives in this repository; only these shapes do.

/** A number written either as `5` or as `{"min": 3, "max": 5}`. */
@Serializable(with = NumRangeSerializer::class)
data class NumRange(val min: Double, val max: Double) {
    init { require(min <= max) { "range min $min > max $max" } }
    val isFixed: Boolean get() = min == max
    companion object { fun of(x: Number) = NumRange(x.toDouble(), x.toDouble()) }
}

object NumRangeSerializer : KSerializer<NumRange> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): NumRange {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when (el) {
            is JsonPrimitive -> NumRange.of(el.doubleOrNull ?: error("not a number: $el"))
            is JsonObject -> {
                require(el.keys.all { it == "min" || it == "max" }) { "range allows only min and max: ${el.keys}" }
                val min = el["min"]?.jsonPrimitive?.doubleOrNull ?: error("range needs min")
                val max = el["max"]?.jsonPrimitive?.doubleOrNull ?: error("range needs max")
                NumRange(min, max)
            }
            else -> error("expected a number or {min,max}")
        }
    }

    override fun serialize(encoder: Encoder, value: NumRange) {
        (encoder as JsonEncoder).encodeJsonElement(
            if (value.isFixed) JsonPrimitive(value.min) else buildJsonObject { put("min", value.min); put("max", value.max) },
        )
    }
}

@Serializable
data class LoadDto(
    val kind: String,
    val value: Double? = null,
    @SerialName("min_rm") val minRm: Int? = null,
    @SerialName("max_rm") val maxRm: Int? = null,
    val kg: Double? = null,
    val rpe: Double? = null,
) {
    fun toSpec(): LoadSpec = when (kind) {
        "pct_1rm" -> LoadSpec.Pct1rm(value ?: error("pct_1rm needs value"))
        "pct_tm" -> LoadSpec.PctTm(value ?: error("pct_tm needs value"))
        "pct_max_reps" -> LoadSpec.PctMaxReps(value ?: error("pct_max_reps needs value"))
        "work_up_rm" -> LoadSpec.WorkUpRm(minRm ?: error("work_up_rm needs min_rm"), maxRm ?: error("work_up_rm needs max_rm"))
        "fixed" -> LoadSpec.Fixed(kg ?: error("fixed needs kg"))
        "rpe" -> LoadSpec.Rpe(rpe ?: error("rpe needs rpe"))
        "bodyweight" -> LoadSpec.Bodyweight
        "none" -> LoadSpec.None
        else -> error("unknown load kind '$kind'")
    }
}

@Serializable
data class ItemDto(
    val slot: String,
    val role: String = "main", // main | primary | secondary | supplemental | finisher | primer | power
    val sets: NumRange? = null,
    val reps: NumRange? = null,
    val load: LoadDto = LoadDto("none"),
    @SerialName("rest_s") val restS: NumRange? = null,
    val optional: Boolean = false,
    @SerialName("option_of") val optionOf: String? = null,
    val technique: String? = null, // peak | amrap | amsap | none (peak-week items)
    val pair: String? = null,      // links a primer to its power exercise
    val note: String? = null,
)

@Serializable
data class SlotDef(
    val id: String,
    val role: String, // press | squat | hinge | pull | power | core | accessory | circuit
    val standard: String,
    val alternates: List<String> = emptyList(),
    @SerialName("weighted_calisthenics") val weightedCalisthenics: Boolean = false,
    val optional: Boolean = false,
)

@Serializable
data class SessionDef(val id: String, val label: String, val slots: List<String>, val note: String? = null)

@Serializable
data class WeekSessionDef(val session: String, val items: List<ItemDto>, val note: String? = null)

@Serializable
data class WeekDef(
    val week: Int,
    val kind: String = "normal", // normal | peak | deload | taper | test | easy
    @SerialName("counts_toward_block") val countsTowardBlock: Boolean = true,
    val sessions: List<WeekSessionDef> = emptyList(),
)

@Serializable
data class VariantDef(val key: String, val title: String, val weeks: List<WeekDef>, val note: String? = null)

@Serializable
data class OptionDef(
    val id: String,
    val title: String,
    val kind: String, // rep_scheme | set_pattern | exercise | deadlift | scheduling | session_split | emphasis
    val description: String,
    val weeks: List<Int>? = null,
    val effect: JsonObject? = null,
)

@Serializable
data class PeakDef(
    val techniques: List<String>,
    @SerialName("every_weeks") val everyWeeks: NumRange? = null,
    @SerialName("schedule_days") val scheduleDays: List<Int> = emptyList(),
    val note: String? = null,
)

@Serializable
data class ProgressionDef(
    val unit: String, // lb | kg
    val upper: NumRange,
    val lower: NumRange,
    @SerialName("after_weeks") val afterWeeks: NumRange,
    @SerialName("skip_if_incomplete") val skipIfIncomplete: Boolean = true,
    val note: String? = null,
)

@Serializable
data class SuppRule(val weeks: List<Int>, val sets: NumRange, val reps: NumRange, val load: LoadDto)

@Serializable
data class AltCluster(val label: String, val sessions: Map<String, List<String>>, val note: String? = null)

@Serializable
data class SeWeek(
    val week: Int,
    val circuits: NumRange,
    val reps: NumRange,
    val kind: String = "normal",
    @SerialName("counts_toward_block") val countsTowardBlock: Boolean = true,
)

@Serializable
data class Prescription(
    val by: List<String> = emptyList(), // minutes | distance_mi | distance_km | rounds | reps
    val minutes: NumRange? = null,
    val rounds: NumRange? = null,
    @SerialName("distance_km") val distanceKm: NumRange? = null,
)

@Serializable
data class Structure(val type: String, val steps: List<String> = emptyList()) // continuous | repeats | intervals | circuit | ladder | custom

@Serializable
data class Intensity(
    val rpe: NumRange? = null,
    @SerialName("hr_pct_max") val hrPctMax: NumRange? = null,
    val cue: String? = null,
)

@Serializable
data class Budget(
    @SerialName("low_intensity_minutes_per_week") val lowIntensityMinutesPerWeek: NumRange? = null,
    @SerialName("session_min_minutes") val sessionMinMinutes: Int? = null,
    @SerialName("high_intensity_per_week") val highIntensityPerWeek: NumRange? = null,
    @SerialName("high_intensity_every_n_weeks") val highIntensityEveryNWeeks: Int? = null,
    @SerialName("sessions_per_week") val sessionsPerWeek: NumRange? = null,
)

@Serializable
data class WeekAdjustment(
    val `when`: String, // week_has_high_intensity | week_without_high_intensity
    @SerialName("low_intensity_minutes_per_week") val lowIntensityMinutesPerWeek: NumRange,
)

@Serializable
data class LayoutDay(val day: Int, val kind: String, val minutes: NumRange? = null, val note: String? = null) // lic | hic | wc | rest | strength

@Serializable
data class ProtocolVariant(
    val key: String,
    val title: String,
    val strength: String? = null, // strength module key paired by this variant
    val budget: Budget? = null,
    val note: String? = null,
)

@Serializable
data class Suggested(val lic: List<String> = emptyList(), val hic: List<String> = emptyList(), val wc: List<String> = emptyList())

@Serializable
data class ChooseFrom(@SerialName("choose_from") val chooseFrom: List<String>, val optional: Boolean = false)

@Serializable
data class IntegrationRule(val rule: String, val note: String, val params: JsonObject? = null)

@Serializable
data class Cell(val ref: String, val params: JsonObject? = null, val label: String? = null, val items: List<ItemDto>? = null)

@Serializable
data class GridDay(val day: Int, val cells: List<Cell> = emptyList())

@Serializable
data class GridWeek(
    val week: Int,
    val kind: String = "normal",
    @SerialName("counts_toward_block") val countsTowardBlock: Boolean = true,
    val days: List<GridDay> = emptyList(),
)

@Serializable
data class SystemBlock(
    val key: String,
    val label: String,
    @SerialName("strength_domain") val strengthDomain: String? = null,
    @SerialName("conditioning_label") val conditioningLabel: String? = null,
    val composition: String? = null,
    val weeks: NumRange,
    val suggested: Suggested? = null,
    val external: String? = null, // names a book/program not defined here (e.g. a hypertrophy program)
    val note: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class Definition {
    abstract val schemaVersion: Int
    abstract val key: String
    abstract val version: Int
    abstract val title: String
    abstract val sourceRef: String?
    abstract val notes: List<String>
}

@Serializable
@SerialName("strength_module")
data class StrengthModuleDef(
    @SerialName("schema_version") override val schemaVersion: Int = 1,
    override val key: String,
    override val version: Int = 1,
    override val title: String,
    @SerialName("source_ref") override val sourceRef: String? = null,
    override val notes: List<String> = emptyList(),
    val family: String,
    val domain: String, // max_strength | hypertrophy | power | strength_endurance
    @SerialName("sessions_per_week") val sessionsPerWeek: Int,
    @SerialName("day_positions") val dayPositions: List<Int>,
    val slots: List<SlotDef>,
    val sessions: List<SessionDef>,
    val variants: List<VariantDef>,
    @SerialName("block_lengths") val blockLengths: List<Int> = listOf(3, 6),
    val supplemental: List<SuppRule> = emptyList(),
    val options: List<OptionDef> = emptyList(),
    val peak: PeakDef? = null,
    val progression: ProgressionDef? = null,
    @SerialName("rest_s") val restS: NumRange? = null,
    @SerialName("scheduling_rules") val schedulingRules: List<String> = emptyList(),
    @SerialName("alt_clusters") val altClusters: List<AltCluster> = emptyList(),
    @SerialName("third_session") val thirdSession: JsonObject? = null,
    @SerialName("compatible_conditioning") val compatibleConditioning: List<String> = emptyList(),
) : Definition()

@Serializable
@SerialName("se_module")
data class SeModuleDef(
    @SerialName("schema_version") override val schemaVersion: Int = 1,
    override val key: String,
    override val version: Int = 1,
    override val title: String,
    @SerialName("source_ref") override val sourceRef: String? = null,
    override val notes: List<String> = emptyList(),
    @SerialName("sessions_per_week") val sessionsPerWeek: Int,
    @SerialName("day_positions") val dayPositions: List<Int>,
    @SerialName("cluster_size") val clusterSize: NumRange,
    val weeks: List<SeWeek>,
    @SerialName("load_pct_1rm") val loadPct1rm: NumRange? = null,
    @SerialName("vest_pct_bodyweight") val vestPctBodyweight: NumRange? = null,
    val finisher: String? = null,
    @SerialName("rest_between_sets_s") val restBetweenSetsS: NumRange? = null,
    @SerialName("rest_between_circuits_s") val restBetweenCircuitsS: NumRange? = null,
    val options: List<OptionDef> = emptyList(),
    @SerialName("sample_clusters") val sampleClusters: List<AltCluster> = emptyList(),
    @SerialName("compatible_conditioning") val compatibleConditioning: List<String> = emptyList(),
) : Definition()

@Serializable
@SerialName("conditioning_session")
data class ConditioningSessionDef(
    @SerialName("schema_version") override val schemaVersion: Int = 1,
    override val key: String,
    override val version: Int = 1,
    override val title: String,
    @SerialName("source_ref") override val sourceRef: String? = null,
    override val notes: List<String> = emptyList(),
    val category: String, // lic | hic | wc | power_hic | core
    val domains: List<String>,
    val aliases: List<String> = emptyList(),
    val prescription: Prescription,
    val structure: Structure,
    val intensity: Intensity? = null,
    val modes: List<String> = emptyList(),
    @SerialName("doubles_as_wc") val doublesAsWc: Boolean = false,
) : Definition()

@Serializable
@SerialName("conditioning_protocol")
data class ConditioningProtocolDef(
    @SerialName("schema_version") override val schemaVersion: Int = 1,
    override val key: String,
    override val version: Int = 1,
    override val title: String,
    @SerialName("source_ref") override val sourceRef: String? = null,
    override val notes: List<String> = emptyList(),
    @SerialName("protocol_type") val protocolType: String, // polarized | work_capacity | ldp
    @SerialName("oa_type") val oaType: String? = null,     // black | green | blue
    val weeks: NumRange? = null,
    val budget: Budget? = null,
    @SerialName("week_adjustments") val weekAdjustments: List<WeekAdjustment> = emptyList(),
    @SerialName("default_layout") val defaultLayout: List<LayoutDay> = emptyList(),
    val suggested: Suggested = Suggested(),
    val rotation: List<String> = emptyList(),
    val variants: List<ProtocolVariant> = emptyList(),
    @SerialName("pairs_well_with") val pairsWellWith: List<String> = emptyList(),
    @SerialName("min_session_note") val minSessionNote: String? = null,
) : Definition()

@Serializable
@SerialName("composition")
data class CompositionDef(
    @SerialName("schema_version") override val schemaVersion: Int = 1,
    override val key: String,
    override val version: Int = 1,
    override val title: String,
    @SerialName("source_ref") override val sourceRef: String? = null,
    override val notes: List<String> = emptyList(),
    val strength: ChooseFrom,
    val conditioning: ChooseFrom,
    val weeks: NumRange,
    val integration: List<IntegrationRule> = emptyList(),
    @SerialName("domains_from_components") val domainsFromComponents: Boolean = true,
) : Definition()

@Serializable
@SerialName("template")
data class TemplateDef(
    @SerialName("schema_version") override val schemaVersion: Int = 1,
    override val key: String,
    override val version: Int = 1,
    override val title: String,
    @SerialName("source_ref") override val sourceRef: String? = null,
    override val notes: List<String> = emptyList(),
    val weeks: Int,
    val domains: List<String>,
    val grid: List<GridWeek>,
    val budget: Budget? = null,
    val benchmark: JsonObject? = null,
) : Definition()

@Serializable
@SerialName("system")
data class SystemDef(
    @SerialName("schema_version") override val schemaVersion: Int = 1,
    override val key: String,
    override val version: Int = 1,
    override val title: String,
    @SerialName("source_ref") override val sourceRef: String? = null,
    override val notes: List<String> = emptyList(),
    val subtype: String, // cycle | perpetual
    @SerialName("oa_type") val oaType: String? = null,
    val blocks: List<SystemBlock> = emptyList(),
    val repeat: Boolean = false,
    val baseline: String? = null,
    val detours: List<String> = emptyList(),
) : Definition()

private val strictJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    encodeDefaults = false
    explicitNulls = false
}

fun parseDefinition(json: String): Definition = strictJson.decodeFromString(Definition.serializer(), json)

fun encodeDefinition(def: Definition): String = strictJson.encodeToString(Definition.serializer(), def)
