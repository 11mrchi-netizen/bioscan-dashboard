CREATE OR REPLACE VIEW v_training_block_metrics AS
WITH
tb_cycles AS (
  SELECT
    id, user_id, start_date, end_date,
    focus->>'template'    AS template,
    focus->>'tb_category' AS tb_category,
    focus->>'primary'     AS primary_goal,
    focus->>'progression' AS progression,
    (end_date - start_date)                    AS block_days,
    start_date + (end_date - start_date) / 2   AS mid_date
  FROM training_cycles
  WHERE focus->>'source' = 'notion'
    AND end_date IS NOT NULL
),
notion_session_counts AS (
  SELECT
    tc.id AS cycle_id,
    COUNT(*) FILTER (WHERE es.type = 'strength')               AS strength_sessions,
    COUNT(*) FILTER (WHERE es.type IN ('other','conditioning')) AS conditioning_sessions,
    COUNT(*) FILTER (WHERE es.type = 'run')                    AS endurance_sessions
  FROM tb_cycles tc
  JOIN exercise_sessions es
    ON es.user_id = tc.user_id
   AND es.start_time::date BETWEEN tc.start_date AND tc.end_date
   AND es.source = 'notion'
  GROUP BY tc.id
),
conditioning_hr AS (
  SELECT
    tc.id AS cycle_id,
    AVG(wes.avg_hr)                                                   AS avg_conditioning_hr,
    AVG(wes.avg_hr) FILTER (WHERE es.start_time::date < tc.mid_date)  AS early_hr,
    AVG(wes.avg_hr) FILTER (WHERE es.start_time::date >= tc.mid_date) AS late_hr,
    MAX(wes.max_hr)                                                    AS peak_hr,
    COUNT(wes.id)                                                      AS linked_wearable_count
  FROM tb_cycles tc
  JOIN exercise_sessions es
    ON es.user_id = tc.user_id
   AND es.start_time::date BETWEEN tc.start_date AND tc.end_date
   AND es.source = 'notion'
   AND es.type IN ('other','conditioning')
   AND (es.details->>'wearable_session_id') IS NOT NULL
   AND (es.details->>'wearable_session_id') ~ '^\d+$'
  JOIN exercise_sessions wes
    ON wes.id = (es.details->>'wearable_session_id')::bigint
   AND wes.avg_hr IS NOT NULL
  GROUP BY tc.id, tc.mid_date
),
daily_metrics AS (
  SELECT
    tc.id AS cycle_id,
    AVG(wd.rhr)      FILTER (WHERE wd.rhr IS NOT NULL)      AS rhr_avg,
    AVG(wd.hrv)      FILTER (WHERE wd.hrv IS NOT NULL)      AS hrv_avg,
    AVG(wd.spo2_avg) FILTER (WHERE wd.spo2_avg IS NOT NULL) AS spo2_avg,
    AVG(wd.rhr) FILTER (WHERE wd.rhr IS NOT NULL AND wd.date <= tc.start_date + 13) AS rhr_start,
    AVG(wd.hrv) FILTER (WHERE wd.hrv IS NOT NULL AND wd.date <= tc.start_date + 13) AS hrv_start,
    AVG(wd.rhr) FILTER (WHERE wd.rhr IS NOT NULL AND wd.date >= tc.end_date - 13)   AS rhr_end,
    AVG(wd.hrv) FILTER (WHERE wd.hrv IS NOT NULL AND wd.date >= tc.end_date - 13)   AS hrv_end,
    (ARRAY_AGG(wd.vo2max ORDER BY wd.date ASC)  FILTER (WHERE wd.vo2max IS NOT NULL))[1] AS vo2max_first,
    (ARRAY_AGG(wd.vo2max ORDER BY wd.date DESC) FILTER (WHERE wd.vo2max IS NOT NULL))[1] AS vo2max_last,
    COUNT(wd.rhr)    FILTER (WHERE wd.rhr IS NOT NULL)    AS rhr_days_count,
    COUNT(wd.hrv)    FILTER (WHERE wd.hrv IS NOT NULL)    AS hrv_days_count,
    COUNT(wd.vo2max) FILTER (WHERE wd.vo2max IS NOT NULL) AS vo2max_days_count
  FROM tb_cycles tc
  JOIN wearable_daily wd
    ON wd.user_id = tc.user_id
   AND wd.date BETWEEN tc.start_date AND tc.end_date
  GROUP BY tc.id, tc.start_date, tc.end_date
),
cycle_stats AS (
  SELECT
    tc.id, tc.user_id, tc.start_date, tc.end_date,
    tc.template, tc.tb_category, tc.primary_goal,
    tc.progression, tc.block_days,
    COALESCE(nsc.strength_sessions, 0)     AS strength_sessions,
    COALESCE(nsc.conditioning_sessions, 0) AS conditioning_sessions,
    COALESCE(nsc.endurance_sessions, 0)    AS endurance_sessions,
    chr.avg_conditioning_hr,
    chr.early_hr,
    chr.late_hr,
    chr.peak_hr,
    COALESCE(chr.linked_wearable_count, 0) AS linked_wearable_count,
    dm.rhr_avg, dm.rhr_start, dm.rhr_end,
    dm.hrv_avg, dm.hrv_start, dm.hrv_end,
    dm.vo2max_first, dm.vo2max_last,
    dm.spo2_avg,
    COALESCE(dm.rhr_days_count, 0)    AS rhr_days_count,
    COALESCE(dm.hrv_days_count, 0)    AS hrv_days_count,
    COALESCE(dm.vo2max_days_count, 0) AS vo2max_days_count
  FROM tb_cycles tc
  LEFT JOIN notion_session_counts nsc ON nsc.cycle_id = tc.id
  LEFT JOIN conditioning_hr chr       ON chr.cycle_id = tc.id
  LEFT JOIN daily_metrics dm          ON dm.cycle_id = tc.id
)
SELECT
  cs.*,
  ROUND((cs.late_hr - cs.early_hr)::numeric, 1)         AS conditioning_hr_delta,
  ROUND((cs.rhr_end - cs.rhr_start)::numeric, 1)        AS rhr_delta,
  ROUND((cs.hrv_end - cs.hrv_start)::numeric, 1)        AS hrv_delta,
  ROUND((cs.vo2max_last - cs.vo2max_first)::numeric, 1)  AS vo2max_delta,
  LAG(cs.avg_conditioning_hr) OVER w AS prev_avg_conditioning_hr,
  LAG(cs.rhr_avg)             OVER w AS prev_rhr_avg,
  LAG(cs.hrv_avg)             OVER w AS prev_hrv_avg,
  LAG(cs.vo2max_last)         OVER w AS prev_vo2max
FROM cycle_stats cs
WINDOW w AS (PARTITION BY cs.template ORDER BY cs.start_date);
