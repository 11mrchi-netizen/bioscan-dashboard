-- Routine intelligence, phase R7 (docs/routine-intelligence/README.md): practices and logged time.
--
-- 1. routine_activities: what the person does on purpose or spends time on. A 'practice' (meditation, stretching, sauna)
--    can be scheduled: days of the week (ISO 1 = Monday .. 7 = Sunday) and a local wall-clock time, with an optional
--    reminder; a 'pastime' (TV, reading, gaming) is only logged. target_min is a practice's planned length.
-- 2. routine_logs: one block of time, or a practice marked done or skipped. started_at / ended_at are LOGGED times: the
--    phone's wall clock labelled +00, like meals.logged_at (domain/WallClock.kt); date is the local day it counts for.
--    client_key makes a save idempotent (a timer stopped from the notification and again in the app writes one row).
-- 3. notification_rules.kind gains 'practice_due' (the worker reads the practices; the rule carries no times), seeded for
--    existing users.
--
-- Time that other data already shows (sessions, walking in trips, outdoor daylight, phone use, sleep) is not copied here:
-- the app reads it from its own tables.
--
-- Rollback: drop table public.routine_logs, public.routine_activities;
--           delete from public.notification_rules where kind = 'practice_due';
--           restore notification_rules_kind_check without it.

create table if not exists public.routine_activities (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 80),
  kind text not null check (kind in ('practice', 'pastime')),
  category text not null default 'other'
    check (category in ('mind', 'body', 'recovery', 'learning', 'creative', 'social', 'screen', 'outdoor', 'chores', 'other')),
  target_min integer check (target_min is null or target_min between 1 and 600),
  days smallint[] check (days is null or (cardinality(days) between 1 and 7 and days <@ array[1, 2, 3, 4, 5, 6, 7]::smallint[])),
  time_of_day time,
  remind boolean not null default false,
  archived_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists routine_activities_name_key on public.routine_activities (user_id, lower(btrim(name)));

create table if not exists public.routine_logs (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  activity_id bigint not null references public.routine_activities (id) on delete cascade,
  date date not null,
  started_at timestamptz,
  ended_at timestamptz,
  minutes real check (minutes is null or minutes between 0 and 1440),
  status text not null default 'done' check (status in ('done', 'skipped')),
  source text not null check (source in ('timer', 'manual', 'reminder')),
  note text check (note is null or length(note) <= 500),
  client_key text not null check (length(client_key) between 1 and 80),
  created_at timestamptz not null default now(),
  unique (user_id, client_key),
  check (ended_at is null or started_at is null or ended_at >= started_at)
);

create index if not exists routine_logs_user_date on public.routine_logs (user_id, date);

alter table public.routine_activities enable row level security;
alter table public.routine_logs enable row level security;

drop policy if exists routine_activities_select_own on public.routine_activities;
create policy routine_activities_select_own on public.routine_activities for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists routine_activities_insert_own on public.routine_activities;
create policy routine_activities_insert_own on public.routine_activities for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists routine_activities_update_own on public.routine_activities;
create policy routine_activities_update_own on public.routine_activities for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists routine_activities_delete_own on public.routine_activities;
create policy routine_activities_delete_own on public.routine_activities for delete to authenticated using (user_id = (select auth.uid()));

drop policy if exists routine_logs_select_own on public.routine_logs;
create policy routine_logs_select_own on public.routine_logs for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists routine_logs_insert_own on public.routine_logs;
create policy routine_logs_insert_own on public.routine_logs for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists routine_logs_update_own on public.routine_logs;
create policy routine_logs_update_own on public.routine_logs for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists routine_logs_delete_own on public.routine_logs;
create policy routine_logs_delete_own on public.routine_logs for delete to authenticated using (user_id = (select auth.uid()));

revoke all on public.routine_activities from anon;
revoke all on public.routine_logs from anon;

alter table public.notification_rules drop constraint if exists notification_rules_kind_check;
alter table public.notification_rules add constraint notification_rules_kind_check
  check (kind = any (array['supplement_due', 'checkin_morning', 'checkin_evening', 'wellbeing_checkin', 'evening_log',
                           'evening_checkin', 'body_checkin', 'fuel_session', 'fuel_event', 'health_checkin',
                           'event_rating', 'event_draw', 'event_followup', 'practice_due']));

insert into public.notification_rules (user_id, kind, enabled, schedule, conditions, limits)
select u.user_id, 'practice_due', true, '{}'::jsonb, '{"skip_if_logged": true}'::jsonb,
       '{"max_per_day": 6, "min_gap_min": 15, "snooze_min": 15}'::jsonb
from (select distinct user_id from public.notification_rules) u
on conflict (user_id, kind) do nothing;
