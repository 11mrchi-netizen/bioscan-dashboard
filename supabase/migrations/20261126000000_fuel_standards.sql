-- Fuel standards (web fuel port): a named, reusable way of fuelling a long session ("a gel every 30 minutes from the 30th", "carbohydrate at 60 g an hour from
-- this product"), applied to any session to fill its fuel schedule (fuel_schedules) in one step. Edited in place, own rows only; the phone does not read it.
--
-- rules is an array of rules (fuel-core/standards.ts StandardRule). Safe to run again.
-- Rollback: drop table public.fuel_standards;

create table if not exists public.fuel_standards (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 60),
  rules jsonb not null check (jsonb_typeof(rules) = 'array' and jsonb_array_length(rules) <= 40),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, name)
);

alter table public.fuel_standards enable row level security;

drop policy if exists fuel_standards_select_own on public.fuel_standards;
create policy fuel_standards_select_own on public.fuel_standards for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists fuel_standards_insert_own on public.fuel_standards;
create policy fuel_standards_insert_own on public.fuel_standards for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists fuel_standards_update_own on public.fuel_standards;
create policy fuel_standards_update_own on public.fuel_standards for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists fuel_standards_delete_own on public.fuel_standards;
create policy fuel_standards_delete_own on public.fuel_standards for delete to authenticated using (user_id = (select auth.uid()));
