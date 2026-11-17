-- Phase G4 finding: the Step G3 migration's nominal 07:00 was written as
-- "07:00 UTC" instead of "07:00 local" (Asia/Taipei), so migrated rows
-- displayed as 15:00 once anything did real timezone-aware conversion
-- (SessionDetailScreen's summary card surfaced this; the Log feed's naive
-- non-zone-aware display happened to hide it). Reinterprets the existing
-- wall-clock digits (07:00 on the right date -- that part was always
-- correct) as Asia/Taipei instead of UTC, for the 19 migrated rows only.
update public.exercise_sessions
set
  start_time = (start_time at time zone 'UTC') at time zone 'Asia/Taipei',
  end_time = (end_time at time zone 'UTC') at time zone 'Asia/Taipei'
where source = 'manual';
