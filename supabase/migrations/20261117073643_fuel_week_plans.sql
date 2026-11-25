-- The week's coded meals as the phone works them out (docs/fuel-planning; the web meal prep, Nov 2026).
--   fuel_week_plans   one row per person and prep week (a Monday): every day's meals with their carbohydrate, protein and
--                     fat targets, the planned sessions, and the foods to portion from, as computed by the Android fuel
--                     engine. The phone writes it each time it works the week out; the console's MEAL PREP reads it, so
--                     both portion containers from the same targets. Replaced as a whole on every write. Own rows only.
--
-- Rollback: drop table public.fuel_week_plans;

create table if not exists public.fuel_week_plans (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  week_start date not null check (extract(isodow from week_start) = 1),
  plan jsonb not null check (jsonb_typeof(plan) = 'object'),
  engine_version text not null,
  computed_at timestamptz not null default now(),
  unique (user_id, week_start)
);

alter table public.fuel_week_plans enable row level security;

drop policy if exists fuel_week_plans_select_own on public.fuel_week_plans;
create policy fuel_week_plans_select_own on public.fuel_week_plans for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists fuel_week_plans_insert_own on public.fuel_week_plans;
create policy fuel_week_plans_insert_own on public.fuel_week_plans for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists fuel_week_plans_update_own on public.fuel_week_plans;
create policy fuel_week_plans_update_own on public.fuel_week_plans for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists fuel_week_plans_delete_own on public.fuel_week_plans;
create policy fuel_week_plans_delete_own on public.fuel_week_plans for delete to authenticated using (user_id = (select auth.uid()));
