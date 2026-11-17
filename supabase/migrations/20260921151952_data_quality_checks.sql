
CREATE OR REPLACE FUNCTION public.run_data_quality_checks()
RETURNS TABLE(
  check_id    text,
  severity    text,
  domain      text,
  table_name  text,
  column_name text,
  record_id   bigint,
  record_date date,
  message     text
)
LANGUAGE sql STABLE SECURITY INVOKER
AS $$

-- ===== 1. IMPOSSIBLE VALUE RANGES =====

-- Resting HR
SELECT 'range.rhr', 'error', 'wearable', 'wearable_daily', 'rhr',
       id, date,
       'Resting HR ' || rhr || ' bpm outside 25-120 range'
FROM wearable_daily
WHERE user_id = (SELECT auth.uid())
  AND rhr IS NOT NULL AND (rhr < 25 OR rhr > 120)

UNION ALL

-- HRV
SELECT 'range.hrv', 'error', 'wearable', 'wearable_daily', 'hrv',
       id, date,
       'HRV ' || hrv || ' ms outside 1-300 range'
FROM wearable_daily
WHERE user_id = (SELECT auth.uid())
  AND hrv IS NOT NULL AND (hrv < 1 OR hrv > 300)

UNION ALL

-- Steps
SELECT 'range.steps', 'error', 'wearable', 'wearable_daily', 'steps',
       id, date,
       'Steps ' || steps || ' outside 0-200000 range'
FROM wearable_daily
WHERE user_id = (SELECT auth.uid())
  AND steps IS NOT NULL AND (steps < 0 OR steps > 200000)

UNION ALL

-- VO2max
SELECT 'range.vo2max', 'error', 'wearable', 'wearable_daily', 'vo2max',
       id, date,
       'VO2max ' || vo2max || ' outside 10-90 range'
FROM wearable_daily
WHERE user_id = (SELECT auth.uid())
  AND vo2max IS NOT NULL AND (vo2max < 10 OR vo2max > 90)

UNION ALL

-- SpO2
SELECT 'range.spo2', 'error', 'wearable', 'wearable_daily', 'spo2_avg',
       id, date,
       'SpO2 ' || spo2_avg || '% outside 50-100 range'
FROM wearable_daily
WHERE user_id = (SELECT auth.uid())
  AND spo2_avg IS NOT NULL AND (spo2_avg < 50 OR spo2_avg > 100)

UNION ALL

-- Sleep hours
SELECT 'range.sleep_hours', 'error', 'sleep', 'sleep_daily', 'hours',
       id, date,
       'Sleep ' || hours || ' hours outside 0-24 range'
FROM sleep_daily
WHERE user_id = (SELECT auth.uid())
  AND hours IS NOT NULL AND (hours < 0 OR hours > 24)

UNION ALL

-- Body weight
SELECT 'range.weight', 'error', 'wearable', 'body_metrics', 'weight_kg',
       id, date,
       'Weight ' || weight_kg || ' kg outside 20-300 range'
FROM body_metrics
WHERE user_id = (SELECT auth.uid())
  AND weight_kg IS NOT NULL AND (weight_kg < 20 OR weight_kg > 300)

UNION ALL

-- Body fat
SELECT 'range.body_fat', 'error', 'wearable', 'body_metrics', 'body_fat_pct',
       id, date,
       'Body fat ' || body_fat_pct || '% outside 1-70 range'
FROM body_metrics
WHERE user_id = (SELECT auth.uid())
  AND body_fat_pct IS NOT NULL AND (body_fat_pct < 1 OR body_fat_pct > 70)

UNION ALL

-- Exercise distance
SELECT 'range.exercise_distance', 'error', 'exercise', 'exercise_sessions', 'distance_km',
       id, start_time::date,
       'Distance ' || distance_km || ' km outside 0-500 range'
FROM exercise_sessions
WHERE user_id = (SELECT auth.uid())
  AND distance_km IS NOT NULL AND (distance_km < 0 OR distance_km > 500)

UNION ALL

-- Exercise duration
SELECT 'range.exercise_duration', 'error', 'exercise', 'exercise_sessions', 'duration_min',
       id, start_time::date,
       'Duration ' || duration_min || ' min outside 0-1440 range'
FROM exercise_sessions
WHERE user_id = (SELECT auth.uid())
  AND duration_min IS NOT NULL AND (duration_min < 0 OR duration_min > 1440)

UNION ALL

-- Exercise avg HR
SELECT 'range.exercise_hr', 'error', 'exercise', 'exercise_sessions', 'avg_hr',
       id, start_time::date,
       'Avg HR ' || avg_hr || ' bpm outside 25-250 range'
FROM exercise_sessions
WHERE user_id = (SELECT auth.uid())
  AND avg_hr IS NOT NULL AND (avg_hr < 25 OR avg_hr > 250)

UNION ALL

-- Meal calories
SELECT 'range.meal_calories', 'error', 'nutrition', 'meals', 'calories',
       id, logged_at::date,
       'Meal calories ' || calories || ' outside 0-10000 range'
FROM meals
WHERE user_id = (SELECT auth.uid())
  AND calories IS NOT NULL AND (calories < 0 OR calories > 10000)

-- ===== 2. DUPLICATE RECORDS =====

UNION ALL

-- Sleep duplicates (multiple rows same user+date)
SELECT 'dup.sleep', 'warning', 'sleep', 'sleep_daily', NULL::text,
       NULL::bigint, date,
       count(*) || ' sleep records for same date'
FROM sleep_daily
WHERE user_id = (SELECT auth.uid())
GROUP BY date
HAVING count(*) > 1

UNION ALL

-- Wearable daily duplicates
SELECT 'dup.wearable', 'error', 'wearable', 'wearable_daily', NULL::text,
       NULL::bigint, date,
       count(*) || ' wearable_daily records for same date'
FROM wearable_daily
WHERE user_id = (SELECT auth.uid())
GROUP BY date
HAVING count(*) > 1

-- ===== 3. MISSING PROVENANCE =====

UNION ALL

-- Null source on exercise_sessions
SELECT 'prov.null_source', 'warning', 'exercise', 'exercise_sessions', 'source',
       id, start_time::date,
       'Exercise session has no source attribution'
FROM exercise_sessions
WHERE user_id = (SELECT auth.uid())
  AND source IS NULL

UNION ALL

-- Missing HC record ID
SELECT 'prov.missing_hc_id', 'warning', 'exercise', 'exercise_sessions', 'health_connect_record_id',
       id, start_time::date,
       'Health Connect session missing record_id'
FROM exercise_sessions
WHERE user_id = (SELECT auth.uid())
  AND source = 'health_connect'
  AND health_connect_record_id IS NULL

-- ===== 4. TEMPORAL ANOMALIES =====

UNION ALL

-- Future dates in wearable_daily
SELECT 'time.future', 'error', 'wearable', 'wearable_daily', NULL::text,
       id, date,
       'Wearable record has future date'
FROM wearable_daily
WHERE user_id = (SELECT auth.uid())
  AND date > current_date

UNION ALL

-- Future dates in sleep_daily
SELECT 'time.future', 'error', 'sleep', 'sleep_daily', NULL::text,
       id, date,
       'Sleep record has future date'
FROM sleep_daily
WHERE user_id = (SELECT auth.uid())
  AND date > current_date

UNION ALL

-- Future dates in exercise_sessions
SELECT 'time.future', 'error', 'exercise', 'exercise_sessions', NULL::text,
       id, start_time::date,
       'Exercise session has future date'
FROM exercise_sessions
WHERE user_id = (SELECT auth.uid())
  AND start_time > now()

UNION ALL

-- Stale sync (wrapped in subquery for ORDER BY/LIMIT)
SELECT * FROM (
  SELECT 'time.stale_sync'::text, 'warning'::text, 'sync'::text, 'sync_log'::text, NULL::text,
         NULL::bigint, synced_at::date,
         'Last sync was ' || (extract(epoch from (now() - synced_at))::int / 3600) || ' hours ago'
  FROM sync_log
  WHERE user_id = (SELECT auth.uid()) AND sync_source = 'health_connect'
    AND synced_at < now() - interval '48 hours'
  ORDER BY synced_at DESC
  LIMIT 1
) stale_sync

-- ===== 5. MISSING VS ZERO CONFUSION =====

UNION ALL

-- Zero steps with other data present
SELECT 'zero.steps_with_data', 'warning', 'wearable', 'wearable_daily', 'steps',
       id, date,
       'Steps=0 but rhr/hrv present — likely missing not zero'
FROM wearable_daily
WHERE user_id = (SELECT auth.uid())
  AND steps = 0
  AND (rhr IS NOT NULL OR hrv IS NOT NULL)

UNION ALL

-- Zero calories on meals
SELECT 'zero.meal_calories', 'warning', 'nutrition', 'meals', 'calories',
       id, logged_at::date,
       'Meal has 0 calories — should probably be NULL (unknown)'
FROM meals
WHERE user_id = (SELECT auth.uid())
  AND calories = 0

-- ===== 6. CROSS-TABLE CONSISTENCY =====

UNION ALL

-- Active > total calories
SELECT 'consist.cal_active_gt_total', 'error', 'wearable', 'wearable_daily', NULL::text,
       id, date,
       'Active calories (' || calories_active || ') > total (' || calories_total || ')'
FROM wearable_daily
WHERE user_id = (SELECT auth.uid())
  AND calories_active IS NOT NULL AND calories_total IS NOT NULL
  AND calories_active > calories_total

UNION ALL

-- Sleep stages exceed total (convert min to hours for comparison)
SELECT 'consist.sleep_stages_gt_total', 'error', 'sleep', 'sleep_daily', NULL::text,
       id, date,
       'Sleep stages (' || round((coalesce(deep_min,0) + coalesce(light_min,0) + coalesce(rem_min,0))/60.0, 1) || 'h) > total (' || hours || 'h)'
FROM sleep_daily
WHERE user_id = (SELECT auth.uid())
  AND hours IS NOT NULL
  AND (coalesce(deep_min,0) + coalesce(light_min,0) + coalesce(rem_min,0)) / 60.0 > hours

$$;
