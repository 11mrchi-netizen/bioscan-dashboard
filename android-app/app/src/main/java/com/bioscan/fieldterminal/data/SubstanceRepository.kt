package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.SubstanceCurves
import com.bioscan.fieldterminal.domain.SubstanceCurves.Dose
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// What TODAY's IN THE BODY card reads: the drinks and supplements logged since the evening before, and the usual bedtime.
// Logged times are the phone's wall clock labelled +00 (domain/WallClock.kt): their digits are local.

/**
 * A regimen item's doses on the day's clock, back to five half-lives, in one unit [base], with the half-life the curve uses:
 * the person's own entry ([ownEntry], substance_kinetics) or the FDA label's. [tmaxAssumed]: no peak time was entered.
 */
data class MedicationCurve(
    val name: String, val kind: String, val halfLifeH: Double, val tmaxH: Double, val tmaxAssumed: Boolean, val ownEntry: Boolean,
    val base: SubstanceCurves.QtyBase, val doses: List<Dose>,
)

/** The regimen's curves, and the items taken lately that have no half-life yet (so nothing can be drawn for them). */
data class RegimenCurves(val items: List<MedicationCurve>, val missing: List<String>)

/** Doses on the day's own clock (minutes from its midnight) and the usual bedtime in the same terms, or null without enough nights. */
data class SubstanceDay(
    val day: LocalDate,
    val caffeine: List<Dose>,
    val alcohol: List<Dose>,
    val supplements: Map<String, List<Dose>>,
    val regimen: RegimenCurves,
    val bedtimeMin: Double?,
)

@Serializable
private data class SubItem(@SerialName("caffeine_mg") val caffeineMg: Double? = null, @SerialName("alcohol_g") val alcoholG: Double? = null)

@Serializable
private data class SubMeal(@SerialName("logged_at") val loggedAt: String, @SerialName("meal_items") val items: List<SubItem> = emptyList())

@Serializable
private data class SubSupplementLog(val id: Long, @SerialName("taken_at") val takenAt: String)

@Serializable
private data class SubSupplementDose(
    @SerialName("source_id") val sourceId: Long,
    val nutrient: String,
    val amount: Double,
    val unit: String? = null,
)

@Serializable
private data class SubSleep(val bedtime: String? = null, @SerialName("tz_offset_min") val tzOffsetMin: Int? = null)

class SubstanceRepository(private val supabase: SupabaseClient) {

    suspend fun load(day: LocalDate): SubstanceDay {
        val lo = "${day.minusDays(1)}T00:00:00+00:00"
        val hi = "${day.plusDays(1)}T00:00:00+00:00"
        val meals = supabase.postgrest.from("meals").select(Columns.list("logged_at,meal_items(caffeine_mg,alcohol_g)")) {
            filter { gte("logged_at", lo); lt("logged_at", hi) }
        }.decodeList<SubMeal>()
        val caffeine = ArrayList<Dose>()
        val alcohol = ArrayList<Dose>()
        for (m in meals) {
            val t = SubstanceCurves.minutesFromDay(m.loggedAt, day) ?: continue
            for (i in m.items) {
                i.caffeineMg?.takeIf { it > 0 }?.let { caffeine += Dose(t, it) }
                i.alcoholG?.takeIf { it > 0 }?.let { alcohol += Dose(t, it) }
            }
        }
        // Supplements are optional extras: a failed read leaves the curves of drinks alone.
        val supplements = catching<Map<String, List<Dose>>> {
            val taken = supabase.postgrest.from("supplement_log").select(Columns.list("id,taken_at")) {
                filter { gte("taken_at", lo); lt("taken_at", hi) }
            }.decodeList<SubSupplementLog>().associate { it.id to it.takenAt }
            if (taken.isEmpty()) emptyMap() else {
                // nutrient_intake.logged_at of a supplement is not the time it was taken: that comes from supplement_log.
                val doses = supabase.postgrest.from("nutrient_intake").select(Columns.list("source_id,nutrient,amount,unit")) {
                    filter { eq("source_type", "supplement"); isIn("source_id", taken.keys.toList()) }
                }.decodeList<SubSupplementDose>()
                val out = HashMap<String, MutableList<Dose>>()
                for (d in doses) {
                    val c = SubstanceCurves.compoundFor(d.nutrient) ?: continue
                    val t = taken[d.sourceId]?.let { SubstanceCurves.minutesFromDay(it, day) } ?: continue
                    val a = SubstanceCurves.amountIn(c, d.amount, d.unit)?.takeIf { it > 0 } ?: continue
                    out.getOrPut(c.key) { ArrayList() } += Dose(t, a)
                }
                out
            }
        }.getOrElse { emptyMap() }
        val bedtime = catching {
            val nights = supabase.postgrest.from("sleep_daily").select(Columns.list("bedtime,tz_offset_min")) {
                filter { lte("date", day.toString()) }
                order("date", Order.DESCENDING)
                limit(14)
            }.decodeList<SubSleep>()
            SubstanceCurves.usualBedtimeMin(nights.map { it.bedtime to it.tzOffsetMin })
        }.getOrNull()
        val regimen = catching { loadRegimen(day) }.getOrElse { RegimenCurves(emptyList(), emptyList()) }
        return SubstanceDay(day, caffeine, alcohol, supplements, regimen, bedtime)
    }

    /**
     * Active regimen items that are not supplements (medications, peptides, hormones, other) with a half-life: the person's own
     * entry (substance_kinetics) wins over the FDA label's (regimen_references). A dose logged in tablets is the servings taken
     * times what a serving holds (the person's own amount, else the product's ingredient row).
     */
    private suspend fun loadRegimen(day: LocalDate): RegimenCurves {
        val meds = supabase.postgrest.from("supplements").select(Columns.list("id,name,kind,product_id")) {
            filter { neq("kind", "supplement"); eq("status", "active") }
        }.decodeList<SubMedication>()
        if (meds.isEmpty()) return RegimenCurves(emptyList(), emptyList())
        val ids = meds.map { it.id }
        val label = supabase.postgrest.from("regimen_references").select(Columns.list("supplement_id,half_life_hours")) {
            filter { isIn("supplement_id", ids) }
        }.decodeList<SubReference>().mapNotNull { r -> r.halfLifeHours?.takeIf { it > 0 }?.let { r.supplementId to it } }.toMap()
        val own = catching {
            supabase.postgrest.from("substance_kinetics")
                .select(Columns.list("supplement_id,half_life_hours,tmax_hours,amount_per_serving,amount_unit")) { filter { isIn("supplement_id", ids) } }
                .decodeList<KineticsRow>().associateBy { it.supplementId }
        }.getOrElse { emptyMap() }
        val products = meds.mapNotNull { it.productId }.distinct()
        val links = if (products.isEmpty()) emptyList() else supabase.postgrest.from("supplement_product_ingredients")
            .select(Columns.list("supplement_product_id,compound_amount,compound_unit,supplement_ingredients(name)")) {
                filter { isIn("supplement_product_id", products) }
            }.decodeList<SubLink>()
        val hi = "${day.plusDays(1)}T00:00:00+00:00"
        val items = ArrayList<MedicationCurve>()
        val missing = ArrayList<String>()
        for (m in meds) {
            val o = own[m.id]
            val hl = o?.halfLifeHours ?: label[m.id]
            val since = "${day.minusDays(SubstanceCurves.lookbackDays(hl ?: 24.0))}T00:00:00+00:00"
            val logs = supabase.postgrest.from("supplement_log").select(Columns.list("taken_at,dose_value,dose_unit,serving_count")) {
                filter { eq("supplement_id", m.id); gte("taken_at", since); lt("taken_at", hi) }
            }.decodeList<SubDoseLog>()
            if (logs.isEmpty()) continue
            if (hl == null) { missing += m.name; continue }
            // What a serving holds: the person's own entry, else the product's ingredient named like the item (or its only one).
            val mine = links.filter { it.productId == m.productId }
            val named = mine.firstOrNull { it.ingredient?.name.orEmpty().lowercase().contains(m.name.lowercase()) } ?: mine.singleOrNull()
            val perServing = if (o?.amountPerServing != null) SubstanceCurves.quantity(o.amountPerServing, o.amountUnit)
            else named?.compoundAmount?.let { SubstanceCurves.quantity(it, named.compoundUnit) }
            val qtys = logs.mapNotNull { l ->
                val t = SubstanceCurves.minutesFromDay(l.takenAt, day) ?: return@mapNotNull null
                val q = SubstanceCurves.doseQty(l.doseValue, l.doseUnit, l.servingCount, perServing)?.takeIf { it.v > 0 } ?: return@mapNotNull null
                t to q
            }
            if (qtys.isEmpty()) { missing += m.name; continue }
            // One unit per item: a dose logged in another family is left out.
            val base = qtys.first().second.base
            items += MedicationCurve(
                m.name, m.kind, hl, o?.tmaxHours ?: SubstanceCurves.DEFAULT_TMAX_H, o?.tmaxHours == null, o != null, base,
                qtys.filter { it.second.base == base }.map { Dose(it.first, it.second.v) },
            )
        }
        return RegimenCurves(items, missing)
    }
}

@Serializable
private data class SubMedication(val id: Long, val name: String, val kind: String = "medication", @SerialName("product_id") val productId: Long? = null)

@Serializable
private data class SubReference(@SerialName("supplement_id") val supplementId: Long, @SerialName("half_life_hours") val halfLifeHours: Double? = null)

@Serializable
private data class SubIngredientName(val name: String)

@Serializable
private data class SubLink(
    @SerialName("supplement_product_id") val productId: Long,
    @SerialName("compound_amount") val compoundAmount: Double? = null,
    @SerialName("compound_unit") val compoundUnit: String? = null,
    @SerialName("supplement_ingredients") val ingredient: SubIngredientName? = null,
)

@Serializable
private data class SubDoseLog(
    @SerialName("taken_at") val takenAt: String,
    @SerialName("dose_value") val doseValue: Double? = null,
    @SerialName("dose_unit") val doseUnit: String? = null,
    @SerialName("serving_count") val servingCount: Double? = null,
)
