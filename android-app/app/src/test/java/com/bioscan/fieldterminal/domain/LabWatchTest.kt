package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LabWatchTest {
    private val today = LocalDate.of(2026, 11, 3)
    private fun rule(id: Long, aliases: List<String>, markers: List<String>, min: Double? = null, unit: String? = null) =
        LabWatchRule(id, 1, emptySet(), aliases, min, unit, "x", markers, "r", "a", "s", null, null, null, reviewed = false)
    private fun item(id: Long, name: String, dose: String, slots: List<String> = listOf("morning")) =
        WatchItem(id, name, emptySet(), regimenSpec("morning", null, dose, slots = slots, schedule = ScheduleJson("daily")))

    @Test
    fun thresholdsUseThePlannedDailyAmount() {
        val zinc = rule(1, listOf("zinc"), listOf("copper"), 25.0, "mg")
        val results = listOf(LabReading("Hemoglobin", 15.0, null, "g/dL", "normal", today.minusDays(100)))
        // 50 mg a day: applies, never measured.
        val w = matchLabWatch(listOf(zinc), listOf(item(1, "Zinc Picolinate (Swanson)", "50 mg")), results, today).single()
        assertEquals(50.0, w.dailyAmount!!, 0.0)
        assertTrue(w.latest.isEmpty())
        // 15 mg once a day: under the threshold; twice a day 30 mg: over.
        assertTrue(matchLabWatch(listOf(zinc), listOf(item(1, "Zinc", "15 mg")), results, today).isEmpty())
        assertEquals(30.0, matchLabWatch(listOf(zinc), listOf(item(1, "Zinc", "15 mg", listOf("morning", "night"))), results, today).single().dailyAmount!!, 0.0)
    }

    @Test
    fun theLatestResultOfEachMarkerComesAlong() {
        val testo = rule(2, listOf("testosterone", "cypionate"), listOf("hematocrit", "testosterone"))
        val results = listOf(
            LabReading("Hematocrit", 44.0, null, "%", "normal", LocalDate.of(2026, 1, 14)),
            LabReading("Hematocrit", 46.0, null, "%", "normal", LocalDate.of(2026, 4, 25)),
            LabReading("Testosterone, Total", 6.1, null, "ng/mL", null, LocalDate.of(2026, 4, 25)),
        )
        val w = matchLabWatch(listOf(testo), listOf(item(3, "Testosterone Cypionate", "100 mg")), results, today).single()
        assertEquals(listOf(46.0, 6.1), w.latest.map { it.value })
        assertEquals("Hematocrit 46 % · 25 apr 2026", labReadingWords(w.latest.first()))
        // Names match whole words only.
        assertTrue(matchLabWatch(listOf(rule(4, listOf("iron"), listOf("ferritin"))), listOf(item(5, "Ironman Gel", "1 serving")), results, today).isEmpty())
    }

    @Test
    fun gramsConvertToMilligrams() {
        val spec = regimenSpec("morning", null, "5 g", slots = listOf("morning"), schedule = ScheduleJson("daily"))
        assertEquals(5000.0, dailyAmountIn(spec, "mg", today)!!, 0.0)
        assertEquals(null, dailyAmountIn(spec, "IU", today))
    }

    @Test
    fun aCapsuleDoseReadsTheRuleSubstanceFromItsIngredients() {
        val zinc = rule(1, listOf("zinc"), listOf("copper"), 25.0, "mg")
        val capsule = WatchItem(1, "Zinc Picolinate (Swanson)", emptySet(),
            regimenSpec("night", null, "", form = "capsule", doseAmount = 1.0, doseUnit = "capsule", slots = listOf("night"), schedule = ScheduleJson("daily")),
            listOf(DoseIngredient("Zinc", setOf("zinc"), 50.0, "mg")))
        assertEquals(50.0, matchLabWatch(listOf(zinc), listOf(capsule), emptyList(), today).single().dailyAmount!!, 1e-9)
        // 20 mg a day is under the rule's 25 mg threshold: the rule does not apply.
        val small = capsule.copy(ingredients = listOf(DoseIngredient("Zinc", setOf("zinc"), 20.0, "mg")))
        assertTrue(matchLabWatch(listOf(zinc), listOf(small), emptyList(), today).isEmpty())
    }
}
