package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.NutrientIntakeRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate
import java.time.ZoneOffset

data class NutrientBreakdownData(
    val nutrients: Map<String, Double>,
    val daysCovered: Int,
)

class NutrientBreakdownRepository(private val supabase: SupabaseClient) {

    suspend fun loadNutrients(days: Int): NutrientBreakdownData {
        val cutoff = LocalDate.now().minusDays(days.toLong() - 1)
            .atStartOfDay()
            .atOffset(ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        val rows = supabase.postgrest.from("nutrient_intake")
            .select(columns = Columns.list("nutrient,amount,unit,logged_at,source_type,source_id")) {
                filter { gte("logged_at", cutoff) }
                order("logged_at", Order.DESCENDING)
                limit(5000)
            }
            .decodeList<NutrientIntakeRow>()

        val totals = mutableMapOf<String, Double>()
        val dates = mutableSetOf<String>()
        for (row in rows) {
            totals[row.nutrient] = (totals[row.nutrient] ?: 0.0) + row.amount
            dates.add(row.loggedAt.take(10))
        }

        return NutrientBreakdownData(
            nutrients = totals,
            daysCovered = dates.size.coerceAtLeast(1),
        )
    }
}
