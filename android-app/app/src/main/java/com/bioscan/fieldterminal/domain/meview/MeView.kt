package com.bioscan.fieldterminal.domain.meview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

// A Me tab as the web Dashboard draws it (web transition plan, phase 3, Nov 2026). The phone works the tab out and saves
// this plain, display-ready document (me_views, one row per tab); the web renders it and recomputes nothing, so both show
// the same numbers. Every value is already formatted here, as the phone shows it. Hues name a design token
// (design/tokens: "m-weight", "ft-domain-fuel", "ok"), never a raw colour. Shape: v = 1, read by
// console/src/dashboard/meView.ts — keep the two in step.

enum class Tone { OK, WARN, BAD }

sealed interface MeItem

/** A number with its label: "Fat-free mass 61.2 kg", a sparkline from [series] when given. */
data class MeTile(
    val label: String, val value: String, val unit: String? = null, val sub: String? = null, val tone: Tone? = null,
    val hue: String? = null, val series: List<Pair<LocalDate, Double>> = emptyList(), val info: String? = null,
) : MeItem

/** A line of a list: an injury, a draw, a record, a measurement. [region] places it on the 3D body (Me › Body). */
data class MeRow(
    val label: String, val value: String? = null, val sub: String? = null, val tone: Tone? = null, val state: String? = null,
    val region: String? = null, val side: String? = null,
) : MeItem

data class MeSeries(val name: String, val hue: String, val points: List<Pair<LocalDate, Double>>)

/** A trend: one or more lines over dates, the value format given by [decimals] and [unit]. */
data class MeChart(val label: String, val unit: String?, val decimals: Int, val series: List<MeSeries>, val caption: String? = null) : MeItem

/** A share of a whole as a ring ("82 %"), with lines of explanation beside it. */
data class MeRing(val label: String, val fraction: Double, val value: String, val lines: List<String>, val hue: String) : MeItem

/** Plain words: an explanation, or why a section is empty. */
data class MeNote(val text: String, val muted: Boolean = true) : MeItem

data class MeSection(val title: String, val items: List<MeItem>, val info: String? = null)

data class MeViewDoc(val tab: String, val sections: List<MeSection>, val range: String? = null)

private fun points(p: List<Pair<LocalDate, Double>>): JsonArray =
    buildJsonArray { p.forEach { (d, v) -> add(buildJsonArray { add(JsonPrimitive(d.toString())); add(JsonPrimitive(v)) }) } }

fun MeItem.toJson(): JsonObject = when (val i = this) {
    is MeTile -> buildJsonObject {
        put("kind", "tile"); put("label", i.label); put("value", i.value)
        i.unit?.let { put("unit", it) }; i.sub?.let { put("sub", it) }; i.tone?.let { put("tone", it.name.lowercase()) }
        i.hue?.let { put("hue", it) }; if (i.series.isNotEmpty()) put("series", points(i.series)); i.info?.let { put("info", it) }
    }
    is MeRow -> buildJsonObject {
        put("kind", "row"); put("label", i.label)
        i.value?.let { put("value", it) }; i.sub?.let { put("sub", it) }; i.tone?.let { put("tone", it.name.lowercase()) }
        i.state?.let { put("state", it) }; i.region?.let { put("region", it) }; i.side?.let { put("side", it) }
    }
    is MeChart -> buildJsonObject {
        put("kind", "chart"); put("label", i.label); i.unit?.let { put("unit", it) }; put("decimals", i.decimals)
        put("series", buildJsonArray { i.series.forEach { s -> add(buildJsonObject { put("name", s.name); put("hue", s.hue); put("points", points(s.points)) }) } })
        i.caption?.let { put("caption", it) }
    }
    is MeRing -> buildJsonObject {
        put("kind", "ring"); put("label", i.label); put("fraction", i.fraction); put("value", i.value); put("hue", i.hue)
        put("lines", buildJsonArray { i.lines.forEach { add(JsonPrimitive(it)) } })
    }
    is MeNote -> buildJsonObject { put("kind", "note"); put("text", i.text); put("muted", i.muted) }
}

fun MeViewDoc.toJson(): JsonObject = buildJsonObject {
    put("v", 1); put("tab", tab); range?.let { put("range", it) }
    put("sections", buildJsonArray {
        sections.forEach { s ->
            add(buildJsonObject {
                put("title", s.title); s.info?.let { put("info", it) }
                put("items", buildJsonArray { s.items.forEach { add(it.toJson()) } })
            })
        }
    })
}
