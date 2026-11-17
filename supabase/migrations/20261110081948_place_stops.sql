-- Routine: one-off stops (docs/routine-intelligence/README.md, "ME › ROUTINE"). A place the person marks as a one-off
-- stop (a restaurant, a shop) keeps the visits it had, but visits that start after stop_since never join it: the next
-- stay nearby starts a place of its own, which can be named again. Null = a place visited again, as before.
--
-- Rollback: alter table public.places drop column stop_since;

alter table public.places add column if not exists stop_since timestamptz;
comment on column public.places.stop_since is 'Set when the person marks the place a one-off stop (a restaurant, a shop): visits that start after it never join it; null = a place visited again.';
