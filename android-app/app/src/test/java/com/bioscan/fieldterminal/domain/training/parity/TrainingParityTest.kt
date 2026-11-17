package com.bioscan.fieldterminal.domain.training.parity

import com.bioscan.fieldterminal.domain.training.DatedSet
import com.bioscan.fieldterminal.domain.training.PercentBase
import com.bioscan.fieldterminal.domain.training.Rounding
import com.bioscan.fieldterminal.domain.training.definition.NumRange
import com.bioscan.fieldterminal.domain.training.definition.parseDefinition
import com.bioscan.fieldterminal.domain.training.estimateMaxFromHistory
import com.bioscan.fieldterminal.domain.training.generate.BlockBlueprint
import com.bioscan.fieldterminal.domain.training.generate.CondSlot
import com.bioscan.fieldterminal.domain.training.generate.DayPlan
import com.bioscan.fieldterminal.domain.training.generate.DefinitionIndex
import com.bioscan.fieldterminal.domain.training.generate.GenChoices
import com.bioscan.fieldterminal.domain.training.generate.GenEquipment
import com.bioscan.fieldterminal.domain.training.generate.GeneratedBlock
import com.bioscan.fieldterminal.domain.training.generate.MaxEntry
import com.bioscan.fieldterminal.domain.training.generate.ModuleChoice
import com.bioscan.fieldterminal.domain.training.generate.SlotTime
import com.bioscan.fieldterminal.domain.training.generate.WeekKind
import com.bioscan.fieldterminal.domain.training.generate.defaultTimeline
import com.bioscan.fieldterminal.domain.training.generate.deriveBlockDomains
import com.bioscan.fieldterminal.domain.training.generate.generateComposedBlock
import com.bioscan.fieldterminal.domain.training.generate.generateMergedBlock
import com.bioscan.fieldterminal.domain.training.generate.generateTemplateBlock
import com.bioscan.fieldterminal.domain.training.generate.layoutWarnings
import com.bioscan.fieldterminal.domain.training.generate.prescriptionJson
import com.bioscan.fieldterminal.domain.training.generate.resizeTimeline
import com.bioscan.fieldterminal.domain.training.generate.suggestLayout
import com.bioscan.fieldterminal.domain.training.generate.summaryLines
import com.bioscan.fieldterminal.domain.training.generate.weeksForBlocks
import com.bioscan.fieldterminal.domain.events.withTaper
import com.bioscan.fieldterminal.domain.training.loadableFor
import com.bioscan.fieldterminal.domain.training.movementKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

// Parity with the web planner (supabase/functions/_shared/training-core): the console's TypeScript port must give the
// same blocks as this engine. This test runs the shared scenarios (fixtures/scenarios.json) through the Kotlin engine and
// checks the result against fixtures/expected.json; the TypeScript test checks its own result against the same file.
// After a deliberate engine change, regenerate: FT_PARITY_WRITE=1 ./gradlew testDebugUnitTest --tests '*TrainingParityTest*'
class TrainingParityTest {
    private val dir = File("../../supabase/functions/_shared/training-core/fixtures")
    private val json = Json { prettyPrint = false }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun range(e: JsonElement?): NumRange? = when (e) {
        is JsonPrimitive -> e.doubleOrNull?.let { NumRange.of(it) }
        is JsonObject -> NumRange(e["min"]!!.jsonPrimitive.doubleOrNull!!, e["max"]!!.jsonPrimitive.doubleOrNull!!)
        else -> null
    }
    private fun rangeJson(r: NumRange?): JsonElement = r?.let { if (it.isFixed) JsonPrimitive(it.min) else buildJsonObject { put("min", it.min); put("max", it.max) } } ?: JsonNull
    private fun kind(s: String) = WeekKind.fromKey(s)

    private fun blockJson(b: GeneratedBlock): JsonObject = buildJsonObject {
        put("calendarWeeks", b.calendarWeeks); put("countedWeeks", b.countedWeeks); put("endDate", b.endDate.toString())
        put("warnings", buildJsonArray { b.warnings.forEach { add(JsonPrimitive(it)) } })
        put("progressions", buildJsonArray {
            b.progressions.forEach { p -> add(buildJsonObject { put("weekIndex", p.weekIndex); put("moduleKey", p.moduleKey); put("exercise", p.exercise); put("fromKg", p.fromKg); put("toKg", p.toKg) }) }
        })
        put("domains", buildJsonArray { deriveBlockDomains(b.sessions).forEach { d -> add(buildJsonObject { put("domain", d.domain); put("role", d.role); put("sessions", d.sessions) }) } })
        put("sessions", buildJsonArray {
            b.sessions.forEach { s ->
                add(buildJsonObject {
                    put("sequenceNo", s.sequenceNo); put("weekIndex", s.weekIndex); put("countsTowardBlock", s.countsTowardBlock); put("daySlot", s.daySlot)
                    put("slotInDay", s.slotInDay); put("date", s.date.toString()); put("startTime", s.startTime?.toString()); put("durationMin", s.durationMin)
                    put("domain", s.domain); put("workKind", s.workKind); put("moduleRef", s.moduleRef); put("title", s.title)
                    put("prescription", s.prescriptionJson())
                    put("lines", buildJsonArray { s.summaryLines().forEach { add(JsonPrimitive(it)) } })
                })
            }
        })
    }

    private fun run(): JsonObject {
        val sc = Json.parseToJsonElement(File(dir, "scenarios.json").readText()).jsonObject
        val defs = sc["definitions"]!!.jsonArray.map { parseDefinition(it.toString()) }
        val idx = DefinitionIndex(defs)
        val maxes = sc["maxes"]!!.jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            MaxEntry(o["oneRmKg"]?.jsonPrimitive?.doubleOrNull, o["trainingMaxKg"]?.jsonPrimitive?.doubleOrNull, o["maxReps"]?.jsonPrimitive?.intOrNull)
        }
        val lookup: (String) -> MaxEntry? = { maxes[it.trim().lowercase()] }
        val cases = buildJsonObject {
            for (ce in sc["cases"]!!.jsonArray) {
                val c = ce.jsonObject
                val start = LocalDate.parse(c["start"].str()!!)
                val eqo = c["equipment"]?.jsonObject
                val eq = GenEquipment(
                    barKg = eqo?.get("barKg")?.jsonPrimitive?.doubleOrNull ?: 20.0,
                    platesKg = eqo?.get("platesKg")?.jsonArray?.map { it.jsonPrimitive.doubleOrNull!! } ?: listOf(1.25, 2.5, 5.0, 10.0, 15.0, 20.0),
                    weightedBase = if (eqo?.get("weightedBase").str() == "total") PercentBase.TotalLoad else PercentBase.AddedLoad,
                )
                val choices = GenChoices(
                    startDate = start,
                    weekdays = c["weekdays"]?.jsonObject?.entries?.associate { (k, v) -> k.toInt() to DayOfWeek.of(v.jsonPrimitive.intOrNull!!) }.orEmpty(),
                    slotTimes = c["slotTimes"]?.jsonObject?.entries?.associate { (k, v) ->
                        k.toInt() to SlotTime(LocalTime.parse(v.jsonObject["start"].str()!!), v.jsonObject["durationMin"]!!.jsonPrimitive.intOrNull!!)
                    }.orEmpty(),
                    variables = c["variables"]?.jsonObject?.mapValues { it.value.str()!! }.orEmpty(),
                    modules = c["modules"]?.jsonObject?.mapValues { (_, v) ->
                        val o = v.jsonObject
                        ModuleChoice(o["variant"].str(), o["blockLength"]?.jsonPrimitive?.intOrNull, o["exercises"]?.jsonObject?.mapValues { e -> e.value.jsonArray.map { it.str()!! } }.orEmpty())
                    }.orEmpty(),
                    conditioning = c["conditioning"]?.jsonObject?.mapValues { e -> e.value.jsonArray.map { it.str()!! } }.orEmpty(),
                    weeks = c["weeks"]?.jsonPrimitive?.intOrNull,
                    deloadAfterBlock = c["deloadAfterBlock"]?.jsonPrimitive?.booleanOrNull ?: true,
                    bodyweightKg = c["bodyweightKg"]?.jsonPrimitive?.doubleOrNull,
                    conditioningPerWeek = c["conditioningPerWeek"]?.jsonPrimitive?.intOrNull,
                    projectProgression = c["projectProgression"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
                val strength = c["strength"].str()?.let { idx.strength(it) }
                val protocol = c["protocol"].str()?.let { idx.protocol(it) }
                val out = buildJsonObject {
                    when (c["kind"].str()) {
                        "template" -> put("block", blockJson(generateTemplateBlock(idx.template(c["template"].str()!!)!!, idx, choices, eq, lookup)))
                        "composed" -> put("block", blockJson(generateComposedBlock(strength, protocol, idx, choices, eq, lookup)))
                        else -> {
                            val layout = when (val l = c["layout"]) {
                                is JsonArray -> l.map { d ->
                                    val o = d.jsonObject
                                    DayPlan(DayOfWeek.of(o["weekday"]!!.jsonPrimitive.intOrNull!!), o["strengthSession"]?.jsonPrimitive?.intOrNull,
                                        o["conditioning"]?.jsonObject?.let { co -> CondSlot(co["kind"].str()!!, range(co["minutes"])) })
                                }
                                else -> suggestLayout(strength, protocol, start.dayOfWeek, choices.conditioningPerWeek)
                            }
                            val timeline = when (val t = c["timeline"]) {
                                is JsonArray -> t.map { kind(it.str()!!) }
                                else -> t!!.jsonObject["default"]!!.jsonArray.let { a -> defaultTimeline(a[0].jsonPrimitive.intOrNull!!, a[1].jsonPrimitive.intOrNull!!, a[2].jsonPrimitive.booleanOrNull!!) }
                            }
                            put("layout", buildJsonArray {
                                layout.forEach { d -> add(buildJsonObject { put("weekday", d.weekday.value); put("strengthSession", d.strengthSession); put("conditioning", d.conditioning?.kind); put("minutes", rangeJson(d.conditioning?.minutes)) }) }
                            })
                            put("layoutWarnings", buildJsonArray { layoutWarnings(layout, strength, protocol).forEach { add(JsonPrimitive(it)) } })
                            put("timeline", buildJsonArray { timeline.forEach { add(JsonPrimitive(it.key)) } })
                            put("block", blockJson(generateMergedBlock(BlockBlueprint(strength?.key, protocol?.key, layout, timeline), idx, choices, eq, lookup)))
                        }
                    }
                }
                put(c["name"].str()!!, out)
            }
        }
        val misc = sc["misc"]!!.jsonObject
        val miscOut = buildJsonObject {
            put("plates", buildJsonArray {
                misc["plates"]!!.jsonArray.forEach { p ->
                    val a = p.jsonArray
                    val r = when (a[3].str()) { "up" -> Rounding.Up; "down" -> Rounding.Down; else -> Rounding.Nearest }
                    val l = loadableFor(a[0].jsonPrimitive.doubleOrNull!!, a[1].jsonPrimitive.doubleOrNull!!, a[2].jsonArray.map { it.jsonPrimitive.doubleOrNull!! }, r)
                    add(buildJsonObject { put("total", l.totalKg); put("plates", buildJsonArray { l.platesPerSideKg.forEach { add(JsonPrimitive(it)) } }) })
                }
            })
            put("movementKeys", buildJsonArray { misc["movementKeys"]!!.jsonArray.forEach { add(JsonPrimitive(movementKey(it.str()!!))) } })
            put("timelines", buildJsonArray {
                misc["timelines"]!!.jsonArray.forEach { t ->
                    val a = t.jsonArray
                    when (a[0].str()) {
                        "default" -> add(buildJsonArray { defaultTimeline(a[1].jsonPrimitive.intOrNull!!, a[2].jsonPrimitive.intOrNull!!, a[3].jsonPrimitive.booleanOrNull!!, a[4].jsonPrimitive.booleanOrNull!!).forEach { add(JsonPrimitive(it.key)) } })
                        "resize" -> add(buildJsonArray { resizeTimeline(a[1].jsonArray.map { kind(it.str()!!) }, a[2].jsonPrimitive.intOrNull!!, a[3].jsonPrimitive.intOrNull!!).forEach { add(JsonPrimitive(it.key)) } })
                        "taper" -> add(buildJsonArray { withTaper(a[1].jsonArray.map { kind(it.str()!!) }, a[2].jsonPrimitive.intOrNull!!).forEach { add(JsonPrimitive(it.key)) } })
                        else -> add(JsonPrimitive(weeksForBlocks(a[1].jsonPrimitive.intOrNull!!, a[2].jsonPrimitive.intOrNull!!, a[3].jsonPrimitive.booleanOrNull!!, a[4].jsonPrimitive.booleanOrNull!!)))
                    }
                }
            })
            put("estimates", buildJsonArray {
                misc["estimates"]!!.jsonArray.forEach { e ->
                    val o = e.jsonObject
                    val sets = o["sets"]!!.jsonArray.map { s ->
                        val so = s.jsonObject
                        DatedSet(LocalDate.parse(so["date"].str()!!), so["reps"]!!.jsonPrimitive.intOrNull!!, so["weightKg"]!!.jsonPrimitive.doubleOrNull!!, so["percentOneRm"]?.jsonPrimitive?.doubleOrNull)
                    }
                    val est = estimateMaxFromHistory(sets, LocalDate.parse(o["today"].str()!!))
                    add(est?.let { buildJsonObject { put("kg", it.kg); put("source", it.source.db); put("asOf", it.asOf.toString()); put("basedOnSets", it.basedOnSets) } } ?: JsonNull)
                }
            })
        }
        return buildJsonObject { put("cases", cases); put("misc", miscOut) }
    }

    @Test
    fun theSharedScenariosGiveTheExpectedBlocks() {
        val actual = run()
        val file = File(dir, "expected.json")
        if (System.getenv("FT_PARITY_WRITE") != null || !file.exists()) {
            file.writeText(json.encodeToString(JsonObject.serializer(), actual) + "\n")
            return
        }
        assertEquals(Json.parseToJsonElement(file.readText()), actual)
    }
}
