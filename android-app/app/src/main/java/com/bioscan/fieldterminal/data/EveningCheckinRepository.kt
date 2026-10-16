package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.EveningCheckin
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// Analysis review phase B: reads and writes public.evening_checkins (one row per local evening) and records the phone's
// passive daily context into public.daily_context (supabase/migrations/20261015_evening_checkins.sql).
@Serializable
data class EveningCheckinRow(
    val date: String,
    val drinks: Int? = null,
    @SerialName("caffeine_after_14") val caffeineAfter14: Boolean? = null,
    @SerialName("late_meal") val lateMeal: Boolean? = null,
    val stress: Int? = null,
    @SerialName("late_screen") val lateScreen: Boolean? = null,
)

fun EveningCheckinRow.toDomain(): EveningCheckin =
    EveningCheckin(LocalDate.parse(date), drinks, caffeineAfter14, lateMeal, stress, lateScreen)

fun EveningCheckin.toRow(): EveningCheckinRow =
    EveningCheckinRow(date.toString(), drinks, caffeineAfter14, lateMeal, stress, lateScreen)

@Serializable
private data class DailyContextRow(
    val date: String,
    @SerialName("utc_offset_min") val utcOffsetMin: Int,
    @SerialName("time_zone") val timeZone: String,
)

class EveningCheckinRepository(private val supabase: SupabaseClient) {

    suspend fun load(date: LocalDate): EveningCheckin? =
        supabase.postgrest.from("evening_checkins")
            .select(columns = Columns.list("date,drinks,caffeine_after_14,late_meal,stress,late_screen")) {
                filter { eq("date", date.toString()) }
            }
            .decodeList<EveningCheckinRow>()
            .firstOrNull()
            ?.toDomain()

    suspend fun loadSince(since: LocalDate): List<EveningCheckin> =
        supabase.postgrest.from("evening_checkins")
            .select(columns = Columns.list("date,drinks,caffeine_after_14,late_meal,stress,late_screen")) {
                filter { gte("date", since.toString()) }
                order("date", Order.ASCENDING)
            }
            .decodeList<EveningCheckinRow>()
            .map { it.toDomain() }

    // One row per evening: answering again (or finishing it the next morning) updates it.
    suspend fun save(checkin: EveningCheckin) {
        supabase.postgrest.from("evening_checkins").upsert(checkin.toRow()) { onConflict = "user_id,date" }
    }

    // The phone's UTC offset and time zone for today. The analysis engine turns logged timestamps into local dates and
    // hours with it (a meal logged at 07:30 in UTC+8 is 23:30 UTC the day before). Cheap and idempotent, so it runs
    // whenever the Status hub loads; a failure is silent and the engine falls back to the nearest known day.
    suspend fun recordDailyContext(zone: ZoneId = ZoneId.systemDefault()) {
        val now = OffsetDateTime.now(zone)
        supabase.postgrest.from("daily_context").upsert(
            DailyContextRow(now.toLocalDate().toString(), now.offset.totalSeconds / 60, zone.id),
        ) { onConflict = "user_id,date" }
    }
}
