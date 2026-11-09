alter table public.exercise_sessions
  add constraint exercise_sessions_route_type_check
  check (type <> 'run' or details->>'route_type' is null
         or details->>'route_type' = any (array['road','trail','mixed','track']));

alter table public.exercise_sessions
  add constraint exercise_sessions_run_type_check
  check (type <> 'run' or details->>'run_type' is null
         or details->>'run_type' = any (array['easy','tempo','long','hills','intervals','race','recovery']));
