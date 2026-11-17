-- DAV-126: threshold-relative intensity (DAV-55's endurance adapters) needs
-- a per-user threshold profile that doesn't exist anywhere today. One row
-- per user, updated whenever thresholds are re-tested -- effective_date
-- lets a future adapter pick the threshold that was in effect on a given
-- session's date, rather than always using "whatever's current".
create table public.training_thresholds (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id),
  threshold_hr smallint check (threshold_hr > 0 and threshold_hr < 250),
  threshold_pace_min_per_km numeric check (threshold_pace_min_per_km > 0),
  ftp_watts numeric check (ftp_watts > 0),
  effective_date date not null default current_date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.training_thresholds enable row level security;

create policy "select own training_thresholds" on public.training_thresholds
  for select using ((select auth.uid()) = user_id);
create policy "insert own training_thresholds" on public.training_thresholds
  for insert with check ((select auth.uid()) = user_id);
create policy "update own training_thresholds" on public.training_thresholds
  for update using ((select auth.uid()) = user_id);
create policy "delete own training_thresholds" on public.training_thresholds
  for delete using ((select auth.uid()) = user_id);
