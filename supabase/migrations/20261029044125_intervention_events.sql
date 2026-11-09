-- Repeatable, timed interventions: an informal N-of-1 evidence layer beside the formal, pre-registered experiments.
--
-- An *episodic* intervention (nasal strips, a lactose-heavy meal, a cold plunge) has no baseline/active window. What
-- accumulates is individual occurrences (intervention_events), each read against an outcome window that was declared
-- before the first occurrence (intervention_expectations). An outcome is either a stored daily series named in
-- supabase/functions/_shared/metric-registry/metric-series.json, or a user-defined tracked outcome (tracked_outcomes),
-- e.g. a Bristol stool score. Metric keys are validated in the app, not by CHECK, so the registry can grow without a
-- migration. Evidence is computed by analysis-core/eventEvidence.ts, never entered by hand.
--
-- Continuous interventions (mode 'continuous', today's behaviour) are untouched.
-- Rollback: drop table public.tracked_outcome_values; drop table public.tracked_outcomes;
--           drop table public.intervention_events; drop table public.intervention_expectations;
--           alter table public.interventions drop column instructions, drop column mode;

alter table public.interventions
  add column if not exists mode text not null default 'continuous' check (mode in ('continuous', 'episodic')),
  add column if not exists instructions text;

-- ---------- tracked outcomes (user-defined manual metrics) ----------

create table public.tracked_outcomes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name text not null check (char_length(name) between 2 and 60),
  -- ordinal_1_7 (Bristol-style), rating_0_10, count (zero or more), yes_no (0 or 1)
  scale text not null check (scale in ('ordinal_1_7', 'rating_0_10', 'count', 'yes_no')),
  -- which way is better, for wording only; 'none' when neither direction is better
  better_direction text not null default 'none' check (better_direction in ('up', 'down', 'none')),
  unit text,
  archived_at timestamptz,
  created_at timestamptz not null default now(),
  unique (user_id, name)
);

create table public.tracked_outcome_values (
  id bigint generated always as identity primary key,
  outcome_id uuid not null references public.tracked_outcomes (id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  observed_at timestamptz not null default now(),
  -- local calendar day and minute of the day of observed_at, set by the client from the phone's time zone
  local_date date not null,
  local_minute smallint not null check (local_minute between 0 and 1439),
  value double precision not null,
  note text,
  created_at timestamptz not null default now(),
  unique (outcome_id, observed_at)
);

create index tracked_outcome_values_outcome_idx on public.tracked_outcome_values (outcome_id, observed_at);

-- the value must fit the outcome's scale
create or replace function public.tracked_outcome_values_check_scale()
returns trigger
language plpgsql
set search_path = public
as $$
declare
  s text;
begin
  select scale into s from public.tracked_outcomes where id = new.outcome_id;
  if s = 'ordinal_1_7' and not (new.value between 1 and 7 and new.value = round(new.value)) then
    raise exception 'value % is outside the 1-7 scale', new.value;
  elsif s = 'rating_0_10' and not (new.value between 0 and 10) then
    raise exception 'value % is outside the 0-10 scale', new.value;
  elsif s = 'count' and not (new.value >= 0 and new.value = round(new.value)) then
    raise exception 'value % is not a count', new.value;
  elsif s = 'yes_no' and new.value not in (0, 1) then
    raise exception 'value % is not 0 or 1', new.value;
  end if;
  return new;
end;
$$;

create trigger tracked_outcome_values_check_scale before insert or update on public.tracked_outcome_values
  for each row execute function public.tracked_outcome_values_check_scale();

-- ---------- expectations (declared before the first event) ----------

create table public.intervention_expectations (
  id uuid primary key default gen_random_uuid(),
  intervention_id uuid not null references public.interventions (id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- 'series': outcome_key names a series in metric-series.json; 'tracked': outcome_key is a tracked_outcomes id
  outcome_kind text not null check (outcome_kind in ('series', 'tracked')),
  outcome_key text not null,
  role text not null default 'primary' check (role in ('primary', 'secondary', 'watch')),
  direction text not null check (direction in ('up', 'down', 'none')),
  -- hours after the event, window_start_h < window_end_h. A daily series covers the calendar day(s) the window touches.
  window_start_h numeric not null default 0,
  window_end_h numeric not null default 24,
  aggregation text not null default 'mean' check (aggregation in ('mean', 'min', 'max', 'sum', 'last')),
  -- set by the trigger below when the expectation is added after events already exist: read as exploratory only
  exploratory boolean not null default false,
  declared_at timestamptz not null default now(),
  check (window_end_h > window_start_h),
  check (window_end_h - window_start_h <= 24 * 14)
);

create index intervention_expectations_intervention_idx on public.intervention_expectations (intervention_id);
-- one primary per intervention among the pre-registered (non-exploratory) expectations
create unique index intervention_expectations_one_primary_idx on public.intervention_expectations (intervention_id)
  where role = 'primary' and not exploratory;

-- A declared expectation cannot be changed or deleted once an event exists, so the window can't be re-chosen after
-- seeing data. A new expectation may still be added later; it is stamped exploratory.
create or replace function public.intervention_expectations_lock()
returns trigger
language plpgsql
set search_path = public
as $$
declare
  has_events boolean;
begin
  if tg_op = 'INSERT' then
    select exists (select 1 from public.intervention_events e where e.intervention_id = new.intervention_id) into has_events;
    if has_events then
      new.exploratory := true;
      if new.role = 'primary' then
        new.role := 'secondary';
      end if;
    end if;
    return new;
  end if;
  select exists (select 1 from public.intervention_events e where e.intervention_id = old.intervention_id) into has_events;
  if has_events then
    raise exception 'expectation is locked: events already exist for this intervention';
  end if;
  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

-- ---------- events (one occurrence) ----------

create table public.intervention_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  intervention_id uuid not null references public.interventions (id) on delete cascade,
  intervention_version integer not null default 1,
  occurred_at timestamptz not null default now(),
  -- local calendar day and minute of the day of occurred_at, set by the client from the phone's time zone
  local_date date not null,
  local_minute smallint not null check (local_minute between 0 and 1439),
  adhered text not null default 'yes' check (adhered in ('yes', 'partial', 'no')),
  dose_note text,
  -- illness, travel, heavy_training, ... (alcohol, late caffeine and late meals come from the evening check-in instead)
  confounders text[] not null default '{}',
  -- how it felt, 0-10, rated before the numbers are shown
  feeling smallint check (feeling between 0 and 10),
  rated_at timestamptz,
  note text,
  assigned_by text not null default 'self' check (assigned_by in ('self', 'drawn')),
  -- the expectations as they stood when the event was logged
  expectation_snapshot jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (intervention_id, occurred_at)
);

create index intervention_events_user_day_idx on public.intervention_events (user_id, local_date);
create index intervention_events_intervention_idx on public.intervention_events (intervention_id, occurred_at);

create trigger intervention_events_touch before update on public.intervention_events
  for each row execute function public.health_touch();

create trigger intervention_expectations_lock before insert or update or delete on public.intervention_expectations
  for each row execute function public.intervention_expectations_lock();

-- ---------- RLS ----------

alter table public.tracked_outcomes enable row level security;
create policy tracked_outcomes_select_own on public.tracked_outcomes for select to authenticated using (user_id = (select auth.uid()));
create policy tracked_outcomes_insert_own on public.tracked_outcomes for insert to authenticated with check (user_id = (select auth.uid()));
create policy tracked_outcomes_update_own on public.tracked_outcomes for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy tracked_outcomes_delete_own on public.tracked_outcomes for delete to authenticated using (user_id = (select auth.uid()));

alter table public.tracked_outcome_values enable row level security;
create policy tracked_outcome_values_select_own on public.tracked_outcome_values for select to authenticated using (user_id = (select auth.uid()));
create policy tracked_outcome_values_insert_own on public.tracked_outcome_values for insert to authenticated with check (
  user_id = (select auth.uid())
  and exists (select 1 from public.tracked_outcomes o where o.id = outcome_id and o.user_id = (select auth.uid())));
create policy tracked_outcome_values_update_own on public.tracked_outcome_values for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy tracked_outcome_values_delete_own on public.tracked_outcome_values for delete to authenticated using (user_id = (select auth.uid()));

alter table public.intervention_expectations enable row level security;
create policy intervention_expectations_select_own on public.intervention_expectations for select to authenticated using (user_id = (select auth.uid()));
create policy intervention_expectations_insert_own on public.intervention_expectations for insert to authenticated with check (
  user_id = (select auth.uid())
  and exists (select 1 from public.interventions i where i.id = intervention_id and i.user_id = (select auth.uid())));
create policy intervention_expectations_update_own on public.intervention_expectations for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy intervention_expectations_delete_own on public.intervention_expectations for delete to authenticated using (user_id = (select auth.uid()));

alter table public.intervention_events enable row level security;
create policy intervention_events_select_own on public.intervention_events for select to authenticated using (user_id = (select auth.uid()));
create policy intervention_events_insert_own on public.intervention_events for insert to authenticated with check (
  user_id = (select auth.uid())
  and exists (select 1 from public.interventions i where i.id = intervention_id and i.user_id = (select auth.uid())));
create policy intervention_events_update_own on public.intervention_events for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy intervention_events_delete_own on public.intervention_events for delete to authenticated using (user_id = (select auth.uid()));
