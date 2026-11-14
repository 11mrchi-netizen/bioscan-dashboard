-- Which plan a recorded session was (docs/fuel-planning/README.md, "Matching recordings to plans").
--
-- One row per recorded session: the plan it was (plan_ref 'calendar:<event id>', 'planned:<planned_sessions id>' or
-- 'event:<athlete_events id>'), or none (plan_ref null: "not planned", only ever set by the person). 'auto' rows are
-- written by the app once a day is over, from its scored match; 'manual' rows are the person's answer and always win.
-- Fuel schedules made for a plan (fuel_schedules kind 'planned', ref_key = plan_ref) reach the recording through here,
-- so the debrief finds them.
--
-- Rollback: drop table public.session_plan_links;

create table if not exists public.session_plan_links (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  exercise_session_id bigint not null references public.exercise_sessions (id) on delete cascade,
  plan_ref text check (plan_ref is null or length(plan_ref) between 1 and 200),
  plan_title text check (plan_title is null or length(plan_title) <= 300),
  plan_date date,
  source text not null check (source in ('auto', 'manual')),
  confidence real check (confidence is null or confidence between 0 and 1),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, exercise_session_id),
  check (plan_ref is not null or source = 'manual')
);

-- A plan is one recording.
create unique index if not exists session_plan_links_one_per_plan on public.session_plan_links (user_id, plan_ref) where plan_ref is not null;

alter table public.session_plan_links enable row level security;

drop policy if exists session_plan_links_select_own on public.session_plan_links;
create policy session_plan_links_select_own on public.session_plan_links for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists session_plan_links_insert_own on public.session_plan_links;
create policy session_plan_links_insert_own on public.session_plan_links for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists session_plan_links_update_own on public.session_plan_links;
create policy session_plan_links_update_own on public.session_plan_links for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists session_plan_links_delete_own on public.session_plan_links;
create policy session_plan_links_delete_own on public.session_plan_links for delete to authenticated using (user_id = (select auth.uid()));

revoke all on public.session_plan_links from anon;
