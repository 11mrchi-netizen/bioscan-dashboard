-- Stool log: does it feel empty afterwards (the sense of complete evacuation)? Asked in the stool form; null when not answered.
-- 'yes' = feels empty, 'partly' = not quite, 'no' = feels there is more.
--
-- Rollback: alter table public.stool_log drop column complete_evacuation;

alter table public.stool_log add column if not exists complete_evacuation text
  check (complete_evacuation in ('yes', 'partly', 'no'));
