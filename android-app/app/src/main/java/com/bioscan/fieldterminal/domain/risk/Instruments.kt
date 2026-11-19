package com.bioscan.fieldterminal.domain.risk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.roundToLong

// Risk profile R2 (docs/risk-profile/01-plan.md): the validated questionnaires, read from the asset instruments.json
// (supabase/functions/_shared/instruments/, shared with the console). Scored exactly as console/src/lib/instruments.ts
// scores them; the file's `cases` are the tests for both. Pure.

@Serializable
data class InstrumentOption(val label: String, val value: Double)

@Serializable
data class InstrumentItem(val id: String, val text: String, val options: String, val reverse: Boolean = false)

@Serializable
data class InstrumentBand(val min: Double, val max: Double, val label: String, val state: String)

@Serializable
data class InstrumentFlag(val id: String, val item: String, val min: Double, val text: String)

@Serializable
data class InstrumentScoring(val method: String, val multiplier: Double)

@Serializable
data class InstrumentNumber(val min: Double, val max: Double, val unit: String)

@Serializable
data class Instrument(
    val id: String,
    val short: String,
    val name: String,
    val domain: String,
    @SerialName("every_days") val everyDays: Long,
    val prompt: String,
    val items: List<InstrumentItem>,
    val number: InstrumentNumber? = null,
    val scoring: InstrumentScoring,
    val range: List<Double>,
    val better: String,
    val unit: String,
    val bands: List<InstrumentBand>,
    val flags: List<InstrumentFlag> = emptyList(),
    val source: String,
)

@Serializable
data class InstrumentCase(val instrument: String, val answers: List<Double>, val score: Double, val band: String, val flags: List<String>)

@Serializable
data class InstrumentDefs(
    val version: String,
    @SerialName("option_sets") val optionSets: Map<String, List<InstrumentOption>>,
    val instruments: List<Instrument>,
    val cases: List<InstrumentCase> = emptyList(),
) {
    fun instrument(id: String): Instrument? = instruments.firstOrNull { it.id == id }
    fun optionsOf(item: InstrumentItem): List<InstrumentOption> = optionSets[item.options].orEmpty()
}

private val json = Json { ignoreUnknownKeys = true }

fun parseInstruments(text: String): InstrumentDefs = json.decodeFromString(InstrumentDefs.serializer(), text)

data class InstrumentScore(val score: Double, val band: InstrumentBand?, val flags: List<InstrumentFlag>)

private fun round2(x: Double): Double = (x * 100).roundToLong() / 100.0

/** Scores [answers] in item order (a number questionnaire takes one value); null when an answer is missing or invalid. */
fun InstrumentDefs.score(inst: Instrument, answers: List<Double>): InstrumentScore? {
    val raw: Double = if (inst.scoring.method == "value") {
        val v = answers.firstOrNull() ?: return null
        val n = inst.number
        if (!v.isFinite() || (n != null && (v < n.min || v > n.max))) return null
        v
    } else {
        if (answers.size != inst.items.size) return null
        val values = inst.items.mapIndexed { i, item ->
            val opts = optionsOf(item)
            val v = answers[i]
            if (opts.none { it.value == v }) return null
            if (item.reverse) opts.maxOf { it.value } + opts.minOf { it.value } - v else v
        }
        if (inst.scoring.method == "mean") values.sum() / values.size else values.sum()
    }
    val s = round2(raw * inst.scoring.multiplier)
    val band = inst.bands.firstOrNull { s >= it.min && s <= it.max }
    val flags = inst.flags.filter { f ->
        val i = inst.items.indexOfFirst { it.id == f.item }
        i >= 0 && answers[i] >= f.min
    }
    return InstrumentScore(s, band, flags)
}

/** When an instrument is next due: [Instrument.everyDays] after the last local date it was answered; null = never answered. */
fun Instrument.dueOn(last: LocalDate?): LocalDate? = last?.plusDays(everyDays)

fun Instrument.isDue(last: LocalDate?, today: LocalDate): Boolean = dueOn(last)?.let { !today.isBefore(it) } ?: true

/** The order the questionnaires are offered and shown in. */
val INSTRUMENT_DOMAINS = listOf("mind" to "MIND", "connection" to "CONNECTION", "work" to "WORK")

/** Questionnaires due today, in the order shown; the reminder names them. */
fun InstrumentDefs.dueToday(lastByInstrument: Map<String, LocalDate>, today: LocalDate): List<Instrument> =
    instruments.filter { it.isDue(lastByInstrument[it.id], today) }

/** One saved answer, as the summaries read it (data/InstrumentsRepository rows map to this). */
data class InstrumentAnswer(val instrument: String, val date: LocalDate, val score: Double, val band: String?, val flags: List<String>)

/** What a questionnaire's row shows: its latest result, its band's state, the previous score, its history and when it is due. */
data class InstrumentSummary(
    val instrument: Instrument,
    val latest: InstrumentAnswer?,
    val state: String?,
    val previous: Double?,
    val series: Map<LocalDate, Double>,
    val dueOn: LocalDate?,
    val due: Boolean,
)

fun InstrumentDefs.summaries(answers: List<InstrumentAnswer>, today: LocalDate): List<InstrumentSummary> = instruments.map { inst ->
    val mine = answers.filter { it.instrument == inst.id }.sortedBy { it.date }
    val latest = mine.lastOrNull()
    InstrumentSummary(
        instrument = inst,
        latest = latest,
        state = latest?.let { l -> inst.bands.firstOrNull { l.score >= it.min && l.score <= it.max }?.state },
        previous = mine.dropLast(1).lastOrNull()?.score,
        // one value a day: the last answer that day
        series = mine.associate { it.date to it.score }.toSortedMap(),
        dueOn = inst.dueOn(latest?.date),
        due = inst.isDue(latest?.date, today),
    )
}
