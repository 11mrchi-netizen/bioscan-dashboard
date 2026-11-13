package com.bioscan.fieldterminal.domain.integrity

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
    // Analysis Layer 3 / R1: a metric changed level or noise on a date (a candidate device/source change); see
    // domain/SeriesBreakStarts.kt and the analysis-run Edge Function, which detects and publishes these.
    SERIES_BREAK,
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

