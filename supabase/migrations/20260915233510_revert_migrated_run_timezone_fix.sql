-- Reverts fix_migrated_run_nominal_timezone: that "fix" was wrong. Confirmed
-- live (a real Log-feed screenshot showed the migrated run shift from
-- "07:00 · 15 SEP" to "23:00 · 14 SEP" after the change) that this whole
-- app's Log feed intentionally does NAIVE, non-zone-aware timestamp parsing
-- everywhere (OffsetDateTime.parse(iso).toLocalDateTime(), confirmed against
-- domain/Log.kt) -- every timestamptz in this app stores wall-clock digits
-- "as if UTC" and reads them back the same naive way, consistently. The
-- original G3 migration's "(date + 07:00) at time zone 'UTC'" was already
-- correct under that convention. The real bug is in SessionDetailScreen.kt,
-- which used proper zone-aware conversion instead of matching the app's
-- existing naive convention -- fixed there instead, in code, not data.
update public.exercise_sessions
set
  start_time = (start_time at time zone 'Asia/Taipei') at time zone 'UTC',
  end_time = (end_time at time zone 'Asia/Taipei') at time zone 'UTC'
where source = 'manual';
