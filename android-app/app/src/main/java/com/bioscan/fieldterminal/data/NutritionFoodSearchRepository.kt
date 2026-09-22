package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.FoodRow
import com.bioscan.fieldterminal.data.model.FoodServingRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns

// DAV-168. Lets the review sheet match a candidate's free-text description
// (or a manual "add item" search) to a real foods row. ilike against
// foods.name, matching this codebase's existing search convention
// (PeopleRepository.kt), not a new full-text-search API surface -- DAV-161's
// GIN index on name still makes this fast at this catalog's real size.
class NutritionFoodSearchRepository(private val supabase: SupabaseClient) {

    suspend fun search(query: String, limit: Int = 20): List<FoodRow> {
        if (query.isBlank()) return emptyList()
        return supabase.postgrest.from("foods")
            .select(
                columns = Columns.list(
                    "id,food_source_id,source_food_id,name,name_zh,brand,barcode,category,subcategory,preparation,is_composite,beverage_class,beverage_subtype",
                ),
            ) {
                filter { ilike("name", "%${query.trim()}%") }
                limit(limit.toLong())
            }
            .decodeList<FoodRow>()
    }

    // Servings for the chip picker once a food is matched -- most manual/
    // barcode-only foods have none, which is fine, the review sheet falls
    // back to a plain grams/ml field.
    suspend fun loadServings(foodId: Long): List<FoodServingRow> =
        supabase.postgrest.from("food_servings")
            .select(columns = Columns.list("id,food_id,serving_name,grams,ml")) { filter { eq("food_id", foodId) } }
            .decodeList<FoodServingRow>()
}
