-- Body-composition inputs on the profile: height (needed by every impedance equation; body_metrics.height_cm is filled on
-- 1 of 119 days) and an athlete flag (impedance equations are validated on the general adult population and read high on
-- athletes). Both optional; the app falls back to the latest body_metrics.height_cm when height_cm is null.
--
-- Rollback: alter table public.user_profile drop column athlete_mode, drop column height_cm;

alter table public.user_profile
  add column if not exists height_cm numeric check (height_cm is null or height_cm between 100 and 230),
  add column if not exists athlete_mode boolean not null default false;
