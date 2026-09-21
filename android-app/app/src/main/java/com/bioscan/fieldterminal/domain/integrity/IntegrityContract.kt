package com.bioscan.fieldterminal.domain.integrity

import java.time.Instant

enum class IntegritySeverity {
    INFO,
    WARNING,
    ERROR,
}

enum class IntegrityCategory {
    DUPLICATE_RECORD,
    DUPLICATE_AGGREGATION,
    STALE_SYNC,
    INVALID_RANGE,
    MISSING_VS_ZERO,
    PROVENANCE_MISSING,
    TIMEZONE_ANOMALY,
    LINEAGE_BROKEN,
}

data class IntegrityIssue(
    val category: IntegrityCategory,
    val domain: String,             // e.g. "exercise_sessions", "sync", "nutrition", "wearable"
    val scope: String,              // e.g. "run_deduplication", "write_back_staleness", "vital_bounds"
    val recordIdentifier: String?,  // sanitized identifier without secrets or PII (e.g. "session:2026-09-20T08:00")
    val severity: IntegritySeverity,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

data class IntegrityReport(
    val checkedAt: Instant = Instant.now(),
    val issues: List<IntegrityIssue>,
) {
    val isValid: Boolean get() = issues.none { it.severity == IntegritySeverity.ERROR }
    val hasWarnings: Boolean get() = issues.any { it.severity == IntegritySeverity.WARNING }
    val errors: List<IntegrityIssue> get() = issues.filter { it.severity == IntegritySeverity.ERROR }
    val warnings: List<IntegrityIssue> get() = issues.filter { it.severity == IntegritySeverity.WARNING }
}
