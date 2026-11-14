-- Widen zepp_raw_extracts so a per-workout detail.json fetch (multiple per day)
-- can coexist with the existing one-row-per-day-per-metric summaries.
alter table zepp_raw_extracts add column track_id text;
alter table zepp_raw_extracts drop constraint zepp_raw_extracts_metric_date_uq;
alter table zepp_raw_extracts add constraint zepp_raw_extracts_metric_date_track_uq
  unique (user_id, metric, query_date, track_id);

-- Per-workout detail payload from /v1/sport/run/detail.json, reconciled against
-- exercise_sessions rather than duplicating them (DAV-115).
create table zepp_workout_detail (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id),
  exercise_session_id bigint references exercise_sessions(id),
  zepp_track_id text not null,
  zepp_source text,
  start_time timestamptz not null,
  end_time timestamptz not null,
  sport_type text,
  raw jsonb not null,
  decoded jsonb,
  fetched_at timestamptz not null default now(),
  unique (user_id, zepp_track_id)
);

alter table zepp_workout_detail enable row level security;

create policy "Users can read own zepp workout detail" on zepp_workout_detail
  for select using ((select auth.uid()) = user_id);
create policy "Users can insert own zepp workout detail" on zepp_workout_detail
  for insert with check ((select auth.uid()) = user_id);
create policy "Users can update own zepp workout detail" on zepp_workout_detail
  for update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "Users can delete own zepp workout detail" on zepp_workout_detail
  for delete using ((select auth.uid()) = user_id);

-- Incremental-sync cursor + last-error status, read by SettingsScreen's ZEPP card.
-- Persisted (not the in-memory HealthConnectSyncStatus pattern) because a Zepp
-- apptoken expires unattended after ~30 days and the cursor must survive restarts
-- regardless -- see docs/zepp-integration plan.
create table zepp_sync_state (
  user_id uuid primary key default auth.uid() references auth.users(id),
  last_synced_track_id text,
  last_synced_at timestamptz,
  last_error text,
  last_error_at timestamptz
);

alter table zepp_sync_state enable row level security;

create policy "Users can read own zepp sync state" on zepp_sync_state
  for select using ((select auth.uid()) = user_id);
create policy "Users can insert own zepp sync state" on zepp_sync_state
  for insert with check ((select auth.uid()) = user_id);
create policy "Users can update own zepp sync state" on zepp_sync_state
  for update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
