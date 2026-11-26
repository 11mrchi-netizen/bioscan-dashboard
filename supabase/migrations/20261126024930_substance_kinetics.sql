-- Substance kinetics: the numbers a regimen item's curve (Today's IN THE BODY) needs that no FDA label gave.
--
-- A medication's half-life comes from its FDA label (regimen_references.half_life_hours, fetched on request). A peptide, a
-- hormone or any other item without a label has none, and a label gives no peak time at all. This table holds the person's
-- own entries, which win over the label: one row per regimen item.
--   half_life_hours    elimination half-life, hours (a depot injection: days * 24)
--   tmax_hours         time from a dose to the peak, hours; empty = the app assumes 2 h and says so
--   amount_per_serving what one serving (a tablet, a vial draw) holds, when no product ingredient says so
--   amount_unit        its unit: mg, mcg, g, IU or units; curves keep each substance in the unit it is logged in
--   note               where the numbers came from (a paper, a vendor sheet, "my estimate")
-- Curves are estimates from logged doses, never blood levels.
--
-- Rollback: drop table public.substance_kinetics;

create table public.substance_kinetics (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  supplement_id bigint not null references public.supplements (id) on delete cascade,
  half_life_hours numeric not null check (half_life_hours > 0 and half_life_hours <= 24 * 365),
  tmax_hours numeric check (tmax_hours is null or (tmax_hours > 0 and tmax_hours <= 24 * 60)),
  amount_per_serving numeric check (amount_per_serving is null or amount_per_serving > 0),
  amount_unit text check (amount_unit is null or amount_unit in ('mg', 'mcg', 'g', 'IU', 'units')),
  note text check (note is null or length(note) <= 500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (supplement_id),
  check ((amount_per_serving is null) = (amount_unit is null))
);
create index substance_kinetics_user_idx on public.substance_kinetics (user_id);

alter table public.substance_kinetics enable row level security;
create policy "select own substance kinetics" on public.substance_kinetics for select using ((select auth.uid()) = user_id);
create policy "insert own substance kinetics" on public.substance_kinetics for insert with check (
  (select auth.uid()) = user_id
  and exists (select 1 from public.supplements s where s.id = supplement_id and s.user_id = (select auth.uid())));
create policy "update own substance kinetics" on public.substance_kinetics for update using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
create policy "delete own substance kinetics" on public.substance_kinetics for delete using ((select auth.uid()) = user_id);

create or replace function public.substance_kinetics_touch() returns trigger language plpgsql set search_path = public as $$
begin new.updated_at := now(); return new; end $$;
create trigger substance_kinetics_touch before update on public.substance_kinetics
  for each row execute function public.substance_kinetics_touch();
