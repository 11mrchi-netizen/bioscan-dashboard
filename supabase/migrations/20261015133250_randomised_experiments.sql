-- Analysis review phase F (docs/analysis-review/README.md): randomised self-experiments and a fixed read date for
-- interventions.
--
-- 1. experiments: two randomised designs (protocol version 2). 'randomized_pairs' runs pairs of equal periods, the order
--    inside each pair drawn at random, every period starting with a washout; 'micro_randomized' draws each day (with or
--    without, 3 or 4 with-days a week) for same-day choices such as a caffeine cut-off. The schedule is drawn on the
--    phone when the person registers, stored in `protocol`, and must start on or after the registration date, which is
--    the server's clock (a client-sent preregistered_at is overwritten): a schedule cannot be drawn over days already
--    seen. An experiment about a behaviour (protocol.behaviour) needs no supplement or intervention row.
-- 2. Deletion is soft (deleted_at): a published result and its frozen exclusions stay consistent with the row behind them,
--    and the analysis skips a deleted experiment. The delete policy goes; the account cascade still removes everything.
-- 3. experiment_exclusion_snapshots: the days a period (or, for a daily design, a week) left out -- illness, alcohol --
--    frozen by the analysis job the night after it ends, so editing a log later cannot move a finished period's result.
--    Users read their own; only the analysis job (service role) writes; nobody updates.
-- 4. interventions.evaluate_on: the date an intervention is read against the forecast from before it started (its.ts,
--    analysis_results kind 'intervention_its'); without one, four weeks after the start. Fixed once the intervention has
--    started, so the read cannot be moved after a look at the days since.
--
-- Rollback: drop table public.experiment_exclusion_snapshots; drop function public.experiment_exclusion_snapshots_no_update();
--           drop trigger experiments_guard_insert on public.experiments; drop function public.experiments_guard_insert();
--           drop trigger interventions_evaluate_on_guard on public.interventions; drop function public.interventions_evaluate_on_guard();
--           alter table public.interventions drop constraint interventions_evaluate_on_after_start;
--           alter table public.interventions drop column evaluate_on; alter table public.experiments drop column deleted_at;
--           restore experiments_design_check (AB, ABAB), experiments_has_a_subject (supplement or intervention), the
--           delete policy and the previous experiments_lock_protocol body (20261013_experiments_intervention_id.sql).

alter table public.experiments drop constraint experiments_design_check;
alter table public.experiments add constraint experiments_design_check
  check (design in ('AB', 'ABAB', 'randomized_pairs', 'micro_randomized'));

alter table public.experiments drop constraint experiments_has_a_subject;
alter table public.experiments add constraint experiments_has_a_subject
  check (supplement_id is not null or intervention_id is not null or protocol ? 'behaviour');

alter table public.experiments add column deleted_at timestamptz;

drop policy experiments_delete_own on public.experiments;

create or replace function public.experiments_lock_protocol()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.user_id is distinct from old.user_id
     or new.supplement_id is distinct from old.supplement_id
     or new.intervention_id is distinct from old.intervention_id
     or new.name is distinct from old.name
     or new.primary_metric is distinct from old.primary_metric
     or new.secondary_metrics is distinct from old.secondary_metrics
     or new.design is distinct from old.design
     or new.protocol is distinct from old.protocol
     or new.protocol_version is distinct from old.protocol_version
     or new.mde_preview is distinct from old.mde_preview
     or new.preregistered_at is distinct from old.preregistered_at then
    raise exception 'experiment protocol is pre-registered and cannot be changed (only status may be updated, or the experiment deleted)';
  end if;
  if old.deleted_at is not null and new.deleted_at is distinct from old.deleted_at then
    raise exception 'a deleted experiment stays deleted';
  end if;
  new.updated_at := now();
  return new;
end;
$$;

create or replace function public.experiments_guard_insert()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  first_day date;
begin
  new.preregistered_at := now();
  if new.design in ('randomized_pairs', 'micro_randomized') then
    if new.protocol_version <> '2' or coalesce(new.protocol ->> 'version', '') <> '2' then
      raise exception 'randomised designs use protocol version 2';
    end if;
    if jsonb_typeof(new.protocol -> 'zone') is distinct from 'number' or (new.protocol ->> 'zone')::double precision <= 0 then
      raise exception 'a randomised design needs its no-effect zone, set at registration';
    end if;
    first_day := case new.design
      when 'randomized_pairs' then (new.protocol -> 'phases' -> 0 ->> 'start')::date
      else (new.protocol -> 'assignments' -> 0 ->> 'date')::date
    end;
    if first_day is null then
      raise exception 'a randomised design needs its drawn schedule';
    end if;
    -- The phone draws the schedule from tomorrow, local time; tomorrow anywhere is never before today's UTC date.
    if first_day < (now() at time zone 'utc')::date then
      raise exception 'the schedule must start after registration';
    end if;
  end if;
  return new;
end;
$$;

create trigger experiments_guard_insert before insert on public.experiments
  for each row execute function public.experiments_guard_insert();

create table public.experiment_exclusion_snapshots (
  id bigint generated always as identity primary key,
  experiment_id uuid not null references public.experiments (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  period_start date not null,
  period_end date not null,
  excluded_dates date[] not null default '{}',
  frozen_at timestamptz not null default now(),
  unique (experiment_id, period_start)
);

create index experiment_exclusion_snapshots_user_idx on public.experiment_exclusion_snapshots (user_id, experiment_id);

alter table public.experiment_exclusion_snapshots enable row level security;

create policy experiment_exclusion_snapshots_select_own on public.experiment_exclusion_snapshots for select to authenticated
  using (user_id = (select auth.uid()));
-- No insert, update or delete policies on purpose: only the analysis job writes, and a frozen period stays frozen.

create or replace function public.experiment_exclusion_snapshots_no_update()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'a frozen period cannot change';
end;
$$;

create trigger experiment_exclusion_snapshots_no_update before update on public.experiment_exclusion_snapshots
  for each row execute function public.experiment_exclusion_snapshots_no_update();

comment on table public.experiment_exclusion_snapshots is 'Analysis review phase F: the days each ended period of a randomised experiment left out, frozen when it ended.';

alter table public.interventions add column evaluate_on date;

-- Fewer than 14 days after the start leaves too few to read (its.ts, ITS.minActiveDays).
alter table public.interventions add constraint interventions_evaluate_on_after_start
  check (evaluate_on is null or started_at is null or evaluate_on >= started_at + 13);

create or replace function public.interventions_evaluate_on_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.evaluate_on is not distinct from old.evaluate_on then
    return new;
  end if;
  -- Once started, the read date is fixed: it may only be set while still unset and before the default read date
  -- (four weeks after the start) has come, and never to a date already past.
  if old.started_at is not null and (old.evaluate_on is not null or (now() at time zone 'utc')::date > old.started_at + 27) then
    raise exception 'evaluate_on is fixed once the intervention has started';
  end if;
  if new.evaluate_on is not null and new.evaluate_on < (now() at time zone 'utc')::date then
    raise exception 'evaluate_on cannot be in the past';
  end if;
  return new;
end;
$$;

create trigger interventions_evaluate_on_guard before update on public.interventions
  for each row execute function public.interventions_evaluate_on_guard();

comment on column public.interventions.evaluate_on is 'Analysis review phase F: the date the intervention is read once against the forecast from before it started; default four weeks after the start.';
