-- Heart-rate zones by method (android-app domain/HeartRateZones.kt): the person's default way of placing the zone edges.
--   auto  a run with a recent lactate-threshold estimate uses it, anything else heart-rate reserve, else % of maximum
--   lthr  % of lactate-threshold heart rate (Zepp's estimate; running only, others fall back as in auto)
--   hrr   % of heart-rate reserve (resting + share x (maximum - resting))
--   max   % of maximum heart rate (the app's only method before this)
-- A session's heart-rate chart opens on this method and can be switched there for that view.
--
-- training_settings was created outside this repository's migrations (live since the training planner); the column is
-- added only if missing, so this applies cleanly either way.
--
-- Rollback: alter table public.training_settings drop column hr_zone_method;

alter table public.training_settings
  add column if not exists hr_zone_method text not null default 'auto'
    check (hr_zone_method in ('auto', 'lthr', 'hrr', 'max'));
