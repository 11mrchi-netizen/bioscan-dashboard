-- Sweat tests (docs/fuel-planning/README.md, phase 4): a weigh-in before and after a session, the fluid drunk and the urine
-- passed, so a hydration plan comes from the person's own sweat rate rather than a generic one (Sawka 2007, ACSM fluid
-- replacement). Sweat lost = weight lost + fluid in - urine; the rate is that over the duration. The conditions (air
-- temperature, humidity, effort) are kept so tests at different temperatures can be compared.
--
-- One row per test, optionally pointing at the session it was taken on. Own rows only. Safe to run again.
--
-- Rollback: drop table public.sweat_tests;

create table if not exists public.sweat_tests (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  tested_on date not null,
  exercise_session_id bigint references public.exercise_sessions (id) on delete set null,
  session_type text not null check (session_type in ('run', 'ride', 'hike', 'swim', 'strength', 'other')),
  intensity text not null check (intensity in ('easy', 'moderate', 'hard', 'long', 'race')),
  duration_min numeric not null check (duration_min between 10 and 1440),
  weight_before_kg numeric not null check (weight_before_kg between 30 and 200),
  weight_after_kg numeric not null check (weight_after_kg between 30 and 200),
  fluid_in_ml numeric not null default 0 check (fluid_in_ml between 0 and 20000),
  urine_ml numeric not null default 0 check (urine_ml between 0 and 5000),
  temp_c numeric check (temp_c is null or temp_c between -30 and 55),
  humidity_pct numeric check (humidity_pct is null or humidity_pct between 0 and 100),
  notes text check (notes is null or length(notes) <= 1000),
  created_at timestamptz not null default now()
);

create index if not exists sweat_tests_user_date_idx on public.sweat_tests (user_id, tested_on desc);

alter table public.sweat_tests enable row level security;

drop policy if exists sweat_tests_select_own on public.sweat_tests;
create policy sweat_tests_select_own on public.sweat_tests for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists sweat_tests_insert_own on public.sweat_tests;
create policy sweat_tests_insert_own on public.sweat_tests for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists sweat_tests_update_own on public.sweat_tests;
create policy sweat_tests_update_own on public.sweat_tests for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists sweat_tests_delete_own on public.sweat_tests;
create policy sweat_tests_delete_own on public.sweat_tests for delete to authenticated using (user_id = (select auth.uid()));
