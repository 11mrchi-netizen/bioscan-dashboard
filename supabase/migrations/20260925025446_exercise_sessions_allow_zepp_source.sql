-- DAV-115: zepp-extract's reconciliation inserts source='zepp' for workouts
-- Health Connect never received; the existing check only allowed the two
-- sources that predated this integration.
alter table exercise_sessions drop constraint exercise_sessions_source_check;
alter table exercise_sessions add constraint exercise_sessions_source_check
  check (source = any (array['manual', 'health_connect', 'zepp']));
