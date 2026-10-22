package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Endurance fueling (milestone 06.3): the product catalog and the per-activity intake log. The tables, the single-counting
// trigger into nutrient_intake and the analysis live server side (docs/endurance-fueling/README.md); this only reads and writes.

@Serializable
data class FuelingVersionRow(
    val id: Long,
    @SerialName("product_id") val productId: Long,
    val version: Int,
    @SerialName("serving_size") val servingSize: Double? = null,
    @SerialName("serving_unit") val servingUnit: String? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fluid_ml") val fluidMl: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
    // Fuel planning: above 60 g/h the plan checks for glucose plus fructose.
    @SerialName("fructose_g") val fructoseG: Double? = null,
)

@Serializable
data class FuelingProductRow(val id: Long, val kind: String, val name: String, val brand: String? = null)

data class FuelingProduct(val product: FuelingProductRow, val latest: FuelingVersionRow?)

@Serializable
data class FuelingSessionRow(
    val id: Long,
    val type: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_min") val durationMin: Double? = null,
)

@Serializable
data class FuelingIntakeRow(
    val id: Long,
    val status: String,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int,
    val description: String? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fluid_ml") val fluidMl: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
)

@Serializable
data class NewFuelingIntake(
    @SerialName("exercise_session_id") val exerciseSessionId: Long,
    @SerialName("recipe_version_id") val recipeVersionId: Long? = null,
    val description: String? = null,
    val status: String,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int,
    val quantity: Double? = null,
    @SerialName("quantity_unit") val quantityUnit: String? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fluid_ml") val fluidMl: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
    val source: String,
    // A retry of the same submit upserts onto this key instead of adding a second row.
    @SerialName("client_key") val clientKey: String,
)

@Serializable
private data class NewProduct(val kind: String, val name: String, val brand: String? = null)

@Serializable
private data class NewVersion(
    @SerialName("product_id") val productId: Long,
    val version: Int,
    @SerialName("serving_size") val servingSize: Double,
    @SerialName("serving_unit") val servingUnit: String,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fluid_ml") val fluidMl: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
    val source: String,
)

@Serializable
private data class NewIntervention(
    val domain: String,
    @SerialName("subject_type") val subjectType: String,
    @SerialName("subject_key") val subjectKey: String,
    val name: String,
    @SerialName("lifecycle_state") val lifecycleState: String,
    @SerialName("baseline_started_at") val baselineStartedAt: String,
)

@Serializable
private data class FuelingUuidRow(val id: String)

class FuelingRepository(private val supabase: SupabaseClient) {

    /** The catalog with each product's newest recipe version (versions are append-only). */
    suspend fun loadProducts(): List<FuelingProduct> {
        val products = supabase.postgrest.from("fueling_products")
            .select(columns = Columns.list("id,kind,name,brand")) { order("name", Order.ASCENDING) }
            .decodeList<FuelingProductRow>()
        if (products.isEmpty()) return emptyList()
        val versions = supabase.postgrest.from("fueling_recipe_versions")
            .select(columns = Columns.list("id,product_id,version,serving_size,serving_unit,carbs_g,fluid_ml,sodium_mg,caffeine_mg,fructose_g"))
            .decodeList<FuelingVersionRow>()
            .groupBy { it.productId }
            .mapValues { (_, v) -> v.maxByOrNull { it.version } }
        return products.map { FuelingProduct(it, versions[it.id]) }
    }

    /** The six latest non-strength activities from the last three weeks, to attach intake to. */
    suspend fun recentSessions(): List<FuelingSessionRow> {
        val cutoff = LocalDate.now().minusDays(21).toString()
        return supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("id,type,start_time,duration_min")) {
                filter { gte("start_time", cutoff); neq("type", "strength") }
                order("start_time", Order.DESCENDING)
                limit(6)
            }
            .decodeList<FuelingSessionRow>()
    }

    suspend fun intakeFor(sessionId: Long): List<FuelingIntakeRow> =
        supabase.postgrest.from("fueling_intake_log")
            .select(columns = Columns.list("id,status,elapsed_seconds,description,carbs_g,fluid_ml,sodium_mg,caffeine_mg")) {
                filter { eq("exercise_session_id", sessionId) }
                order("elapsed_seconds", Order.ASCENDING)
            }
            .decodeList<FuelingIntakeRow>()

    /** Product plus its first recipe version (per serving values). */
    suspend fun createProduct(kind: String, name: String, carbsG: Double?, sodiumMg: Double?, fluidMl: Double?, caffeineMg: Double?) {
        val product = supabase.postgrest.from("fueling_products")
            .insert(NewProduct(kind, name.trim())) { select(Columns.list("id,kind,name,brand")) }
            .decodeSingle<FuelingProductRow>()
        supabase.postgrest.from("fueling_recipe_versions")
            .insert(NewVersion(product.id, 1, 1.0, "serving", carbsG = carbsG, fluidMl = fluidMl, sodiumMg = sodiumMg, caffeineMg = caffeineMg, source = "app"))
    }

    suspend fun logIntake(row: NewFuelingIntake) {
        supabase.postgrest.from("fueling_intake_log").upsert(row) { onConflict = "user_id,client_key" }
    }

    /** The intervention a fueling experiment points at (experiments.intervention_id). Returns its id. */
    suspend fun createFuelingIntervention(productId: Long, productName: String): String =
        supabase.postgrest.from("interventions")
            .insert(
                NewIntervention(
                    domain = "nutrition",
                    subjectType = "fueling_recipe",
                    lifecycleState = "baseline",
                    subjectKey = productId.toString(),
                    name = "Fueling: $productName",
                    baselineStartedAt = LocalDate.now().toString(),
                ),
            ) { select(Columns.list("id")) }
            .decodeSingle<FuelingUuidRow>().id
}
