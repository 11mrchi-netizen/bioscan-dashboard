-- DAV-124: exercise_sessions is the only table that tracks where a row
-- came from (source + health_connect_record_id). Every other analytical
-- table has exactly one real writer today (confirmed via grep):
-- wearable_daily/sleep_daily/body_metrics <- HealthConnectDailySyncRepository
-- meals/hydration_daily <- AddEntryRepository (manual, Log tab)
-- Adding an explicit column now, defaulted to each table's real current
-- writer, so a future second pipeline (e.g. direct Amazfit ingestion) has
-- somewhere to record which source wrote a given row instead of it being
-- implicit and eventually ambiguous.

alter table public.wearable_daily
  add column if not exists source text not null default 'health_connect';

alter table public.sleep_daily
  add column if not exists source text not null default 'health_connect';

alter table public.body_metrics
  add column if not exists source text not null default 'health_connect';

alter table public.meals
  add column if not exists source text not null default 'manual';

alter table public.hydration_daily
  add column if not exists source text not null default 'manual';
