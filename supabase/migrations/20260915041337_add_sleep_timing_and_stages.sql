-- Wellness Project's list_sleep already returns bedtime/wake timestamps and
-- deep/REM/light stage minutes (confirmed via direct tool call) -- never
-- captured here before. This unlocks SRI (Sleep Regularity Index) computation,
-- flagged as a real buildable-today item in feasibility-assessment.md Category 2.
alter table sleep_daily add column bedtime timestamptz;
alter table sleep_daily add column wake_time timestamptz;
alter table sleep_daily add column deep_min numeric;
alter table sleep_daily add column rem_min numeric;
alter table sleep_daily add column light_min numeric;
