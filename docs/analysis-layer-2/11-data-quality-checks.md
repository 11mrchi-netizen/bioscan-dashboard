# 11 — Cross-domain data-quality and provenance checks

**Linear:** [DAV-179](https://linear.app/biodashboard/issue/DAV-179) · **Status:** done

## Overview

Reusable integrity checks that protect the analytical foundation across
Health Connect, manual logs, Zepp, nutrition and future sources. Each check
returns structured diagnostics (table, column, record scope, severity,
human-readable message) without exposing secrets or raw credentials.

## Check categories

### 1. Duplicate records

Detect rows that likely represent the same real-world event ingested from
multiple sources or sync runs.

| Check | Tables | Logic |
|-------|--------|-------|
| Exercise session duplicates | exercise_sessions | Same user + date + type + duration within 5 min + different source |
| Sleep session duplicates | sleep_daily | Multiple rows for same user + date |
| Wearable daily duplicates | wearable_daily | Multiple rows for same user + date (should be impossible with upsert) |

### 2. Impossible value ranges

Flag physiologically impossible or sensor-error values.

| Metric | Table.Column | Valid range |
|--------|-------------|-------------|
| Resting HR | wearable_daily.rhr | 25–120 bpm |
| HRV | wearable_daily.hrv | 1–300 ms |
| Steps | wearable_daily.steps | 0–200,000 |
| VO2max | wearable_daily.vo2max | 10–90 mL/kg/min |
| SpO2 | wearable_daily.spo2_avg | 50–100% |
| Sleep hours | sleep_daily.hours | 0–24 h |
| Body weight | body_metrics.weight_kg | 20–300 kg |
| Body fat | body_metrics.body_fat_pct | 1–70% |
| Exercise distance | exercise_sessions.distance_km | 0–500 km |
| Exercise duration | exercise_sessions.duration_min | 0–1440 min |
| Exercise avg HR | exercise_sessions.avg_hr | 25–250 bpm |
| Meal calories | meals.calories | 0–10,000 kcal |

### 3. Missing or invalid provenance

Identify records without source attribution, which blocks multi-source
deduplication and confidence scoring.

| Check | Logic |
|-------|-------|
| Null source | exercise_sessions.source IS NULL |
| Missing HC record ID | exercise_sessions where source='health_connect' but health_connect_record_id IS NULL |
| Tables without source column | Structural gap (wearable_daily, sleep_daily, body_metrics, etc.) — reported as advisory |

### 4. Timestamp and temporal anomalies

| Check | Logic |
|-------|-------|
| Future dates | Any row with date > current_date |
| Temporal gaps | Consecutive missing dates in wearable_daily (>2 days) |
| Stale sync | sync_log last entry older than 48 hours |

### 5. Missing vs zero confusion

| Check | Logic |
|-------|-------|
| Zero steps with other data | wearable_daily where steps = 0 but resting_hr or hrv_rmssd is non-null |
| Null vs zero calories | meals where calories = 0 (likely should be NULL = unknown) |

### 6. Cross-table consistency

| Check | Logic |
|-------|-------|
| Active > total calories | wearable_daily where calories_active > calories_total |
| Sleep stages > total | sleep_daily where (deep_min + light_min + rem_min) / 60 > hours |

## Output contract

Every check returns rows conforming to:

```sql
(
  check_id    text,      -- e.g. 'range.resting_hr'
  severity    text,      -- 'error', 'warning', 'advisory'
  domain      text,      -- 'wearable', 'sleep', 'exercise', 'nutrition', 'sync'
  table_name  text,
  column_name text,      -- nullable
  record_id   bigint,    -- nullable (row-level findings)
  record_date date,      -- nullable
  message     text       -- human-readable diagnostic
)
```

Severities:
- **error**: Data is certainly wrong (impossible value, confirmed duplicate).
  Downstream analytics should exclude or flag.
- **warning**: Data is likely wrong or suspicious (zero-steps-with-HR, stale sync).
  Requires human review.
- **advisory**: Structural gap that doesn't affect current data but will cause
  problems when a second source is added (missing provenance column).

## Usage

```sql
-- Run all checks
SELECT * FROM run_data_quality_checks();

-- Run a specific domain
SELECT * FROM run_data_quality_checks()
WHERE domain = 'exercise';

-- Count by severity
SELECT severity, count(*) FROM run_data_quality_checks()
GROUP BY severity;
```

## Reusability

Domain projects consume the same `run_data_quality_checks()` function rather
than implementing bespoke validation. The function is idempotent and read-only —
it never modifies data, only reports findings.
