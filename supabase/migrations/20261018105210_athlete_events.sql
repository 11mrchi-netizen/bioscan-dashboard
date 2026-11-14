-- Events outside the training program (docs/fuel-planning/README.md, phase 1): races, long sessions, matches, hiking days
-- and other hard days. Until now the app only knew planned sessions from a training block and calendar events, so a race
-- or a hard day planned outside a block could not be represented or planned for (fuel, hydration, taper).
--
-- One row per event. The expected effort is a target time or an expected duration. The course, when there is one, is
-- kept on the row as a compact elevation profile (course_profile: [distance m, elevation m] every 100 m, from a GPX file
-- attached directly or through the calendar's Drive link), so a fuel plan can be built without the GPX file. Aid
-- stations are a short list (name, km, what they offer, cutoff). Once the event is done it points at its recorded
-- session, so load, the log and the review see one workout.
--
-- calendar_event_id links an event made from a calendar entry, so the same entry is not offered again.
--
-- Own rows only. Safe to run again.
--
-- Rollback: drop table public.athlete_events;

create table if not exists public.athlete_events (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name text not null check (length(name) between 1 and 120),
  kind text not null check (kind in ('race', 'long_session', 'match', 'hike_day', 'other_hard_day')),
  sport text not null check (sport in ('run', 'trail_run', 'ride', 'hike', 'swim', 'triathlon', 'team_sport', 'other')),
  priority text not null default 'B' check (priority in ('A', 'B', 'C')),
  event_date date not null,
  -- local wall-clock start with its offset, as the other timed rows
  start_time timestamptz,
  status text not null default 'planned' check (status in ('planned', 'done', 'dns', 'cancelled')),
  target_time_min numeric check (target_time_min is null or target_time_min between 1 and 10080),
  expected_duration_min numeric check (expected_duration_min is null or expected_duration_min between 1 and 10080),
  distance_km numeric check (distance_km is null or distance_km between 0 and 2000),
  ascent_m numeric check (ascent_m is null or ascent_m between 0 and 50000),
  descent_m numeric check (descent_m is null or descent_m between 0 and 50000),
  start_lat double precision check (start_lat is null or start_lat between -90 and 90),
  start_lon double precision check (start_lon is null or start_lon between -180 and 180),
  start_elevation_m numeric,
  location_name text check (location_name is null or length(location_name) <= 200),
  course_profile jsonb check (course_profile is null or jsonb_typeof(course_profile) = 'array'),
  course_source text check (course_source is null or course_source in ('gpx_file', 'drive_gpx')),
  aid_stations jsonb not null default '[]'::jsonb check (jsonb_typeof(aid_stations) = 'array'),
  calendar_event_id text,
  planned_route_id bigint references public.planned_routes (id) on delete set null,
  exercise_session_id bigint references public.exercise_sessions (id) on delete set null,
  notes text check (notes is null or length(notes) <= 4000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, calendar_event_id)
);

create index if not exists athlete_events_user_date_idx on public.athlete_events (user_id, event_date);

create or replace function public.athlete_events_touch_updated_at() returns trigger
language plpgsql set search_path = public as $$
begin
  new.updated_at := now();
  return new;
end
$$;

drop trigger if exists athlete_events_touch_updated_at on public.athlete_events;
create trigger athlete_events_touch_updated_at before update on public.athlete_events
  for each row execute function public.athlete_events_touch_updated_at();

alter table public.athlete_events enable row level security;

drop policy if exists athlete_events_select_own on public.athlete_events;
create policy athlete_events_select_own on public.athlete_events for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists athlete_events_insert_own on public.athlete_events;
create policy athlete_events_insert_own on public.athlete_events for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists athlete_events_update_own on public.athlete_events;
create policy athlete_events_update_own on public.athlete_events for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists athlete_events_delete_own on public.athlete_events;
create policy athlete_events_delete_own on public.athlete_events for delete to authenticated using (user_id = (select auth.uid()));
