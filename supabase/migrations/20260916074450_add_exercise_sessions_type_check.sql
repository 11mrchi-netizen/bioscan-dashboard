alter table public.exercise_sessions
  add constraint exercise_sessions_type_check
  check (type = any (array['run','walk','hike','ride','swim','strength','yoga','other']));
