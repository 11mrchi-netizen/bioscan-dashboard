alter table public.lab_draws
  add column draw_time time,
  add column fasted boolean,
  add column hours_since_training numeric;
