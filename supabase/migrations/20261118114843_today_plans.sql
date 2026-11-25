-- The day's plan as the phone's TODAY drew it (web transition plan, phase 2, Nov 2026).
--   today_plans   one row per person and day: the plan rows (meals with their targets, sessions, supplements due, close the
--                 day, bed by) and the SO FAR line (kcal against the day's target, carbs, protein, fat), written by the phone
--                 each time TODAY is built. The web Dashboard reads it to show any day's plan exactly as the phone showed
--                 it; it never recomputes the plan. Replaced as a whole on every write. Own rows only.
--
-- Rollback: drop table public.today_plans;

create table if not exists public.today_plans (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  day date not null,
  plan jsonb not null check (jsonb_typeof(plan) = 'object'),
  built_at timestamptz not null default now(),
  unique (user_id, day)
);

alter table public.today_plans enable row level security;

drop policy if exists today_plans_select_own on public.today_plans;
create policy today_plans_select_own on public.today_plans for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists today_plans_insert_own on public.today_plans;
create policy today_plans_insert_own on public.today_plans for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists today_plans_update_own on public.today_plans;
create policy today_plans_update_own on public.today_plans for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists today_plans_delete_own on public.today_plans;
create policy today_plans_delete_own on public.today_plans for delete to authenticated using (user_id = (select auth.uid()));
