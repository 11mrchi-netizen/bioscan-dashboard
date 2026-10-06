package com.bioscan.fieldterminal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor

// DAV-361. Pantry stock math, pure and derived: nothing here is stored. Stock is
// what the user recorded (purchases/adjustments/discards) minus what the log says
// was taken since the first recorded purchase. No purchase recorded = no stock
// state at all (never a negative or guessed number).

enum class StockEventKind { Purchase, Adjustment, Discard }

// servings is signed: purchase > 0, discard < 0, adjustment either way.
data class StockEvent(val kind: StockEventKind, val servings: Double, val at: Instant, val expiresOn: LocalDate? = null)

data class TakenServings(val at: Instant, val servings: Double)

enum class RateBasis { Scheduled, Observed }

enum class PantryFindingKind { NoStock, Low, ReorderSoon, ExpiresFirst, ProviderFlag }

// A finding always carries its reason; there is no composite pantry score.
data class PantryFinding(val kind: PantryFindingKind, val reason: String)

data class PantryAssessment(
    val remainingServings: Double?, // null = no purchase recorded
    val dailyRate: Double?,
    val rateBasis: RateBasis?,
    // Trailing observed rate, shown next to a scheduled one when they differ by > 25%.
    val observedRate: Double?,
    val rateDiffers: Boolean,
    val daysLeft: Int?,
    val runoutDate: LocalDate?,
    val reorderBy: LocalDate?,
    val findings: List<PantryFinding>,
)

const val DEFAULT_REORDER_LEAD_DAYS = 14
const val LOW_STOCK_DAYS = 7
const val OBSERVED_WINDOW_DAYS = 28
const val MIN_OBSERVED_DAYS = 7 // fewer days of history = no observed rate, not a noisy one

fun assessPantry(
    events: List<StockEvent>,
    taken: List<TakenServings>,
    // Servings consumed per day if the roster schedules it; null for as-needed.
    scheduledPerDay: Double?,
    leadDays: Int?,
    providerFlags: List<String>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): PantryAssessment {
    val findings = mutableListOf<PantryFinding>()
    providerFlags.forEach { findings += PantryFinding(PantryFindingKind.ProviderFlag, it) }

    val firstPurchase = events.filter { it.kind == StockEventKind.Purchase }.minOfOrNull { it.at }
    if (firstPurchase == null) {
        findings += PantryFinding(PantryFindingKind.NoStock, "No purchase recorded, so remaining stock is unknown.")
        return PantryAssessment(null, null, null, null, false, null, null, null, findings)
    }

    val consumed = taken.filter { !it.at.isBefore(firstPurchase) }.sumOf { it.servings }
    val remaining = (events.sumOf { it.servings } - consumed).coerceAtLeast(0.0)

    val sinceFirst = ChronoUnit.DAYS.between(firstPurchase.atZone(zone).toLocalDate(), today).toInt() + 1
    val window = minOf(OBSERVED_WINDOW_DAYS, sinceFirst)
    val observed = if (window < MIN_OBSERVED_DAYS) null else {
        val from = today.minusDays(window - 1L)
        taken.filter { val d = it.at.atZone(zone).toLocalDate(); !d.isBefore(from) && !d.isAfter(today) && !it.at.isBefore(firstPurchase) }
            .sumOf { it.servings } / window
    }

    val rate = scheduledPerDay?.takeIf { it > 0 } ?: observed?.takeIf { it > 0 }
    val basis = when {
        scheduledPerDay != null && scheduledPerDay > 0 -> RateBasis.Scheduled
        rate != null -> RateBasis.Observed
        else -> null
    }
    val differs = scheduledPerDay != null && scheduledPerDay > 0 && observed != null &&
        abs(observed - scheduledPerDay) / scheduledPerDay > 0.25

    val daysLeft = rate?.let { floor(remaining / it).toInt() }
    val runout = daysLeft?.let { today.plusDays(it.toLong()) }
    val reorderBy = runout?.minusDays((leadDays ?: DEFAULT_REORDER_LEAD_DAYS).toLong())

    if (remaining <= 0.0) {
        findings += PantryFinding(PantryFindingKind.Low, "Recorded stock is used up. If you bought more, record the purchase.")
    } else if (daysLeft != null && daysLeft <= LOW_STOCK_DAYS) {
        findings += PantryFinding(PantryFindingKind.Low, "About $daysLeft day(s) left at the current rate.")
    } else if (reorderBy != null && !today.isBefore(reorderBy)) {
        findings += PantryFinding(PantryFindingKind.ReorderSoon, "Reorder by $reorderBy to cover the lead time before it runs out on $runout.")
    }

    // ponytail: earliest expiry across purchases, not per-lot burn-down. Upgrade to FIFO lots if mixed batches matter.
    val expiry = events.filter { it.kind == StockEventKind.Purchase }.mapNotNull { it.expiresOn }.minOrNull()
    if (expiry != null && remaining > 0.0 && runout != null && expiry.isBefore(runout)) {
        findings += PantryFinding(PantryFindingKind.ExpiresFirst, "The earliest batch expires $expiry, before the estimated run-out on $runout.")
    }

    return PantryAssessment(remaining, rate, basis, observed, differs, daysLeft, runout, reorderBy, findings)
}
