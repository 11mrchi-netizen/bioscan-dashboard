// Single place that maps the console's logical fields onto the live Supabase schema.
// Since the analysis hub's phase A the series the console reads come from the shared contract file
// (supabase/functions/_shared/metric-registry/metric-series.json, rules in analysis-core/seriesContract.ts); this file
// adds the live-column snapshot that keeps those contracts honest.
// Columns verified against project ugfrglbcoivkprjqvjzz on 2026-10-12. The console was first written against an assumed
// schema (a `runs` table, `meals.date`, `total_sleep_minutes`, `hrv_rmssd_ms`, `confidence`/`provenance` columns) that the
// live database never had, so every query failed silently; keep new queries going through these constants.

import seriesFile from '../../../supabase/functions/_shared/metric-registry/metric-series.json' with { type: 'json' }
import type { SeriesContract, SeriesFile } from '../../../supabase/functions/_shared/analysis-core/seriesContract.ts'

export const SERIES_FILE = seriesFile as SeriesFile
export const SERIES: SeriesContract[] = SERIES_FILE.series
const SERIES_BY_ID = new Map(SERIES.map((s) => [s.id, s]))

export function seriesById(id: string): SeriesContract | undefined {
  return SERIES_BY_ID.get(id)
}

// Tables that carry a `source` column (wellbeing_daily, meals, exercise_sessions do not all have the same shape).
export const TABLES_WITH_SOURCE = new Set(['wearable_daily', 'sleep_daily', 'body_metrics', 'hydration_daily', 'rest_days', 'exercise_sessions', 'meals'])

// exercise_sessions.type values (live counts 2026-10-12: walk 4655, other 1295, strength 534, run 143, ride 16, hike 2, yoga 1).
// Walks are auto-detected background movement and would swamp any load model or timeline.
export const ENDURANCE_TYPES = ['run', 'ride', 'hike']
export const NOTABLE_SESSION_TYPES = ['run', 'ride', 'hike', 'strength', 'yoga']

// start_time and logged_at are written as "local time, UTC-labelled" (Health Connect convention, docs/zepp-integration/04),
// so the first ten characters are the local calendar date. Not every column follows it: sleep bedtimes and meals logged
// before 2026-09-17 are true UTC. The per-series rule is the time contract (seriesContract.ts localDateOf).
export function localDate(ts: string | null | undefined): string {
  return String(ts ?? '').slice(0, 10)
}

// Inclusive date range as timestamp bounds for timestamptz columns.
export function dayBounds(start: string, end: string): { from: string; to: string } {
  return { from: `${start}T00:00:00`, to: `${end}T23:59:59` }
}

// A query result that failed must be visible, not an empty state.
export function warnOnError(scope: string, error: { message: string } | null): void {
  if (error) console.warn(`[console] ${scope}: ${error.message}`)
}

// Columns of the tables the series contracts read, snapshotted from the live database (2026-10-12; exercise_sessions,
// meals, hydration_daily and the sleep_daily stage columns added 2026-10-27). schema.test.ts fails if a contract names a
// column that is not listed here. Refresh the snapshot with
//   select table_name, column_name from information_schema.columns where table_schema = 'public'
export const LIVE_COLUMNS: Record<string, string[]> = {
  wearable_daily: ['id', 'user_id', 'date', 'steps', 'rhr', 'hrv', 'vo2max', 'zone_minutes', 'created_at', 'calories_active', 'calories_total', 'spo2_avg', 'bmr', 'activity_intensity_min', 'source', 'hr_timeseries'],
  sleep_daily: ['id', 'user_id', 'date', 'hours', 'score', 'respiratory_rate', 'created_at', 'bedtime', 'wake_time', 'deep_min', 'rem_min', 'light_min', 'source', 'stage_intervals', 'tz_offset_min'],
  wellbeing_daily: ['id', 'user_id', 'date', 'energy', 'mood', 'stress', 'soreness', 'created_at', 'morning_erection_quality', 'arousal_level'],
  body_metrics: ['id', 'user_id', 'date', 'weight_kg', 'body_fat_pct', 'lean_mass_kg', 'created_at', 'height_cm', 'source'],
  hydration_daily: ['id', 'user_id', 'date', 'ml', 'created_at', 'source'],
  meals: ['id', 'user_id', 'logged_at', 'description', 'calories', 'protein_g', 'fat_g', 'carbs_g', 'created_at', 'fiber_g', 'sugar_g', 'sodium_mg', 'source'],
  exercise_sessions: ['id', 'user_id', 'type', 'start_time', 'end_time', 'duration_min', 'distance_km', 'calories_active', 'calories_total', 'avg_hr', 'max_hr', 'elevation_gain_m', 'avg_power_w', 'avg_speed_kmh', 'rpe', 'notes', 'details', 'source', 'health_connect_record_id', 'created_at'],
}
