-- Fuel plans as they stood (docs/fuel-planning/README.md, phase 6): a compact copy of a day's or an event's fuel plan,
-- written only when it changes, so the app can say what changed since it was last shown and the review after an event
-- reads the plan as it was on the day. Append-only: rows are never updated (the app only inserts); old rows may be
-- deleted by the person with the rest of their data.
--
-- kind 'day' keys on the date (ref_key 'YYYY-MM-DD'); kind 'event' on the athlete_events id. plan is the app's summary
-- (domain/fuel/FuelSnapshot.kt); inputs_hash tells an unchanged plan from a changed one; engine_version which rules made
-- it.
--
-- Own rows only. Safe to run again.
--
-- Rollback: drop table public.fuel_plans;

create table if not exists public.fuel_plans (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  kind text not null check (kind in ('day', 'event', 'session')),
  ref_key text not null check (length(ref_key) between 1 and 64),
  engine_version text not null,
  inputs_hash text not null,
  plan jsonb not null check (jsonb_typeof(plan) = 'object'),
  created_at timestamptz not null default now()
);

create index if not exists fuel_plans_user_ref_idx on public.fuel_plans (user_id, kind, ref_key, created_at desc);

alter table public.fuel_plans enable row level security;

drop policy if exists fuel_plans_select_own on public.fuel_plans;
create policy fuel_plans_select_own on public.fuel_plans for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists fuel_plans_insert_own on public.fuel_plans;
create policy fuel_plans_insert_own on public.fuel_plans for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists fuel_plans_delete_own on public.fuel_plans;
create policy fuel_plans_delete_own on public.fuel_plans for delete to authenticated using (user_id = (select auth.uid()));
