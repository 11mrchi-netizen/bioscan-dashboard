alter table public.exercise_sessions
  add constraint exercise_sessions_user_hc_record_unique
  unique (user_id, health_connect_record_id);
