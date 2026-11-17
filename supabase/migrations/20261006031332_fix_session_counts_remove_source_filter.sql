DROP VIEW IF EXISTS v_training_block_metrics;

CREATE OR REPLACE VIEW v_training_block_metrics AS
WITH tb_cycles AS (
  SELECT
    training_cycles.id,
    training_cycles.user_id,
    training_cycles.start_date,
    training_cycles.end_date,
    (training_cycles.notes::jsonb -> 'notion_import_focus') ->> 'template' AS template,
    (training_cycles.notes::jsonb -> 'notion_import_focus') ->> 'tb_category' AS tb_category,
    (training_cycles.notes::jsonb -> 'notion_import_focus') ->> 'primary' AS primary_goal,
    (training_cycles.notes::jsonb -> 'notion_import_focus') ->> 'progression' AS progression,
    training_cycles.end_date - training_cycles.start_date AS block_days,
    training_cycles.start_date + (training_cycles.end_date - training_cycles.start_date) / 2 AS mid_date
  FROM training_cycles
  WHERE training_cycles.notes IS NOT NULL
    AND training_cycles.notes::jsonb ? 'notion_import_focus'
    AND training_cycles.end_date IS NOT NULL
),
notion_session_counts AS (
  SELECT
    tc.id AS cycle_id,
    count(*) FILTER (WHERE es.type = 'strength') AS strength_sessions,
    count(*) FILTER (WHERE es.type = ANY (ARRAY['other', 'conditioning'])) AS conditioning_sessions,
    count(*) FILTER (WHERE es.type = 'run') AS endurance_sessions
  FROM tb_cycles tc
  JOIN exercise_sessions es
    ON es.user_id = tc.user_id
    AND es.start_time::date >= tc.start_date
    AND es.start_time::date <= tc.end_date
  GROUP BY tc.id
),
conditioning_hr AS (
  SELECT
    tc.id AS cycle_id,
    avg(wes.avg_hr) AS avg_conditioning_hr,
    avg(wes.avg_hr) FILTER (WHERE es.start_time::date < tc.mid_date) AS early_hr,
    avg(wes.avg_hr) FILTER (WHERE es.start_time::date >= tc.mid_date) AS late_hr,
    max(wes.max_hr) AS peak_hr,
    count(wes.id) AS linked_wearable_count
  FROM tb_cycles tc
  JOIN exercise_sessions es
    ON es.user_id = tc.user_id
    AND es.start_time::date >= tc.start_date
    AND es.start_time::date <= tc.end_date
    AND es.type = ANY (ARRAY['other', 'conditioning'])
    AND (es.details ->> 'wearable_session_id') IS NOT NULL
    AND (es.details ->> 'wearable_session_id') ~ '^\d+$'
  JOIN exercise_sessions wes
    ON wes.id = (es.details ->> 'wearable_session_id')::bigint
    AND wes.avg_hr IS NOT NULL
  GROUP BY tc.id, tc.mid_date
),
daily_metrics AS (
  SELECT
    tc.id AS cycle_id,
    avg(wd.rhr) FILTER (WHERE wd.rhr IS NOT NULL) AS rhr_avg,
    avg(wd.hrv) FILTER (WHERE wd.hrv IS NOT NULL) AS hrv_avg,
    avg(wd.spo2_avg) FILTER (WHERE wd.spo2_avg IS NOT NULL) AS spo2_avg,
    avg(wd.rhr) FILTER (WHERE wd.rhr IS NOT NULL AND wd.date <= tc.start_date + 13) AS rhr_start,
    avg(wd.hrv) FILTER (WHERE wd.hrv IS NOT NULL AND wd.date <= tc.start_date + 13) AS hrv_start,
    avg(wd.rhr) FILTER (WHERE wd.rhr IS NOT NULL AND wd.date >= tc.end_date - 13) AS rhr_end,
    avg(wd.hrv) FILTER (WHERE wd.hrv IS NOT NULL AND wd.date >= tc.end_date - 13) AS hrv_end,
    (array_agg(wd.vo2max ORDER BY wd.date) FILTER (WHERE wd.vo2max IS NOT NULL))[1] AS vo2max_first,
    (array_agg(wd.vo2max ORDER BY wd.date DESC) FILTER (WHERE wd.vo2max IS NOT NULL))[1] AS vo2max_last,
    count(wd.rhr) FILTER (WHERE wd.rhr IS NOT NULL) AS rhr_days_count,
    count(wd.hrv) FILTER (WHERE wd.hrv IS NOT NULL) AS hrv_days_count,
    count(wd.vo2max) FILTER (WHERE wd.vo2max IS NOT NULL) AS vo2max_days_count
  FROM tb_cycles tc
  JOIN wearable_daily wd
    ON wd.user_id = tc.user_id
    AND wd.date >= tc.start_date
    AND wd.date <= tc.end_date
  GROUP BY tc.id, tc.start_date, tc.end_date
),
cycle_stats AS (
  SELECT
    tc.id, tc.user_id, tc.start_date, tc.end_date,
    tc.template, tc.tb_category, tc.primary_goal, tc.progression, tc.block_days,
    COALESCE(nsc.strength_sessions, 0) AS strength_sessions,
    COALESCE(nsc.conditioning_sessions, 0) AS conditioning_sessions,
    COALESCE(nsc.endurance_sessions, 0) AS endurance_sessions,
    chr.avg_conditioning_hr, chr.early_hr, chr.late_hr, chr.peak_hr,
    COALESCE(chr.linked_wearable_count, 0) AS linked_wearable_count,
    dm.rhr_avg, dm.rhr_start, dm.rhr_end,
    dm.hrv_avg, dm.hrv_start, dm.hrv_end,
    dm.vo2max_first, dm.vo2max_last, dm.spo2_avg,
    COALESCE(dm.rhr_days_count, 0) AS rhr_days_count,
    COALESCE(dm.hrv_days_count, 0) AS hrv_days_count,
    COALESCE(dm.vo2max_days_count, 0) AS vo2max_days_count
  FROM tb_cycles tc
  LEFT JOIN notion_session_counts nsc ON nsc.cycle_id = tc.id
  LEFT JOIN conditioning_hr chr ON chr.cycle_id = tc.id
  LEFT JOIN daily_metrics dm ON dm.cycle_id = tc.id
)
SELECT
  id, user_id, start_date, end_date,
  template, tb_category, primary_goal, progression, block_days,
  strength_sessions, conditioning_sessions, endurance_sessions,
  avg_conditioning_hr, early_hr, late_hr, peak_hr, linked_wearable_count,
  rhr_avg, rhr_start, rhr_end,
  hrv_avg, hrv_start, hrv_end,
  vo2max_first, vo2max_last, spo2_avg,
  rhr_days_count, hrv_days_count, vo2max_days_count,
  round(late_hr - early_hr, 1) AS conditioning_hr_delta,
  round(rhr_end - rhr_start, 1) AS rhr_delta,
  round(hrv_end - hrv_start, 1) AS hrv_delta,
  round(vo2max_last - vo2max_first, 1) AS vo2max_delta,
  lag(avg_conditioning_hr) OVER w AS prev_avg_conditioning_hr,
  lag(rhr_avg) OVER w AS prev_rhr_avg,
  lag(hrv_avg) OVER w AS prev_hrv_avg,
  lag(vo2max_last) OVER w AS prev_vo2max
FROM cycle_stats cs
WINDOW w AS (PARTITION BY template ORDER BY start_date);
