-- A FINAL morning's CHARGE and domain scores, kept so they stand (android-app domain/score/ScoreSnapshots.kt). The app
-- scores every morning from the data it reads; without this a sync that rewrote an old row, the monthly re-fit of the
-- CHARGE weights or a new app build moved mornings already marked FINAL. A kept morning is replaced only when it is built
-- again with an input (or cap) it did not have, or by a new score model version.
--
-- scores is the app's DayScores as JSON; inputs lists the input keys it was built from.
--
-- Rollback: drop table public.score_snapshots;

create table if not exists public.score_snapshots (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  day date not null,
  model_version text not null,
  inputs text[] not null,
  scores jsonb not null check (jsonb_typeof(scores) = 'object'),
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);
alter table public.score_snapshots enable row level security;
drop policy if exists "select own score_snapshots" on public.score_snapshots;
drop policy if exists "insert own score_snapshots" on public.score_snapshots;
drop policy if exists "update own score_snapshots" on public.score_snapshots;
create policy "select own score_snapshots" on public.score_snapshots for select using ((select auth.uid()) = user_id);
create policy "insert own score_snapshots" on public.score_snapshots for insert with check ((select auth.uid()) = user_id);
create policy "update own score_snapshots" on public.score_snapshots for update using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
