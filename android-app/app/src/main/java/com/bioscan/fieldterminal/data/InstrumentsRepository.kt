package com.bioscan.fieldterminal.data

import android.content.Context
import com.bioscan.fieldterminal.domain.risk.Instrument
import com.bioscan.fieldterminal.domain.risk.InstrumentDefs
import com.bioscan.fieldterminal.domain.risk.InstrumentScore
import com.bioscan.fieldterminal.domain.risk.parseInstruments
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Risk profile R2 (supabase/migrations/20261119101435_instrument_responses.sql): questionnaire answers. Append-only: a
// mistake is deleted and answered again. local_date is the phone's calendar date when it was saved; answered_at (a real
// instant) is set by the database.

@Serializable
data class InstrumentResponseRow(
    val id: Long,
    val instrument: String,
    @SerialName("local_date") val localDate: String,
    @SerialName("answered_at") val answeredAt: String,
    val answers: List<Double>,
    val score: Double,
    val band: String? = null,
    val flags: List<String> = emptyList(),
    val source: String = "phone",
) {
    val date: LocalDate get() = LocalDate.parse(localDate)
}

@Serializable
private data class NewInstrumentResponseRow(
    val instrument: String,
    @SerialName("defs_version") val defsVersion: String,
    @SerialName("local_date") val localDate: String,
    val answers: List<Double>,
    val score: Double,
    val band: String?,
    val flags: List<String>,
    val source: String = "phone",
)

class InstrumentsRepository(private val supabase: SupabaseClient) {

    suspend fun loadResponses(): List<InstrumentResponseRow> = ReadCache.kept("risk.instrument_responses", tables = setOf("instrument_responses")) {
        supabase.postgrest.from("instrument_responses")
            .select(Columns.list("id,instrument,local_date,answered_at,answers,score,band,flags,source")) {
                order("answered_at", Order.ASCENDING)
            }
            .decodeList<InstrumentResponseRow>()
    }

    /** The local date each questionnaire was last answered. */
    suspend fun lastAnswered(): Map<String, LocalDate> =
        loadResponses().groupBy { it.instrument }.mapValues { (_, rows) -> rows.maxOf { it.date } }

    suspend fun save(defs: InstrumentDefs, inst: Instrument, answers: List<Double>, scored: InstrumentScore, today: LocalDate = LocalDate.now()) {
        supabase.postgrest.from("instrument_responses").insert(
            NewInstrumentResponseRow(
                instrument = inst.id, defsVersion = defs.version, localDate = today.toString(), answers = answers,
                score = scored.score, band = scored.band?.label, flags = scored.flags.map { it.id },
            ),
        )
    }

    suspend fun delete(id: Long) {
        supabase.postgrest.from("instrument_responses").delete { filter { eq("id", id) } }
    }

    companion object {
        @Volatile private var defs: InstrumentDefs? = null

        /** The packaged definitions (asset instruments.json), read once. */
        fun definitions(context: Context): InstrumentDefs =
            defs ?: context.applicationContext.assets.open("instruments.json").bufferedReader().use { parseInstruments(it.readText()) }.also { defs = it }
    }
}
