package com.bioscan.fieldterminal.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Photo logging v3: the estimate's foods, questions and micronutrients reach the app, and an older (v2) answer still reads.
class PhotoEstimateV3Test {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aV3AnswerCarriesTheFoodsAndQuestions() {
        val e = json.decodeFromString<NutritionMealEstimate>(
            """{"description":"Chicken rice bowl","calories":484,"protein_g":34.3,"carbs_g":51,"fat_g":14.5,"confidence":0.6,
               "confidence_level":"medium","confidence_reason":"sauce unclear","total_grams":300,
               "items":[{"name":"grilled chicken thigh","canonical_name":"chicken thigh","grams":120,"portion":"1 thigh",
                 "preparation":"grilled","calories":250,"protein_g":30,"carbs_g":0,"fat_g":14,
                 "extra":{"sodium":400,"iron":1.2},"confidence":"high","assumptions":["skin on"]}],
               "questions":[{"question":"Cooked in oil?","options":["No","A little","A lot"]}]}""",
        )
        assertEquals("medium", e.confidenceLevel)
        assertEquals(1, e.items.size)
        assertEquals("chicken thigh", e.items[0].canonicalName)
        assertEquals(400.0, e.items[0].extra.getValue("sodium"), 1e-9)
        assertEquals(listOf("skin on"), e.items[0].assumptions)
        assertEquals(3, e.questions[0].options.size)
    }

    @Test
    fun aV2AnswerStillReads() {
        val e = json.decodeFromString<NutritionMealEstimate>("""{"description":"Pasta","calories":600,"protein_g":20,"carbs_g":90,"fat_g":15,"confidence":0.7}""")
        assertTrue(e.items.isEmpty())
        assertTrue(e.questions.isEmpty())
        assertEquals(null, e.confidenceLevel)
    }

    @Test
    fun anUnmatchedFoodKeepsThePhotosMicronutrientsAndTheyScaleWithThePortion() {
        val item = CronometerItemResult(
            query = "pickled mustard greens", quantityG = 40.0, matchQuality = "none",
            aiEstimate = CronometerAiEstimate(calories = 12.0, proteinG = 0.5, carbsG = 2.0, fatG = 0.1, extra = mapOf("sodium" to 900.0, "iron" to 0.6)),
            usedEstimate = true,
        )
        assertEquals(900.0, item.portionNutrients().getValue("sodium"), 1e-9)
        assertEquals(12.0, item.portionNutrients().getValue("calories"), 1e-9)
        val doubled = item.withGrams(80.0)
        assertEquals(1800.0, doubled.portionNutrients().getValue("sodium"), 1e-9)
        assertEquals(24.0, doubled.portionNutrients().getValue("calories"), 1e-9)
        // The totals rebuilt after an edit carry them too.
        val totals = emptyEnrichment().withItems(listOf(doubled))
        assertEquals(1800.0, totals.sodiumMg!!, 1e-9)
        assertEquals(1.2, totals.allNutrients.getValue("iron"), 1e-9)
        assertTrue(JsonObject(emptyMap()).isEmpty())
    }
}
