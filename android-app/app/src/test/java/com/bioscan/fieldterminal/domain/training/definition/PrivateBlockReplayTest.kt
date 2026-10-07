package com.bioscan.fieldterminal.domain.training.definition

import com.bioscan.fieldterminal.domain.training.Rounding
import com.bioscan.fieldterminal.domain.training.loadableFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

// Replays logged sessions against the user's PRIVATE definitions. Skipped unless
// TB_DEFS_DIR (definition JSON) and TB_LOGS (a JSON array of logged slot records) are set.
// Record: {date, module, variant, week, session, slot, max_kg, percent_base: "total"|"added",
//          sets: [{reps, weight_kg, percent}]}. Hard failures: no such prescription, wrong percent,
// set count outside the prescribed range, total load not the plate-rounded target. Soft
// findings (reps differing from the table, added-load tolerance) go into the report only.
class PrivateBlockReplayTest {
    private val bar = 20.0
    private val plates = listOf(1.25, 2.5, 5.0, 10.0, 15.0, 20.0)

    @Test
    fun loggedSessionsMatchTheDefinitions() {
        val dir = System.getenv("TB_DEFS_DIR")?.let(::File)
        val logs = System.getenv("TB_LOGS")?.let(::File)
        assumeTrue("TB_DEFS_DIR/TB_LOGS not set", dir != null && dir.isDirectory && logs != null && logs.isFile)
        val modules = dir!!.listFiles { f -> f.extension == "json" }!!.map { parseDefinition(it.readText()) }.filterIsInstance<StrengthModuleDef>().associateBy { it.key }
        val records = Json.parseToJsonElement(logs!!.readText()).jsonArray
        val hard = mutableListOf<String>()
        val soft = mutableListOf<String>()
        for (rec in records) {
            val o = rec.jsonObject
            val tag = "${o.s("date")} ${o.s("module")} wk${o.s("week")} ${o.s("session")} ${o.s("slot")}"
            val m = modules[o.s("module")] ?: run { hard += "$tag: unknown module"; null } ?: continue
            val week = m.variants.firstOrNull { it.key == o.s("variant") }?.weeks?.firstOrNull { it.week == o["week"]!!.jsonPrimitive.int }
            val item = week?.sessions?.firstOrNull { it.session == o.s("session") }?.items?.firstOrNull { it.slot == o.s("slot") }
            if (item == null) { hard += "$tag: no such prescription"; continue }
            val sets = (o["sets"] as JsonArray).map { it.jsonObject }
            val wantPct = item.load.value
            sets.filter { it["percent"] != null }.forEach { if (wantPct == null || abs(it["percent"]!!.jsonPrimitive.double - wantPct) > 0.01) hard += "$tag: logged ${it["percent"]}% but prescription is $wantPct%" }
            val n = sets.size.toDouble()
            item.sets?.let { if (n < it.min || n > it.max) hard += "$tag: $n sets outside ${it.min}-${it.max}" }
            item.reps?.let { r -> sets.map { it["reps"]!!.jsonPrimitive.double }.filter { it < r.min || it > r.max }.distinct().forEach { soft += "$tag: logged $it reps, table says ${r.min}-${r.max}" } }
            val max = o["max_kg"]!!.jsonPrimitive.double
            val target = max * (wantPct ?: 0.0) / 100.0
            sets.map { it["weight_kg"]!!.jsonPrimitive.double }.distinct().forEach { w ->
                if (o.s("percent_base") == "added") {
                    if (abs(w - target) > 1.0) soft += "$tag: added load $w vs target ${"%.1f".format(target)}"
                } else {
                    val want = loadableFor(target, bar, plates, Rounding.Nearest).totalKg
                    if (abs(w - want) > 0.001) hard += "$tag: loaded $w, plate-rounded target is $want"
                }
            }
        }
        File(dir.parentFile, "replay.txt").writeText("${records.size} records\nHARD:\n" + hard.joinToString("\n") + "\nSOFT:\n" + soft.joinToString("\n"))
        assertTrue("replay mismatches:\n" + hard.joinToString("\n"), hard.isEmpty())
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
}
