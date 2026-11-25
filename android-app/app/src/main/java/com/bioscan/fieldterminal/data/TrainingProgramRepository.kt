package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.training.generate.MaxEntry

import com.bioscan.fieldterminal.domain.training.generate.GenEquipment

import com.bioscan.fieldterminal.domain.training.generate.Accessory

import com.bioscan.fieldterminal.domain.training.generate.liftJson

import com.bioscan.fieldterminal.domain.training.generate.accessoryItemsFor

import com.bioscan.fieldterminal.domain.training.generate.accessoriesFor

import com.bioscan.fieldterminal.domain.training.generate.accessoriesJson

import com.bioscan.fieldterminal.domain.training.generate.accessoriesFromJson

import com.bioscan.fieldterminal.data.model.AthleteMaxRow
import com.bioscan.fieldterminal.data.model.BlockNameRow
import com.bioscan.fieldterminal.data.model.GeneratedBlockRow
import com.bioscan.fieldterminal.data.model.IdRow
import com.bioscan.fieldterminal.data.model.LoggedStrengthSessionRow
import com.bioscan.fieldterminal.data.model.NewAthleteMaxRow
import com.bioscan.fieldterminal.data.model.NewBlockDomainRow
import com.bioscan.fieldterminal.data.model.NewPlannedSessionRow
import com.bioscan.fieldterminal.data.model.NewTrainingBlockRow
import com.bioscan.fieldterminal.data.model.NewTrainingDefinitionRow
import com.bioscan.fieldterminal.data.model.PlannedForSessionRow
import com.bioscan.fieldterminal.data.model.TrainingDefinitionRow
import com.bioscan.fieldterminal.data.model.TrainingSettingsRow
import com.bioscan.fieldterminal.data.model.UpcomingSessionRow
import com.bioscan.fieldterminal.data.model.ZoneMethodRow
import com.bioscan.fieldterminal.domain.LenientJson
import com.bioscan.fieldterminal.domain.ZoneMethod
import com.bioscan.fieldterminal.domain.training.DatedSet
import com.bioscan.fieldterminal.domain.training.RecordedMax
import com.bioscan.fieldterminal.domain.training.ResolvedMax
import com.bioscan.fieldterminal.domain.training.definition.CompositionDef
import com.bioscan.fieldterminal.domain.training.definition.ConditioningProtocolDef
import com.bioscan.fieldterminal.domain.training.definition.ConditioningSessionDef
import com.bioscan.fieldterminal.domain.training.definition.Definition
import com.bioscan.fieldterminal.domain.training.definition.SeModuleDef
import com.bioscan.fieldterminal.domain.training.definition.StrengthModuleDef
import com.bioscan.fieldterminal.domain.training.definition.SystemDef
import com.bioscan.fieldterminal.domain.training.definition.TemplateDef
import com.bioscan.fieldterminal.domain.training.definition.encodeDefinition
import com.bioscan.fieldterminal.domain.training.definition.parseDefinition
import com.bioscan.fieldterminal.domain.training.definition.validateDefinition
import com.bioscan.fieldterminal.domain.training.definition.validateSet
import com.bioscan.fieldterminal.domain.training.generate.GeneratedBlock
import com.bioscan.fieldterminal.domain.training.generate.deriveBlockDomains
import com.bioscan.fieldterminal.domain.training.generate.prescriptionJson
import com.bioscan.fieldterminal.domain.training.movementKey
import com.bioscan.fieldterminal.domain.training.resolveMaxes
import com.bioscan.fieldterminal.domain.training.generate.MaxStep
import com.bioscan.fieldterminal.domain.training.generate.ModuleChoice
import com.bioscan.fieldterminal.domain.training.generate.PlannedLite
import com.bioscan.fieldterminal.domain.training.generate.endEarlyPreview
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

data class PlannedMatch(val row: PlannedForSessionRow, val blockName: String?, val linked: Boolean)

data class DefinitionCatalog(val definitions: List<Definition>, val unreadable: List<String>)

data class ImportFile(val name: String, val text: String)

data class ImportReport(val imported: Int, val rejected: List<String>, val setErrors: List<String>, val setWarnings: List<String>)

data class BlockPlan(
    val name: String,
    val template: TemplateDef?,
    val components: JsonArray,
    val definitionsUsed: List<Definition>,
    val choices: JsonObject,
    val startDate: LocalDate,
    val generated: GeneratedBlock,
)

private val json = LenientJson

private val STRENGTH_DOMAINS = setOf("max_strength", "hypertrophy", "strength_endurance", "power")

// DAV-345. Definitions, maxes, settings and block creation for the programming engine. The
// definitions are the user's private rows (RLS); nothing here ships a program.
class TrainingProgramRepository(private val supabase: SupabaseClient) {

    suspend fun loadCatalog(): DefinitionCatalog {
        val rows = supabase.postgrest.from("training_definitions")
            .select(columns = Columns.list("id,methodology,kind,key,version,title,body,status")) { filter { neq("status", "retired") } }
            .decodeList<TrainingDefinitionRow>()
        val bad = mutableListOf<String>()
        val defs = rows.mapNotNull { r ->
            runCatching { parseDefinition(r.body.toString()) }.getOrElse { bad += "${r.key} v${r.version}: ${it.message?.take(120)}"; null }
        }
        return DefinitionCatalog(defs, bad)
    }

    // Import definition files the user provides. Each file must parse strictly and pass its own
    // structural checks; the set is then cross-checked and the findings reported, not enforced.
    suspend fun importDefinitions(files: List<ImportFile>): ImportReport {
        val rejected = mutableListOf<String>()
        val ok = mutableListOf<Definition>()
        for (f in files) {
            val def = runCatching { parseDefinition(f.text) }.getOrElse { rejected += "${f.name}: ${it.message?.take(160)}"; null } ?: continue
            val errors = validateDefinition(def)
            if (errors.isNotEmpty()) { rejected += "${f.name}: ${errors.first()}"; continue }
            ok += def
        }
        val report = validateSet(ok)
        ok.chunked(25).forEach { chunk ->
            supabase.postgrest.from("training_definitions").upsert(chunk.map { it.toRow() }) {
                onConflict = "user_id,methodology,kind,key,version"
            }
        }
        return ImportReport(ok.size, rejected, report.errors, report.warnings)
    }

    // Kept on disk with the read cache (data/ReadCache.kt): the page opens from the last copy while this loads.
    suspend fun loadGeneratedBlocks(): List<GeneratedBlockRow> =
        ReadCache.get("program.blocks", tables = setOf("training_blocks", "planned_sessions"), serializer = ListSerializer(GeneratedBlockRow.serializer())) { fetchLoadGeneratedBlocks() }

    // Generated blocks, plus the block under way when it was imported or recorded elsewhere: it can end early too.
    private suspend fun fetchLoadGeneratedBlocks(): List<GeneratedBlockRow> {
        val today = LocalDate.now().toString()
        return supabase.postgrest.from("training_blocks")
            .select(columns = Columns.list("id,name,start_date,end_date,status,origin,cycle_id")) {
                filter { neq("status", "abandoned") }
                order("start_date", Order.DESCENDING)
            }.decodeList<GeneratedBlockRow>()
            .filter { it.origin == "generated" || (it.status == "active" || it.status == "scheduled") && it.endDate >= today }
    }

    // Kept on disk with the read cache (data/ReadCache.kt): the page opens from the last copy while this loads.
    suspend fun loadUpcoming(from: LocalDate, limit: Int = 14): List<UpcomingSessionRow> =
        ReadCache.get("program.upcoming.$from.$limit", tables = setOf("planned_sessions", "training_blocks"), serializer = ListSerializer(UpcomingSessionRow.serializer())) { fetchLoadUpcoming(from, limit) }

    private suspend fun fetchLoadUpcoming(from: LocalDate, limit: Int = 14): List<UpcomingSessionRow> =
        supabase.postgrest.from("planned_sessions")
            .select(columns = Columns.list("id,block_id,scheduled_date,week_index,title,domain,status")) {
                filter { gte("scheduled_date", from.toString()); eq("status", "planned") }
                order("scheduled_date", Order.ASCENDING)
                order("slot_in_day", Order.ASCENDING)
                limit(limit.toLong())
            }.decodeList<UpcomingSessionRow>()

    suspend fun deleteBlock(id: Long) {
        supabase.postgrest.from("training_blocks").delete { filter { eq("id", id) } }
    }

    suspend fun saveSettings(s: TrainingSettingsRow) {
        supabase.postgrest.from("training_settings").upsert(buildJsonObject {
            put("plates_kg", buildJsonArray { s.platesKg.forEach { add(JsonPrimitive(it)) } })
            put("default_start_time", s.defaultStartTime)
            put("default_duration_min", s.defaultDurationMin)
            put("second_start_time", s.secondStartTime)
            put("second_duration_min", s.secondDurationMin)
            put("weighted_percent_base", s.weightedPercentBase)
            put("updated_at", Instant.now().toString())
        }) { onConflict = "user_id" }
    }

    // The default heart-rate zone method (20261021_hr_zone_method.sql), read and written on its own: a database without
    // the column yet still opens the planner and its settings, and the zones fall back to AUTO.
    suspend fun loadZoneMethod(): ZoneMethod = ReadCache.kept("training.zone_method", tables = setOf("training_settings")) {
        catching {
            supabase.postgrest.from("training_settings")
                .select(columns = Columns.list("hr_zone_method"))
                .decodeList<ZoneMethodRow>().firstOrNull()?.method
        }.getOrNull().let(ZoneMethod::of)
    }

    suspend fun saveZoneMethod(method: ZoneMethod) {
        supabase.postgrest.from("training_settings").upsert(buildJsonObject {
            put("hr_zone_method", method.key)
            put("updated_at", Instant.now().toString())
        }) { onConflict = "user_id" }
    }

    // The planned session a logged workout belongs to: the one already linked to it, else the
    // unlinked plan on the same day whose kind fits (strength with strength, the rest with the rest).
    suspend fun loadPlannedFor(sessionId: Long, date: LocalDate, strength: Boolean): PlannedMatch? {
        val cols = "id,block_id,week_index,title,domain,scheduled_date,status,exercise_session_id,match_status,prescription"
        val table = supabase.postgrest.from("planned_sessions")
        val linked = table.select(columns = Columns.list(cols)) { filter { eq("exercise_session_id", sessionId); neq("status", "superseded") } }.decodeList<PlannedForSessionRow>().firstOrNull()
        val row = linked ?: table.select(columns = Columns.list(cols)) {
            filter { eq("scheduled_date", date.toString()); eq("status", "planned"); exact("exercise_session_id", null) }
            order("slot_in_day", Order.ASCENDING)
        }.decodeList<PlannedForSessionRow>().firstOrNull { (it.domain in STRENGTH_DOMAINS) == strength } ?: return null
        val name = supabase.postgrest.from("training_blocks").select(columns = Columns.list("name")) { filter { eq("id", row.blockId) } }.decodeList<BlockNameRow>().firstOrNull()?.name
        return PlannedMatch(row, name, linked != null)
    }

    suspend fun linkPlanned(plannedId: Long, sessionId: Long) {
        supabase.postgrest.from("planned_sessions").update({
            set("exercise_session_id", sessionId)
            set("match_status", "manual")
            set("status", "confirmed")
            set("confirmed_at", Instant.now().toString())
        }) { filter { eq("id", plannedId) } }
    }

    suspend fun loadSettings(): TrainingSettingsRow =
        supabase.postgrest.from("training_settings")
            .select(columns = Columns.list("plates_kg,default_start_time,default_duration_min,second_start_time,second_duration_min,weighted_percent_base"))
            .decodeList<TrainingSettingsRow>().firstOrNull() ?: TrainingSettingsRow()

    // Maxes the setup screen offers, keyed by movement key: recorded rows compete with estimates
    // from the last 120 days of logged strength sessions.
    suspend fun loadMaxes(today: LocalDate = LocalDate.now()): Map<String, ResolvedMax> {
        val recorded = supabase.postgrest.from("athlete_maxes")
            .select(columns = Columns.list("id,exercise_key,exercise_name,kind,value,unit,as_of,source")) { order("as_of", Order.DESCENDING) }
            .decodeList<AthleteMaxRow>()
            .map { RecordedMax(it.exerciseKey, it.exerciseName, it.kind, it.value, LocalDate.parse(it.asOf), it.source) }
        val since = today.minusDays(120)
        val sessions = supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("start_time,details")) {
                filter { eq("type", "strength"); gte("start_time", since.toString()) }
            }
            .decodeList<LoggedStrengthSessionRow>()
        val names = mutableMapOf<String, String>()
        val logged = mutableMapOf<String, MutableList<DatedSet>>()
        for (s in sessions) {
            val date = runCatching { OffsetDateTime.parse(s.startTime).toLocalDate() }.getOrNull() ?: continue
            val exercises = (s.details as? JsonObject)?.get("exercises") as? JsonArray ?: continue
            for (ex in exercises) {
                val o = ex as? JsonObject ?: continue
                val name = o["name"]?.jsonPrimitive?.content ?: continue
                val key = movementKey(name)
                names.putIfAbsent(key, name)
                for (set in (o["sets"] as? JsonArray).orEmpty()) {
                    val so = set as? JsonObject ?: continue
                    val reps = so["reps"]?.jsonPrimitive?.intOrNull ?: continue
                    val kg = so["weight_kg"]?.jsonPrimitive?.doubleOrNull ?: continue
                    logged.getOrPut(key) { mutableListOf() } += DatedSet(date, reps, kg, so["percent_1rm"]?.jsonPrimitive?.doubleOrNull)
                }
            }
        }
        recorded.forEach { names.putIfAbsent(it.movementKey, it.name) }
        return resolveMaxes(recorded, logged, names, today)
    }

    suspend fun saveMax(name: String, kind: String, value: Double, unit: String, source: String, asOf: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("athlete_maxes").insert(
            NewAthleteMaxRow(movementKey(name), name, kind, value, unit, asOf.toString(), source, buildJsonObject { put("entered_in", "block setup") })
        )
    }

    // Creates the block, its domains and every planned session. A failure part-way removes the
    // block (planned sessions and domains cascade) so a half-built plan never survives.
    suspend fun createBlock(plan: BlockPlan): Long {
        val g = plan.generated
        val snapshot = buildJsonObject {
            plan.template?.let { put("template", json.parseToJsonElement(encodeDefinition(it))) }
            put("definitions", buildJsonObject { plan.definitionsUsed.forEach { put(it.key, json.parseToJsonElement(encodeDefinition(it))) } })
        }
        val refs = buildJsonArray {
            plan.definitionsUsed.forEach { add(buildJsonObject { put("kind", it.kindName()); put("key", it.key); put("version", it.version) }) }
        }
        val blockId = supabase.postgrest.from("training_blocks").insert(
            NewTrainingBlockRow(
                name = plan.name, templateKey = plan.template?.key, templateVersion = plan.template?.version,
                components = plan.components, definitionRefs = refs, definitionSnapshot = snapshot, choices = plan.choices,
                startDate = plan.startDate.toString(), endDate = g.endDate.toString(),
            )
        ) { select(Columns.list("id")) }.decodeSingle<IdRow>().id
        try {
            val domains = deriveBlockDomains(g.sessions).take(6)
            if (domains.isNotEmpty()) supabase.postgrest.from("training_block_domains").insert(domains.map { NewBlockDomainRow(blockId, it.domain, it.role) })
            g.sessions.chunked(100).forEach { chunk ->
                supabase.postgrest.from("planned_sessions").insert(chunk.map { s ->
                    NewPlannedSessionRow(
                        blockId = blockId, sequenceNo = s.sequenceNo, weekIndex = s.weekIndex, daySlot = s.daySlot, slotInDay = s.slotInDay,
                        domain = s.domain, workKind = s.workKind, moduleRef = s.moduleRef, title = s.title, prescription = s.prescriptionJson(),
                        originalDate = s.date.toString(), scheduledDate = s.date.toString(), startTime = s.startTime?.toString(), durationMin = s.durationMin,
                    )
                })
            }
        } catch (t: Throwable) {
            catching { supabase.postgrest.from("training_blocks").delete { filter { eq("id", blockId) } } }
            throw t
        }
        return blockId
    }
}


/** A block as ending it early needs it: its row, its sessions, its strength program and choices, and its review. */
data class BlockForEnding(
    val row: GeneratedBlockRow,
    val sessions: List<PlannedLite>,
    val module: StrengthModuleDef?,
    val choice: ModuleChoice,
    val review: JsonObject?,
)

@kotlinx.serialization.Serializable
private data class BlockEndRow(
    val id: Long,
    val name: String,
    @kotlinx.serialization.SerialName("start_date") val startDate: String,
    @kotlinx.serialization.SerialName("end_date") val endDate: String,
    val status: String = "scheduled",
    val origin: String = "generated",
    @kotlinx.serialization.SerialName("cycle_id") val cycleId: Long? = null,
    val components: JsonElement? = null,
    val choices: JsonElement? = null,
    @kotlinx.serialization.SerialName("definition_snapshot") val definitionSnapshot: JsonElement? = null,
    val review: JsonElement? = null,
)

@kotlinx.serialization.Serializable
private data class EndSessionRow(
    val id: Long,
    @kotlinx.serialization.SerialName("scheduled_date") val scheduledDate: String,
    @kotlinx.serialization.SerialName("week_index") val weekIndex: Int = 0,
    val status: String = "planned",
    val prescription: JsonElement? = null,
)

/** The heaviest % of 1RM among a frozen prescription's lifts, or null. */
internal fun topPct(prescription: JsonElement?): Double? = (prescription as? JsonArray)?.mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    if (o["type"]?.jsonPrimitive?.contentOrNull != "lift") null else (o["load"] as? JsonObject)?.get("pct")?.jsonPrimitive?.doubleOrNull
}?.maxOrNull()

suspend fun loadBlockForEnding(supabase: SupabaseClient, id: Long): BlockForEnding {
    val b = supabase.postgrest.from("training_blocks")
        .select(columns = Columns.list("id,name,start_date,end_date,status,origin,cycle_id,components,choices,definition_snapshot,review")) { filter { eq("id", id) } }
        .decodeSingle<BlockEndRow>()
    val sessions = supabase.postgrest.from("planned_sessions")
        .select(columns = Columns.list("id,scheduled_date,week_index,status,prescription")) { filter { eq("block_id", id); neq("status", "superseded") } }
        .decodeList<EndSessionRow>()
        .map { PlannedLite(it.id, LocalDate.parse(it.scheduledDate), it.weekIndex, it.status, topPct(it.prescription)) }
    val strengthKey = (b.components as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "strength" }
        ?.let { (it["ref"] as? JsonObject)?.get("key")?.jsonPrimitive?.contentOrNull }
    val module = strengthKey?.let { k ->
        ((b.definitionSnapshot as? JsonObject)?.get("definitions") as? JsonObject)?.get(k)
            ?.let { runCatching { parseDefinition(it.toString()) as? StrengthModuleDef }.getOrNull() }
    }
    val cluster = strengthKey?.let { ((b.choices as? JsonObject)?.get("clusters") as? JsonObject)?.get(it) as? JsonObject }
    val choice = ModuleChoice(
        variant = cluster?.get("variant")?.jsonPrimitive?.contentOrNull,
        blockLength = cluster?.get("block_length")?.jsonPrimitive?.intOrNull,
        exercises = cluster?.filterKeys { it != "variant" && it != "block_length" }
            ?.mapNotNull { (k, v) -> (v as? JsonArray)?.let { a -> k to a.mapNotNull { it.jsonPrimitive.contentOrNull } } }?.toMap().orEmpty(),
        accessories = accessoriesFromJson(cluster?.get("accessories")),
    )
    return BlockForEnding(GeneratedBlockRow(b.id, b.name, b.startDate, b.endDate, b.status, b.origin, b.cycleId), sessions, module, choice, b.review as? JsonObject)
}

/**
 * Ends [block] on [lastDay]: its sessions still planned after that day are superseded (kept, never deleted), the block
 * ends that day and is completed, and the review records the stop. [steps] are the maxes the lifter chose to step up
 * (empty keeps them, the default: the cycle was not completed).
 */
suspend fun endBlockEarly(supabase: SupabaseClient, block: BlockForEnding, lastDay: LocalDate, steps: List<MaxStep>) {
    val dropped = endEarlyPreview(block.sessions, lastDay)
    supabase.postgrest.from("planned_sessions").update({ set("status", "superseded") }) {
        filter { eq("block_id", block.row.id); eq("status", "planned"); gt("scheduled_date", lastDay.toString()) }
    }
    val review = buildJsonObject {
        block.review?.forEach { (k, v) -> put(k, v) }
        put("ended_early", buildJsonObject {
            put("last_day", lastDay.toString()); put("original_end_date", block.row.endDate); put("on", LocalDate.now().toString())
            put("superseded_sessions", dropped.dropped); put("maxes_stepped_up", steps.isNotEmpty())
        })
    }
    supabase.postgrest.from("training_blocks").update({
        set("end_date", lastDay.toString()); set("status", "completed"); set("review", review); set("updated_at", java.time.Instant.now().toString())
    }) { filter { eq("id", block.row.id) } }
    // An imported or recorded block lives on as a training_cycles row (the CURRENT BLOCK card reads it): it ends too.
    block.row.cycleId?.let { cid ->
        supabase.postgrest.from("training_cycles").update({ set("end_date", lastDay.toString()) }) { filter { eq("id", cid) } }
    }
    for (st in steps) {
        supabase.postgrest.from("athlete_maxes").insert(
            NewAthleteMaxRow(movementKey(st.exercise), st.exercise, "1rm", st.toKg, "kg", LocalDate.now().toString(), "progression",
                buildJsonObject { put("from_kg", st.fromKg); put("reason", "block ended early, stepped up by choice") }, blockId = block.row.id),
        )
    }
}

private fun Definition.kindName(): String = when (this) {
    is StrengthModuleDef -> "strength_module"
    is SeModuleDef -> "se_module"
    is ConditioningSessionDef -> "conditioning_session"
    is ConditioningProtocolDef -> "conditioning_protocol"
    is CompositionDef -> "composition"
    is TemplateDef -> "template"
    is SystemDef -> "system"
}

private fun Definition.toRow(): NewTrainingDefinitionRow = NewTrainingDefinitionRow(
    methodology = key.substringBefore('.'), kind = kindName(), key = key, version = version, schemaVersion = schemaVersion,
    title = title, body = json.parseToJsonElement(encodeDefinition(this)), sourceRef = sourceRef,
)

@kotlinx.serialization.Serializable
private data class ChoicesRow(val choices: JsonElement? = null)

@kotlinx.serialization.Serializable
private data class AccessorySessionRow(
    val id: Long,
    val title: String,
    @kotlinx.serialization.SerialName("work_kind") val workKind: String = "progression",
    val prescription: JsonElement? = null,
)

/**
 * Replaces the accessory work of a block under way: its choices, and the accessory lines of its strength sessions still
 * planned from [from] (earlier and confirmed sessions stay as they were). A session's own lines are never touched.
 * Returns how many sessions changed.
 */
suspend fun updateBlockAccessories(
    supabase: SupabaseClient, block: BlockForEnding, accessories: Map<String, List<Accessory>>, from: LocalDate, eq: GenEquipment, maxes: (String) -> MaxEntry?,
): Int {
    val m = block.module ?: error("This block has no strength program to add accessory work to")
    val choices = (supabase.postgrest.from("training_blocks").select(columns = Columns.list("choices")) { filter { eq("id", block.row.id) } }
        .decodeSingle<ChoicesRow>().choices as? JsonObject) ?: JsonObject(emptyMap())
    val clusters = choices["clusters"] as? JsonObject ?: JsonObject(emptyMap())
    val cluster = (clusters[m.key] as? JsonObject ?: JsonObject(emptyMap())).filterKeys { it != "accessories" } +
        (if (accessories.isEmpty()) emptyMap() else mapOf("accessories" to accessoriesJson(accessories)))
    val updated = JsonObject(choices + ("clusters" to JsonObject(clusters + (m.key to JsonObject(cluster)))))
    supabase.postgrest.from("training_blocks").update({ set("choices", updated); set("updated_at", java.time.Instant.now().toString()) }) { filter { eq("id", block.row.id) } }

    val sessions = supabase.postgrest.from("planned_sessions")
        .select(columns = Columns.list("id,title,work_kind,prescription")) {
            filter { eq("block_id", block.row.id); eq("status", "planned"); exact("exercise_session_id", null); gte("scheduled_date", from.toString()); eq("module_ref", "strength:${m.key}") }
        }.decodeList<AccessorySessionRow>()
    val byTitle = m.sessions.associateBy { "${m.title}: ${it.label}" }
    val choice = ModuleChoice(accessories = accessories)
    var changed = 0
    for (s in sessions) {
        val items = accessoryItemsFor(accessoriesFor(choice, byTitle[s.title]?.id), if (s.workKind == "deload") "deload" else "normal", eq, maxes)
        val before = (s.prescription as? JsonArray)?.toList().orEmpty()
        fun JsonElement.field(k: String) = ((this as? JsonObject)?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val kept = before.filterNot { it.field("type") == "lift" && it.field("role") == "accessory" }
        val at = kept.indexOfLast { it.field("type") == "lift" } + 1
        val after = JsonArray(kept.take(at) + items.map { it.liftJson() } + kept.drop(at))
        if (after == JsonArray(before)) continue
        // A session's prescription is immutable in the database (planned_sessions_immutable: "create a new revision"), so the change goes in as a new revision of the
        // session, the old row set aside (supabase/migrations/20261124063309_plan_edit.sql), never as an update in place.
        supabase.postgrest.rpc("revise_planned_session", buildJsonObject { put("p_id", s.id); put("p_today", from.toString()); put("p_prescription", after) })
        changed++
    }
    return changed
}
