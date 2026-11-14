-- Regimen model (docs/regimen/README.md): the supplement roster becomes a roster of everything taken on purpose --
-- supplements, medications, peptides, hormones -- with a structured dose and schedule instead of free text.
--
-- 1. supplements gains:
--    kind         supplement | medication | peptide | hormone | other
--    form, route  what it is and how it is taken (capsule, injection...; oral, subcutaneous...)
--    dose_amount, dose_unit   one dose as a number and a unit ("36 mg, 3x/week" was a dose string before)
--    slots        the times of day it is taken: any of morning, afternoon, night (twice a day = two slots)
--    schedule     {"type":"daily"} | {"type":"every_n_days","n":3} | {"type":"weekdays","days":[1,3,5]} (ISO, Mon=1)
--                 | {"type":"as_needed"}, optionally with "cycle": {"on_days":56,"off_days":28,"start":"2026-11-01"}
--    dose_steps   {"start":"2026-11-01","steps":[{"weeks":4,"amount":2.5},{"amount":5}]}: planned dose changes
--                 (titration or taper); the last step has no length and carries on.
--    The old columns stay and the app keeps them filled (dose text = the current dose, time_of_day = the first slot or
--    as-needed, every_n_days = n), so the web dashboard, the log agent and the analysis functions keep working.
-- 2. supplement_changes: one row each time an item starts, stops, restarts or changes dose or schedule, written by a
--    trigger so every client's edits are recorded. The timeline the charts and the "since you changed X" readings use.
-- 3. Backfill: the 20 existing rows parsed into the new columns (Tadalafil marked a medication), and their start and
--    end dates recorded as history.
--
-- Rollback: drop trigger supplements_record_change on public.supplements; drop function public.record_supplement_change();
--           drop table public.supplement_changes;
--           alter table public.supplements drop column kind, drop column form, drop column route, drop column dose_amount,
--             drop column dose_unit, drop column slots, drop column schedule, drop column dose_steps;

alter table public.supplements
  add column if not exists kind text not null default 'supplement'
    check (kind in ('supplement', 'medication', 'peptide', 'hormone', 'other')),
  add column if not exists form text
    check (form is null or form in ('capsule', 'tablet', 'softgel', 'powder', 'liquid', 'gummy', 'injection', 'nasal_spray', 'cream', 'patch', 'other')),
  add column if not exists route text
    check (route is null or route in ('oral', 'sublingual', 'subcutaneous', 'intramuscular', 'nasal', 'topical', 'other')),
  add column if not exists dose_amount numeric check (dose_amount is null or dose_amount > 0),
  add column if not exists dose_unit text
    check (dose_unit is null or dose_unit in ('mg', 'mcg', 'g', 'IU', 'ml', 'drop', 'pill', 'capsule', 'tablet', 'softgel', 'scoop', 'serving', 'unit')),
  add column if not exists slots text[] not null default '{}'
    check (slots <@ array['morning', 'afternoon', 'night']::text[]),
  add column if not exists schedule jsonb
    check (schedule is null or (jsonb_typeof(schedule) = 'object' and schedule->>'type' in ('daily', 'every_n_days', 'weekdays', 'as_needed'))),
  add column if not exists dose_steps jsonb
    check (dose_steps is null or (jsonb_typeof(dose_steps) = 'object' and jsonb_typeof(dose_steps->'steps') = 'array'));

-- Backfill before the history trigger exists, so tidying the dose text is not recorded as a dose change.
with parsed as (
  select id,
         regexp_match(dose, '^\s*([0-9]+(?:[.,][0-9]+)?)\s*([A-Za-z]+)') as m
  from public.supplements
)
update public.supplements s
set dose_amount = replace(p.m[1], ',', '.')::numeric,
    dose_unit = case lower(p.m[2])
                  when 'gr' then 'g' when 'g' then 'g' when 'mg' then 'mg' when 'mcg' then 'mcg' when 'ug' then 'mcg'
                  when 'iu' then 'IU' when 'ml' then 'ml' when 'pill' then 'pill' when 'pills' then 'pill'
                  when 'capsule' then 'capsule' when 'capsules' then 'capsule' when 'caps' then 'capsule'
                  when 'tablet' then 'tablet' when 'tablets' then 'tablet' when 'softgel' then 'softgel' when 'softgels' then 'softgel'
                  when 'scoop' then 'scoop' when 'drop' then 'drop' when 'drops' then 'drop'
                end
from parsed p
where p.id = s.id and p.m is not null;

update public.supplements
set slots = case when time_of_day = 'as-needed' then '{}'::text[] else array[time_of_day] end,
    schedule = case
                 when time_of_day = 'as-needed' then '{"type":"as_needed"}'::jsonb
                 when every_n_days is not null then jsonb_build_object('type', 'every_n_days', 'n', every_n_days)
                 else '{"type":"daily"}'::jsonb
               end,
    route = coalesce(route, 'oral'),
    kind = case when name ilike 'tadalafil%' then 'medication' else kind end;

-- The dose text becomes the structured dose, written the way the app writes it ("36 mg, 3x/week" -> "36 mg"; the
-- every-3-days schedule already says how often).
update public.supplements
set dose = trim_scale(dose_amount)::text || ' ' || dose_unit
where dose_amount is not null and dose_unit is not null;

create table if not exists public.supplement_changes (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  supplement_id bigint not null references public.supplements (id) on delete cascade,
  changed_at timestamptz not null default now(),
  -- What changed: any of started, stopped, restarted, dose, schedule.
  changes text[] not null check (cardinality(changes) > 0 and changes <@ array['started', 'stopped', 'restarted', 'dose', 'schedule']::text[]),
  before jsonb,
  after jsonb not null,
  created_at timestamptz not null default now()
);
create index if not exists supplement_changes_user_time_idx on public.supplement_changes (user_id, changed_at desc);
create index if not exists supplement_changes_item_idx on public.supplement_changes (supplement_id, changed_at);

alter table public.supplement_changes enable row level security;
create policy "select own supplement changes" on public.supplement_changes for select using ((select auth.uid()) = user_id);
-- Written by the trigger only; the person can delete a wrong entry.
create policy "delete own supplement changes" on public.supplement_changes for delete using ((select auth.uid()) = user_id);

create or replace function public.supplement_snapshot(s public.supplements) returns jsonb
language sql immutable as $$
  select jsonb_build_object(
    'name', s.name, 'kind', s.kind, 'status', s.status,
    'dose', s.dose, 'dose_amount', s.dose_amount, 'dose_unit', s.dose_unit,
    'time_of_day', s.time_of_day, 'every_n_days', s.every_n_days, 'slots', to_jsonb(s.slots),
    'schedule', s.schedule, 'dose_steps', s.dose_steps)
$$;

create or replace function public.record_supplement_change() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  c text[] := '{}';
begin
  if tg_op = 'INSERT' then
    c := array['started'];
  else
    if old.status = 'active' and new.status = 'ended' then c := c || 'stopped'::text; end if;
    if old.status = 'ended' and new.status = 'active' then c := c || 'restarted'::text; end if;
    if old.dose is distinct from new.dose or old.dose_amount is distinct from new.dose_amount
       or old.dose_unit is distinct from new.dose_unit or old.dose_steps is distinct from new.dose_steps then
      c := c || 'dose'::text;
    end if;
    if old.time_of_day is distinct from new.time_of_day or old.every_n_days is distinct from new.every_n_days
       or old.slots is distinct from new.slots or old.schedule is distinct from new.schedule then
      c := c || 'schedule'::text;
    end if;
  end if;
  if cardinality(c) > 0 then
    insert into public.supplement_changes (user_id, supplement_id, changes, before, after)
    values (new.user_id, new.id, c,
            case when tg_op = 'UPDATE' then public.supplement_snapshot(old) end,
            public.supplement_snapshot(new));
  end if;
  return new;
end
$$;

drop trigger if exists supplements_record_change on public.supplements;
create trigger supplements_record_change
  after insert or update on public.supplements
  for each row execute function public.record_supplement_change();

-- History so far: each item's start, and the end of the ended ones.
insert into public.supplement_changes (user_id, supplement_id, changed_at, changes, before, after)
select s.user_id, s.id, s.start_date::timestamptz, array['started'], null, public.supplement_snapshot(s)
from public.supplements s
where not exists (select 1 from public.supplement_changes c where c.supplement_id = s.id);

insert into public.supplement_changes (user_id, supplement_id, changed_at, changes, before, after)
select s.user_id, s.id, s.end_date::timestamptz, array['stopped'], null, public.supplement_snapshot(s)
from public.supplements s
where s.status = 'ended' and s.end_date is not null;

-- 4. A client that writes only the old columns (an insert without slots/schedule, or an edit of time_of_day or
--    every_n_days alone) gets the structured columns derived from them, so the two never disagree. Runs before the
--    history trigger, which then sees the derived values.
create or replace function public.sync_supplement_columns() returns trigger
language plpgsql set search_path = public as $$
begin
  if tg_op = 'INSERT' and new.schedule is null and cardinality(new.slots) = 0
     or tg_op = 'UPDATE' and (old.time_of_day is distinct from new.time_of_day or old.every_n_days is distinct from new.every_n_days)
        and old.slots is not distinct from new.slots and old.schedule is not distinct from new.schedule then
    new.slots := case when new.time_of_day = 'as-needed' then '{}'::text[] else array[new.time_of_day] end;
    new.schedule := case
                      when new.time_of_day = 'as-needed' then '{"type":"as_needed"}'::jsonb
                      when new.every_n_days is not null then jsonb_build_object('type', 'every_n_days', 'n', new.every_n_days)
                      else '{"type":"daily"}'::jsonb
                    end;
  end if;
  return new;
end
$$;

drop trigger if exists supplements_sync_columns on public.supplements;
create trigger supplements_sync_columns
  before insert or update on public.supplements
  for each row execute function public.sync_supplement_columns();
