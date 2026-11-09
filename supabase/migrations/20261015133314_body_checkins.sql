-- The monthly body check-in (docs/body-checkin/README.md): grip strength, tape girths and self-measured skinfolds, taken in one
-- sitting under recorded conditions so one month can be compared with the next.
--
-- 1. body_checkins: one row per sitting -- when, whether fasted and before training, which caliper (a change of caliper
--    changes skinfold readings, so it is recorded and shown), a note.
-- 2. body_measurements: one row per site and side of a check-in. `readings` keeps every repeat as taken (the phone asks
--    for another when two disagree by more than the method's tolerance); `value` is their median, the number the app
--    uses. The repeats are what the typical error of each site is worked out from. Tape sites are in cm, skinfolds in
--    mm; `method` follows from the site. Skinfolds are taken on the right side only (the side one can reach and see),
--    tape limbs on both sides.
-- 3. grip_strength_log.checkin_id: grip readings taken during a check-in point at it (null for a grip logged on its own).
-- 4. notification_rules: a 'body_checkin' reminder, Saturday 09:30, skipped until 28 days after the last check-in.
--
-- Own rows only. A measurement may only point at the person's own check-in.
--
-- Rollback: alter table public.grip_strength_log drop column checkin_id;
--           drop table public.body_measurements; drop table public.body_checkins;
--           delete from public.notification_rules where kind = 'body_checkin';
--           restore notification_rules_kind_check without 'body_checkin' (20261015_evening_checkins.sql).

create table public.body_checkins (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- local wall-clock time labelled with an offset, same convention as grip_strength_log
  occurred_at timestamptz not null,
  fasted boolean,
  before_training boolean,
  caliper text check (caliper is null or length(caliper) <= 80),
  notes text check (notes is null or length(notes) <= 2000),
  created_at timestamptz not null default now(),
  unique (user_id, id)
);

create index body_checkins_user_time_idx on public.body_checkins (user_id, occurred_at desc);

create table public.body_measurements (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  checkin_id bigint not null,
  site text not null check (site in (
    'neck', 'shoulders', 'chest', 'waist_navel', 'waist_midpoint', 'hips',
    'biceps_relaxed', 'biceps_flexed', 'forearm', 'thigh', 'calf',
    'sf_abdominal', 'sf_suprailiac', 'sf_thigh', 'sf_chest')),
  side text not null check (side in ('left', 'right', 'none')),
  method text not null check (method in ('tape', 'caliper')),
  readings numeric[] not null check (cardinality(readings) between 1 and 5 and array_position(readings, null) is null),
  value numeric not null,
  created_at timestamptz not null default now(),
  -- The check-in belongs to the same person (composite key, so RLS alone does not have to guarantee it).
  foreign key (user_id, checkin_id) references public.body_checkins (user_id, id) on delete cascade,
  unique (checkin_id, site, side),
  constraint body_measurements_method_matches_site check ((method = 'caliper') = (site like 'sf\_%')),
  -- Trunk girths have no side; limbs and skinfolds do.
  constraint body_measurements_side_matches_site check (
    (side = 'none') = (site in ('neck', 'shoulders', 'chest', 'waist_navel', 'waist_midpoint', 'hips'))),
  -- The median lies between the smallest and the largest reading.
  constraint body_measurements_value_in_readings check (value >= any (readings) and value <= any (readings)),
  constraint body_measurements_plausible check (
    case method
      when 'tape' then 10 <= all (readings) and 250 >= all (readings)
      else 1 <= all (readings) and 80 >= all (readings)
    end)
);

create index body_measurements_user_site_idx on public.body_measurements (user_id, site, side);
create index body_measurements_checkin_idx on public.body_measurements (checkin_id);

alter table public.body_checkins enable row level security;
alter table public.body_measurements enable row level security;

create policy body_checkins_select_own on public.body_checkins for select to authenticated using (user_id = (select auth.uid()));
create policy body_checkins_insert_own on public.body_checkins for insert to authenticated with check (user_id = (select auth.uid()));
create policy body_checkins_update_own on public.body_checkins for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy body_checkins_delete_own on public.body_checkins for delete to authenticated using (user_id = (select auth.uid()));

create policy body_measurements_select_own on public.body_measurements for select to authenticated using (user_id = (select auth.uid()));
create policy body_measurements_insert_own on public.body_measurements for insert to authenticated with check (user_id = (select auth.uid()));
create policy body_measurements_update_own on public.body_measurements for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy body_measurements_delete_own on public.body_measurements for delete to authenticated using (user_id = (select auth.uid()));

alter table public.grip_strength_log add column checkin_id bigint;
alter table public.grip_strength_log add constraint grip_strength_log_checkin_fk
  foreign key (user_id, checkin_id) references public.body_checkins (user_id, id) on delete set null (checkin_id);

alter table public.notification_rules drop constraint notification_rules_kind_check;
alter table public.notification_rules add constraint notification_rules_kind_check
  check (kind = any (array['supplement_due', 'checkin_morning', 'checkin_evening', 'wellbeing_checkin', 'evening_log',
                           'evening_checkin', 'body_checkin']));

insert into public.notification_rules (user_id, kind, enabled, schedule, conditions, limits)
select u.user_id, 'body_checkin', true, '{"times": ["09:30"], "days": [6]}'::jsonb, '{"skip_if_logged": true}'::jsonb,
       '{"max_per_day": 1, "min_gap_min": 60, "snooze_min": 60}'::jsonb
from (select distinct user_id from public.notification_rules) u
on conflict (user_id, kind) do nothing;
