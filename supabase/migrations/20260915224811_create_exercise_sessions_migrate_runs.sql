create table public.exercise_sessions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id),
  type text not null default 'other',
  start_time timestamptz not null,
  end_time timestamptz not null,
  duration_min numeric,
  distance_km numeric,
  calories_active numeric,
  calories_total numeric,
  avg_hr numeric,
  max_hr numeric,
  elevation_gain_m numeric,
  avg_power_w numeric,
  avg_speed_kmh numeric,
  rpe smallint check (rpe >= 0 and rpe <= 10),
  notes text,
  details jsonb not null default '{}'::jsonb,
  source text not null default 'health_connect' check (source in ('manual', 'health_connect')),
  health_connect_record_id text,
  created_at timestamptz not null default now()
);

comment on table public.exercise_sessions is 'Phase G3: generic multi-type exercise sessions (any type, not just runs), replacing the run-only `runs` table. `details` is a flexible jsonb for type-specific extras (e.g. route_type for runs, sets for strength) Health Connect does not carry -- kept basic on purpose, no fixed schema yet.';

create unique index exercise_sessions_hc_record_uidx
  on public.exercise_sessions (user_id, health_connect_record_id)
  where health_connect_record_id is not null;
create index exercise_sessions_user_start_idx on public.exercise_sessions (user_id, start_time desc);

alter table public.exercise_sessions enable row level security;
create policy "select own exercise_sessions" on public.exercise_sessions for select using ((select auth.uid()) = user_id);
create policy "insert own exercise_sessions" on public.exercise_sessions for insert with check ((select auth.uid()) = user_id);
create policy "update own exercise_sessions" on public.exercise_sessions for update using ((select auth.uid()) = user_id);
create policy "delete own exercise_sessions" on public.exercise_sessions for delete using ((select auth.uid()) = user_id);

-- Migrate the 19 existing `runs` rows in (nominal 07:00 start time, matching
-- the Android app's own RUN_NOMINAL_TIME convention for this date-only
-- table -- `runs` never carried a real time-of-day). `route_type` moves
-- into `details`. Source stays 'manual' for these -- they predate Health
-- Connect and have no health_connect_record_id.
insert into public.exercise_sessions
  (user_id, type, start_time, end_time, duration_min, distance_km, avg_hr, rpe, notes, details, source, created_at)
select
  user_id,
  'run',
  (date::timestamp + time '07:00') at time zone 'UTC',
  (date::timestamp + time '07:00') at time zone 'UTC' + make_interval(mins => coalesce(duration_min, 0)::int),
  duration_min,
  distance_km,
  avg_hr,
  rpe,
  notes,
  jsonb_build_object('route_type', route_type),
  'manual',
  created_at
from public.runs;

-- Renamed, not dropped -- recoverable if this migration has a bug.
alter table public.runs rename to runs_retired;
