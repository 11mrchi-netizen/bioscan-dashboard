-- Routine intelligence, phase R6 (docs/routine-intelligence/01-research-and-plan.md): self-experiments on routines.
--
-- experiments.primary_metric and secondary_metrics accept three routine outcomes besides the eight existing ones, so a
-- habit can itself be what an experiment measures: screen_bed_min (phone in the hour before sleep), screen_pm_social
-- (social and video apps after 21:00) and outdoor_daylight_min (estimated minutes outdoors in daylight). The series come
-- from phone_usage_daily, phone_app_usage_daily and mobility_daily (analysis-core/routineExposures.ts).
--
-- Adherence needs no schema: a daily randomised experiment's protocol.behaviour names a frame series (screen_bed_any,
-- screen_pm_social_any, night_phone, active_commute, daylight_day), and the analysis checks each with-day against what the
-- phone measured.
--
-- Rollback: restore both checks to the eight outcomes of 20261013_experiments.sql (after removing any experiment that
--           uses a routine outcome).

alter table public.experiments drop constraint experiments_primary_metric_check;
alter table public.experiments add constraint experiments_primary_metric_check
  check (primary_metric in ('hrv', 'rhr', 'sleep_hours', 'deep_rem_min', 'energy', 'mood', 'stress', 'soreness',
                            'screen_bed_min', 'screen_pm_social', 'outdoor_daylight_min'));

alter table public.experiments drop constraint experiments_secondary_metrics_check;
alter table public.experiments add constraint experiments_secondary_metrics_check
  check (cardinality(secondary_metrics) <= 3 and secondary_metrics <@ array['hrv', 'rhr', 'sleep_hours', 'deep_rem_min',
         'energy', 'mood', 'stress', 'soreness', 'screen_bed_min', 'screen_pm_social', 'outdoor_daylight_min']::text[]);
