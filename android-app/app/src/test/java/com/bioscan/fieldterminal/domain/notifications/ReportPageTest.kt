package com.bioscan.fieldterminal.domain.notifications

import com.bioscan.fieldterminal.data.model.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportPageTest {
    private val hrv = ReportMetric("hrv", "HRV", "ms", "recovery", 52.0, 48.1, 3.94, 6, "better", perWeek = false)

    @Test fun theNotificationOpensTheReportInTheApp() {
        assertEquals(NotificationDestination.REPORT, notificationDestination(NotificationKind.PERIOD_REPORT))
    }

    @Test fun numbersAndSigns() {
        assertEquals("1,234", reportNumber(1234.4))
        assertEquals("7.3", reportNumber(7.25))
        assertEquals("52", reportNumber(52.0))
        assertEquals("–", reportNumber(null))
        assertEquals("+3.9", reportSigned(3.94))
        assertEquals("−1.5", reportSigned(-1.5))
        assertEquals("±0", reportSigned(0.01))
    }

    @Test fun metricLines() {
        assertEquals("better" to ReportTone.OK, reportMetricStatus(hrv))
        assertEquals("worse" to ReportTone.BAD, reportMetricStatus(hrv.copy(status = "worse")))
        assertEquals(ReportTone.NONE, reportMetricStatus(hrv.copy(status = "up")).second)
        assertEquals("+3.9 vs 48.1 the week before · 6 days", reportMetricSub(hrv, "week"))
        assertEquals("nothing the month before · per week", reportMetricSub(hrv.copy(prev = null, delta = null, perWeek = true), "month"))
    }

    @Test fun regimenAndNutrients() {
        val c = ReportRegimen(2, "Vitamin C", planned = 7, taken = 5, rate = 0.714, status = "partly")
        assertEquals("partly" to ReportTone.WARN, reportRegimenStatus(c))
        assertEquals("5 of 7 doses · 71%", reportRegimenSub(c))
        val mg = ReportMicro("magnesium", "Magnesium", "mg", 132.1, 75.0, 57.1, 400.0, 350.0, "high_days", listOf("Magnesium"))
        val fe = mg.copy(key = "iron", label = "Iron", status = "ok")
        val se = mg.copy(key = "selenium", label = "Selenium", status = "no_data")
        assertEquals(listOf("magnesium", "iron"), reportMicroOrder(listOf(fe, se, mg)).map { it.key })
        assertEquals("75 from food + 57.1 from supplements (Magnesium) · need 400 · limit 350", reportMicroSub(mg))
        assertEquals("food not tracked · need 400 · limit 350", reportMicroSub(mg.copy(food = null, supplements = 0.0)))
    }
}
