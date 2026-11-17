CREATE OR REPLACE VIEW v_conditioning_drills AS
SELECT
  es.id                                             AS session_id,
  es.start_time::date                               AS date,
  es.start_time                                     AS notion_start,
  es.end_time                                       AS notion_end,
  es.details->>'template'                           AS template,
  es.details->>'intensity'                          AS session_intensity,
  es.details->>'notes'                              AS session_notes,
  drill->>'name'                                    AS drill_name,
  drill->>'type'                                    AS drill_type,
  drill->>'focus'                                   AS drill_focus,
  drill->>'description'                             AS drill_description,
  drill->'performance'                              AS performance,
  drill->>'notes'                                   AS drill_notes,
  -- Wearable linkage (null until linked)
  (es.details->>'wearable_session_id')::bigint      AS wearable_session_id,
  wes.start_time                                    AS actual_start,
  wes.end_time                                      AS actual_end,
  wes.duration_min                                  AS actual_duration_min,
  wes.avg_hr,
  wes.max_hr,
  wes.calories_active
FROM exercise_sessions es
CROSS JOIN LATERAL jsonb_array_elements(
  CASE jsonb_typeof(es.details->'drills')
    WHEN 'array' THEN es.details->'drills'
    ELSE '[]'::jsonb
  END
) AS drill
LEFT JOIN exercise_sessions wes
  ON wes.id = (es.details->>'wearable_session_id')::bigint
WHERE es.source = 'notion'
  AND es.type   = 'other';
