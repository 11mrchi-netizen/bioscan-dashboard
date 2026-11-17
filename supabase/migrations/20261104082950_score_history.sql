-- Every scored morning's CHARGE and domain scores as the app computes them (android-app data/ScoresRepository.kt), one
-- light row per day, so the nightly relationship engine can ask what goes with next-morning RECOVERY and SLEEP
-- (supabase/functions/_shared/analysis-core/hypotheses.ts, catalogue 2.2.0). score_snapshots keeps only FINAL mornings
-- from the day it started; this holds the app's whole scoring history (240 mornings) and is rewritten as it changes.
--
-- Rollback: drop table public.score_history;

create table if not exists public.score_history (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  day date not null,
  model_version text not null,
  charge double precision,
  sleep double precision,
  recovery double precision,
  load double precision,
  fuel double precision,
  balance double precision,
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);
alter table public.score_history enable row level security;
drop policy if exists "select own score_history" on public.score_history;
drop policy if exists "insert own score_history" on public.score_history;
drop policy if exists "update own score_history" on public.score_history;
create policy "select own score_history" on public.score_history for select using ((select auth.uid()) = user_id);
create policy "insert own score_history" on public.score_history for insert with check ((select auth.uid()) = user_id);
create policy "update own score_history" on public.score_history for update using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
