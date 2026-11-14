-- zepp_workout_detail.summary: the workout summary (decoded->summary) as its own stored column.
--
-- The Training and Running pages and the heart-rate zone anchors read every workout's summary. Selecting
-- decoded->summary made Postgres read and unpack each row's whole decoded document (per-second heart rate, pace,
-- route ...) just to return a few scalars: about 3 s for ~400 workouts, measured on the phone. A generated column is
-- computed once when decoded is written (zepp-extract writes decoded, never this column) and is read on its own.
--
-- Rollback: alter table public.zepp_workout_detail drop column summary;

alter table public.zepp_workout_detail
  add column if not exists summary jsonb generated always as (decoded -> 'summary') stored;
