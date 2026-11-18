package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.MealSlotKind
import com.bioscan.fieldterminal.ui.screens.today.PlanAction
import com.bioscan.fieldterminal.ui.screens.today.PlanKind
import com.bioscan.fieldterminal.ui.screens.today.PlanRow
import com.bioscan.fieldterminal.ui.screens.today.SoFar
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// The web Dashboard reads today_plans.plan in this shape (console/src/dashboard/todayData.ts): keep the two in step.
class TodayPlanPublisherTest {
    @Test fun `rows keep their wall-clock time, kind and numbers and untimed rows have no time`() {
        val rows = listOf(
            PlanRow(null, "Close Monday", "1 min", false, PlanAction.CloseDay(LocalDate.of(2026, 11, 16)), PlanKind.CLOSE),
            PlanRow(LocalTime.of(12, 30), "Lunch", "640 kcal", true, PlanAction.Meal(MealSlotKind.LUNCH), PlanKind.MEAL, kcal = 640.4),
            PlanRow(LocalTime.of(18, 5), "Easy run", "45 min · READY ✓", false, PlanAction.Bedtime, PlanKind.SESSION, durationMin = 45.0),
        )
        val p = todayPlanSnapshot(rows, SoFar(1210.0, 2600.0, 150.0, 80.0, 40.0))
        assertEquals("1", p["v"]!!.jsonPrimitive.content)
        val r = p["rows"]!!.jsonArray.map { it.jsonObject }
        assertFalse("time" in r[0])
        assertEquals("12:30", r[1]["time"]!!.jsonPrimitive.content)
        assertEquals("MEAL", r[1]["kind"]!!.jsonPrimitive.content)
        assertEquals("true", r[1]["done"]!!.jsonPrimitive.content)
        assertEquals("18:05", r[2]["time"]!!.jsonPrimitive.content)
        assertEquals("45.0", r[2]["duration_min"]!!.jsonPrimitive.content)
        val s = p["so_far"]!!.jsonObject
        assertEquals("2600.0", s["target_kcal"]!!.jsonPrimitive.content)
        assertEquals("150.0", s["carbs_g"]!!.jsonPrimitive.content)
    }
}
