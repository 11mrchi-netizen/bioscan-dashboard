package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.AthleteMaxRow
import com.bioscan.fieldterminal.data.model.IdRow
import com.bioscan.fieldterminal.data.model.LoggedStrengthSessionRow
import com.bioscan.fieldterminal.data.model.NewAthleteMaxRow
import com.bioscan.fieldterminal.data.model.NewBlockDomainRow
import com.bioscan.fieldterminal.data.model.NewPlannedSessionRow
import com.bioscan.fieldterminal.data.model.NewTrainingBlockRow
import com.bioscan.fieldterminal.data.model.NewTrainingDefinitionRow
import com.bioscan.fieldterminal.data.model.GeneratedBlockRow
import com.bioscan.fieldterminal.data.model.TrainingDefinitionRow
import com.bioscan.fieldterminal.data.model.UpcomingSessionRow
import com.bioscan.fieldterminal.data.model.TrainingSettingsRow
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
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.OffsetDateTime

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

private val json = Json { ignoreUnknownKeys = true }

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

    suspend fun loadGeneratedBlocks(): List<GeneratedBlockRow> =
        supabase.postgrest.from("training_blocks")
            .select(columns = Columns.list("id,name,start_date,end_date,status")) {
                filter { eq("origin", "generated"); neq("status", "abandoned") }
                order("start_date", Order.DESCENDING)
            }.decodeList<GeneratedBlockRow>()

    suspend fun loadUpcoming(from: LocalDate, limit: Int = 14): List<UpcomingSessionRow> =
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
            runCatching { supabase.postgrest.from("training_blocks").delete { filter { eq("id", blockId) } } }
            throw t
        }
        return blockId
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
