-- After an event or long session (docs/fuel-planning/README.md, phase 8): how the fuel plan went. One row per reviewed
-- event: the carbohydrate, fluid and caffeine actually taken (from the plan's items marked consumed, partial or skipped,
-- which also go into fueling_intake_log for that session), how the gut coped (0 none - 10 worst), energy (0 empty - 10
-- strong), and the weigh-ins that turn the race into a sweat test. These are the app's first gastrointestinal records,
-- which the deferred cross-activity fuelling analysis (endurance fueling 06.3.5) was waiting for.
--
-- Own rows only. Safe to run again.
--
-- Rollback: drop table public.fuel_plan_reviews;

create table if not exists public.fuel_plan_reviews (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  event_id bigint references public.athlete_events (id) on delete cascade,
  exercise_session_id bigint references public.exercise_sessions (id) on delete set null,
  reviewed_on date not null default current_date,
  duration_min numeric check (duration_min is null or duration_min between 1 and 10080),
  carbs_g numeric check (carbs_g is null or carbs_g between 0 and 5000),
  fluid_ml numeric check (fluid_ml is null or fluid_ml between 0 and 30000),
  caffeine_mg numeric check (caffeine_mg is null or caffeine_mg between 0 and 2000),
  gi_score smallint check (gi_score is null or gi_score between 0 and 10),
  energy_score smallint check (energy_score is null or energy_score between 0 and 10),
  weight_before_kg numeric check (weight_before_kg is null or weight_before_kg between 30 and 200),
  weight_after_kg numeric check (weight_after_kg is null or weight_after_kg between 30 and 200),
  notes text check (notes is null or length(notes) <= 2000),
  created_at timestamptz not null default now(),
  unique (user_id, event_id)
);

create index if not exists fuel_plan_reviews_user_date_idx on public.fuel_plan_reviews (user_id, reviewed_on desc);

alter table public.fuel_plan_reviews enable row level security;

drop policy if exists fuel_plan_reviews_select_own on public.fuel_plan_reviews;
create policy fuel_plan_reviews_select_own on public.fuel_plan_reviews for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists fuel_plan_reviews_insert_own on public.fuel_plan_reviews;
create policy fuel_plan_reviews_insert_own on public.fuel_plan_reviews for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists fuel_plan_reviews_update_own on public.fuel_plan_reviews;
create policy fuel_plan_reviews_update_own on public.fuel_plan_reviews for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists fuel_plan_reviews_delete_own on public.fuel_plan_reviews;
create policy fuel_plan_reviews_delete_own on public.fuel_plan_reviews for delete to authenticated using (user_id = (select auth.uid()));
