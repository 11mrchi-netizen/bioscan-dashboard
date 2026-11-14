-- Regimen phase 5 (docs/regimen/README.md): injectables.
--
-- 1. supplement_log.injection_site: where each injected dose went, so the app can suggest the site rested longest.
-- 2. supplements.syringe_units: the syringe units (U-100 insulin syringe) the prescriber gave for one dose. The app shows
--    them on the dose and only cross-checks them against the vial's mix; it never works out or suggests a dose.
-- 3. regimen_vials: the item's current vial (or pen) -- what it holds, the water added when mixed, when it was mixed or
--    opened, how many days it keeps once mixed -- and how many unopened spares are left. One open vial per item at a time;
--    finishing it records the day.
--
-- Rollback: drop table public.regimen_vials; alter table public.supplements drop column syringe_units;
--           alter table public.supplement_log drop column injection_site;

alter table public.supplement_log
  add column if not exists injection_site text check (injection_site is null or injection_site in (
    'abdomen_upper_left', 'abdomen_upper_right', 'abdomen_lower_left', 'abdomen_lower_right',
    'thigh_left_upper', 'thigh_left_lower', 'thigh_right_upper', 'thigh_right_lower',
    'arm_left', 'arm_right', 'glute_left', 'glute_right', 'deltoid_left', 'deltoid_right'));

alter table public.supplements
  add column if not exists syringe_units numeric check (syringe_units is null or (syringe_units > 0 and syringe_units <= 100));

create table public.regimen_vials (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  supplement_id bigint not null references public.supplements (id) on delete cascade,
  amount numeric not null check (amount > 0),
  amount_unit text not null check (amount_unit in ('mg', 'mcg', 'IU', 'unit')),
  -- Water added when mixed (ml); null for a ready-made solution or pen, where [volume_ml] holds its volume.
  diluent_ml numeric check (diluent_ml is null or (diluent_ml > 0 and diluent_ml <= 100)),
  volume_ml numeric check (volume_ml is null or (volume_ml > 0 and volume_ml <= 100)),
  opened_on date not null,
  discard_after_days integer check (discard_after_days is null or discard_after_days between 1 and 365),
  spares integer not null default 0 check (spares between 0 and 999),
  finished_on date,
  created_at timestamptz not null default now(),
  check (diluent_ml is not null or volume_ml is not null)
);
create unique index regimen_vials_one_open on public.regimen_vials (supplement_id) where finished_on is null;

alter table public.regimen_vials enable row level security;
create policy "select own vials" on public.regimen_vials for select using ((select auth.uid()) = user_id);
create policy "insert own vials" on public.regimen_vials for insert with check (
  (select auth.uid()) = user_id
  and exists (select 1 from public.supplements s where s.id = supplement_id and s.user_id = (select auth.uid())));
create policy "update own vials" on public.regimen_vials for update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "delete own vials" on public.regimen_vials for delete using ((select auth.uid()) = user_id);
