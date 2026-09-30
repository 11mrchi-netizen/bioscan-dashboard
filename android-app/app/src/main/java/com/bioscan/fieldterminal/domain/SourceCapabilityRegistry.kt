package com.bioscan.fieldterminal.domain

// DAV-116: which connected source can provide which metric, at what
// granularity, raw vs manufacturer-derived -- so analysis code can discover
// an appropriate source instead of hardcoding Zepp/Health-Connect
// assumptions (the exact gap this ticket names). Every fact below is pulled
// straight from docs/analysis-layer-2/02-metric-registry.md's own per-metric
// source column, HealthConnectDailySyncRepository.kt's real sync targets,
// and zepp-extract/index.ts's METRIC_DEFS comments (which endpoints actually
// return real data today, not just which are attempted) -- no new research,
// just made queryable instead of scattered across three files' comments.
// Metric keys match domain/comparison/MetricDirectionality.kt's own
// snake_case canonical names, not a second naming scheme.

enum class DataSource { HEALTH_CONNECT, ZEPP }

// Same raw/derived/modeled/inferred taxonomy the metric registry doc already
// defines in prose (its "Value-kind taxonomy" section) -- this is that
// taxonomy's first appearance as real code.
enum class MetricKind { RAW, DERIVED, MODELED, INFERRED }

enum class SourceStatus {
    // Confirmed returning real data against this account today.
    LIVE,
    // Endpoint/field exists but doesn't return usable data yet (404/500, or
    // shape unconfirmed) -- see the metric's own entry below for why.
    BROKEN,
}

data class SourceCapability(
    val source: DataSource,
    val metric: String,
    val granularity: String,
    val kind: MetricKind,
    val status: SourceStatus,
    val supabaseColumn: String,
)

val SOURCE_CAPABILITIES: List<SourceCapability> = listOf(
    // Health Connect -- daily wearable metrics, all LIVE (wearable_daily/sleep_daily/body_metrics).
    SourceCapability(DataSource.HEALTH_CONNECT, "hrv", "daily", MetricKind.RAW, SourceStatus.LIVE, "wearable_daily.hrv"),
    SourceCapability(DataSource.HEALTH_CONNECT, "resting_heart_rate", "daily", MetricKind.RAW, SourceStatus.LIVE, "wearable_daily.rhr"),
    SourceCapability(DataSource.HEALTH_CONNECT, "vo2max", "daily (intermittent)", MetricKind.RAW, SourceStatus.LIVE, "wearable_daily.vo2max"),
    SourceCapability(DataSource.HEALTH_CONNECT, "spo2", "daily", MetricKind.RAW, SourceStatus.LIVE, "wearable_daily.spo2_avg"),
    SourceCapability(DataSource.HEALTH_CONNECT, "steps", "daily", MetricKind.RAW, SourceStatus.LIVE, "wearable_daily.steps"),
    SourceCapability(DataSource.HEALTH_CONNECT, "sleep_duration", "daily", MetricKind.RAW, SourceStatus.LIVE, "sleep_daily.hours"),
    SourceCapability(DataSource.HEALTH_CONNECT, "sleep_deep_minutes", "daily", MetricKind.RAW, SourceStatus.LIVE, "sleep_daily.deep_min"),
    SourceCapability(DataSource.HEALTH_CONNECT, "sleep_rem_minutes", "daily", MetricKind.RAW, SourceStatus.LIVE, "sleep_daily.rem_min"),
    SourceCapability(DataSource.HEALTH_CONNECT, "respiratory_rate_sleep", "daily", MetricKind.RAW, SourceStatus.LIVE, "sleep_daily.respiratory_rate"),
    SourceCapability(DataSource.HEALTH_CONNECT, "body_weight", "intermittent", MetricKind.RAW, SourceStatus.LIVE, "body_metrics.weight_kg"),
    SourceCapability(DataSource.HEALTH_CONNECT, "body_fat_pct", "intermittent", MetricKind.RAW, SourceStatus.LIVE, "body_metrics.body_fat_pct"),

    // Zepp -- per-second in-session series, real and decoded
    // (zepp-extract/index.ts's decodeWorkoutDetail()).
    SourceCapability(DataSource.ZEPP, "heart_rate_per_second", "per-second (in-session)", MetricKind.RAW, SourceStatus.LIVE, "zepp_workout_detail.decoded"),
    SourceCapability(DataSource.ZEPP, "speed_per_second", "per-second (in-session)", MetricKind.RAW, SourceStatus.LIVE, "zepp_workout_detail.decoded"),
    SourceCapability(DataSource.ZEPP, "altitude_per_second", "per-second (in-session)", MetricKind.RAW, SourceStatus.LIVE, "zepp_workout_detail.decoded"),
    SourceCapability(DataSource.ZEPP, "distance_per_second", "per-second (in-session)", MetricKind.RAW, SourceStatus.LIVE, "zepp_workout_detail.decoded"),
    SourceCapability(DataSource.ZEPP, "cadence_per_second", "per-second (in-session)", MetricKind.RAW, SourceStatus.LIVE, "zepp_workout_detail.decoded"),
    // Zepp-native derived (Minetti GAP/EF/decoupling, effort.ts) -- kept
    // distinguishable from any generic equivalent by living only in
    // zepp_workout_detail.decoded.summary, never blended into a shared column.
    SourceCapability(DataSource.ZEPP, "gap_min_per_km", "per-session", MetricKind.MODELED, SourceStatus.LIVE, "zepp_workout_detail.decoded.summary.gapMinPerKm"),
    SourceCapability(DataSource.ZEPP, "efficiency_factor", "per-session", MetricKind.MODELED, SourceStatus.LIVE, "zepp_workout_detail.decoded.summary.efficiencyFactor"),
    SourceCapability(DataSource.ZEPP, "hr_decoupling_pct", "per-session", MetricKind.MODELED, SourceStatus.LIVE, "zepp_workout_detail.decoded.summary.hrDecouplingPct"),
    SourceCapability(DataSource.ZEPP, "lactate_threshold_hr", "per-session (watch-estimated)", MetricKind.INFERRED, SourceStatus.LIVE, "zepp_workout_detail.decoded.summary.lactateThresholdHrBpm"),
    // Daily/watch-level Zepp metrics -- endpoint shapes still 404/500 on
    // every real attempt (zepp-extract/index.ts's METRIC_DEFS comments) --
    // BROKEN, not a source analysis code should route to yet.
    SourceCapability(DataSource.ZEPP, "stress", "daily (events timeline)", MetricKind.RAW, SourceStatus.BROKEN, "zepp_raw_extracts.raw_body"),
    SourceCapability(DataSource.ZEPP, "heart_rate", "daily", MetricKind.RAW, SourceStatus.BROKEN, "zepp_raw_extracts.raw_body"),
    SourceCapability(DataSource.ZEPP, "hrv", "daily", MetricKind.RAW, SourceStatus.BROKEN, "zepp_raw_extracts.raw_body"),
    SourceCapability(DataSource.ZEPP, "sleep_duration", "daily", MetricKind.RAW, SourceStatus.BROKEN, "zepp_raw_extracts.raw_body"),
    SourceCapability(DataSource.ZEPP, "spo2", "daily", MetricKind.RAW, SourceStatus.BROKEN, "zepp_raw_extracts.raw_body"),
    SourceCapability(DataSource.ZEPP, "training_load", "daily", MetricKind.MODELED, SourceStatus.BROKEN, "zepp_raw_extracts.raw_body"),
    SourceCapability(DataSource.ZEPP, "vo2max", "daily (intermittent)", MetricKind.RAW, SourceStatus.BROKEN, "zepp_raw_extracts.raw_body"),
)

// The acceptance criterion this exists for: "analysis code can discover an
// appropriate source instead of hardcoding Zepp assumptions" -- e.g. calling
// this for "spo2" returns Health Connect today, not Zepp, without the caller
// needing to know Zepp's spo2 endpoint is broken.
fun liveSourceFor(metric: String): SourceCapability? =
    SOURCE_CAPABILITIES.firstOrNull { it.metric == metric && it.status == SourceStatus.LIVE }
