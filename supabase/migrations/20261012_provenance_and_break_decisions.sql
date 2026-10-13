-- Analysis Layer 3, Phase 1 (R1): per-field provenance and user decisions on detected series breaks.
-- Rollback: drop table public.daily_field_provenance; drop table public.series_break_decisions;
--
-- daily_field_provenance is a side table (not a column on wearable_daily/sleep_daily) because the Health Connect sync
-- writes each metric with its own single-column upsert; a shared origin column would be overwritten by whichever job ran
-- last. One row per (user, date, field) lets each writer record its own origin without touching the others.

create table public.daily_field_provenance (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  date date not null,
  field text not null,                       -- 'wearable_daily.hrv', 'sleep_daily.deep_min', ...
  origin text not null,                      -- Health Connect dataOrigin package name, 'zepp', 'manual', or 'unknown'
  writer text not null,                      -- 'health-connect-sync' | 'zepp-extract' | ...
  written_at timestamptz not null default now(),
  primary key (user_id, date, field)
);

alter table public.daily_field_provenance enable row level security;

create policy "select own daily_field_provenance" on public.daily_field_provenance
  for select using ((select auth.uid()) = user_id);
create policy "insert own daily_field_provenance" on public.daily_field_provenance
  for insert with check ((select auth.uid()) = user_id);
create policy "update own daily_field_provenance" on public.daily_field_provenance
  for update using ((select auth.uid()) = user_id);

comment on table public.daily_field_provenance is 'Which origin wrote each daily field (R1). History before 2026-10-12 is unknown and is not backfilled.';

-- A detected statistical break is only a candidate: a genuine physiological change looks the same as a device change.
-- The user (or provenance evidence) decides which candidates restart a personal baseline.
create table public.series_break_decisions (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  metric text not null,
  break_date date not null,
  decision text not null check (decision in ('source_change', 'real_change', 'ignore')),
  decided_at timestamptz not null default now(),
  primary key (user_id, metric, break_date)
);

alter table public.series_break_decisions enable row level security;

create policy "select own series_break_decisions" on public.series_break_decisions
  for select using ((select auth.uid()) = user_id);
create policy "insert own series_break_decisions" on public.series_break_decisions
  for insert with check ((select auth.uid()) = user_id);
create policy "update own series_break_decisions" on public.series_break_decisions
  for update using ((select auth.uid()) = user_id);
create policy "delete own series_break_decisions" on public.series_break_decisions
  for delete using ((select auth.uid()) = user_id);
