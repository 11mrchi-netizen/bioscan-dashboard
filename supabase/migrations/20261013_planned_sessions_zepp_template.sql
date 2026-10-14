-- The Zepp custom-workout template made for a planned strength session (zepp-training function).
-- zepp_pushed_hash = hash of what was sent, so an unchanged session is not pushed again.
alter table planned_sessions
  add column if not exists zepp_template_id text,
  add column if not exists zepp_pushed_hash text,
  add column if not exists zepp_pushed_at timestamptz;
