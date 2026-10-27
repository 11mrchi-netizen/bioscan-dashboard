-- Routine intelligence, phases R1 and R2 (docs/routine-intelligence/01-research-and-plan.md).
--
-- R1, phone use, from Android's usage events (one read a day, the raw events are not stored):
-- 1. phone_usage_daily: one row per local day (04:00 to 04:00, the small hours belong to the evening before, as for
--    the evening check-in). The bed window comes from sleep_daily: the night that ends on date + 1.
-- 2. phone_app_usage_daily: the same day per app (package and label, owner decision 3).
-- 3. phone_app_categories: the person's own category for an app, over the one the app declares.
--
-- R2, location capture (owner decision 2: fixes and coordinates are kept here):
-- 4. location_fixes: fixes taken during trips and one an hour while the collector is alive.
-- 5. activity_transitions: Android's activity transitions (still, walking, running, on a bicycle, in a vehicle).
-- 6. collector_heartbeats: proof the collector was alive, so a gap reads as unknown, never as a stay.
-- 7. place_visits: stays of 10 minutes or more within 150 m, found on the phone. place_id is filled in phase R3,
--    when places exist (its foreign key is added then).
--
-- Every table: RLS, owner only, for all four commands (the app's DELETE ALL needs delete).
--
-- Rollback: drop table public.place_visits, public.collector_heartbeats, public.activity_transitions,
--           public.location_fixes, public.phone_app_categories, public.phone_app_usage_daily, public.phone_usage_daily;

create table public.phone_usage_daily (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  date date not null,
  coverage_pct smallint not null check (coverage_pct between 0 and 100),
  screen_min real not null check (screen_min >= 0),
  unlocks integer not null check (unlocks >= 0),
  first_unlock_at timestamptz,
  -- the end of the last screen use that started before sleep onset (null without a sleep record for the night)
  last_use_before_sleep_at timestamptz,
  -- screen minutes in the hour before sleep onset, and between onset and wake
  bed_window_min real check (bed_window_min is null or bed_window_min >= 0),
  night_screen_min real check (night_screen_min is null or night_screen_min >= 0),
  night_unlocks integer check (night_unlocks is null or night_unlocks >= 0),
  -- longest stretch without the screen on between 08:00 and 22:00
  longest_free_min real check (longest_free_min is null or longest_free_min >= 0),
  -- {category: minutes}
  by_category jsonb not null default '{}'::jsonb,
  -- {place category: minutes}, from phase R3
  by_place_category jsonb,
  source text not null default 'android_usage_stats',
  recorded_at timestamptz not null default now(),
  primary key (user_id, date)
);

create table public.phone_app_usage_daily (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  date date not null,
  package text not null,
  app_label text,
  category text not null,
  foreground_min real not null check (foreground_min >= 0),
  launches integer not null check (launches >= 0),
  bed_window_min real check (bed_window_min is null or bed_window_min >= 0),
  after_21_min real not null default 0 check (after_21_min >= 0),
  recorded_at timestamptz not null default now(),
  primary key (user_id, date, package)
);

create table public.phone_app_categories (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  package text not null,
  category text not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, package)
);

create table public.location_fixes (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  ts timestamptz not null,
  lat double precision not null check (lat between -90 and 90),
  lon double precision not null check (lon between -180 and 180),
  accuracy_m real check (accuracy_m is null or accuracy_m >= 0),
  -- trip: taken while a trip was being recorded; heartbeat: the hourly fix while staying
  kind text not null check (kind in ('trip', 'heartbeat')),
  activity text,
  primary key (user_id, ts)
);

create table public.activity_transitions (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  ts timestamptz not null,
  activity text not null check (activity in ('still', 'walking', 'running', 'on_bicycle', 'in_vehicle')),
  transition text not null check (transition in ('enter', 'exit')),
  primary key (user_id, ts, activity, transition)
);

create table public.collector_heartbeats (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  stream text not null check (stream in ('location', 'phone_usage')),
  ts timestamptz not null,
  primary key (user_id, stream, ts)
);

create table public.place_visits (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  start_at timestamptz not null,
  end_at timestamptz not null,
  lat double precision not null,
  lon double precision not null,
  n_fixes integer not null check (n_fixes >= 2),
  -- share of the stay the collector was provably alive (heartbeats or fixes no more than 90 minutes apart)
  confidence real not null check (confidence between 0 and 1),
  -- the stay reaches the newest fix: the person may still be there, so end_at can still move
  open boolean not null default false,
  place_id uuid,
  primary key (user_id, start_at),
  check (end_at >= start_at)
);

create index location_fixes_ts on public.location_fixes (user_id, ts desc);
create index place_visits_end on public.place_visits (user_id, end_at desc);

do $$
declare t text;
begin
  foreach t in array array['phone_usage_daily', 'phone_app_usage_daily', 'phone_app_categories', 'location_fixes',
                           'activity_transitions', 'collector_heartbeats', 'place_visits']
  loop
    execute format('alter table public.%I enable row level security', t);
    execute format('create policy %I on public.%I for select to authenticated using (user_id = (select auth.uid()))', t || '_select_own', t);
    execute format('create policy %I on public.%I for insert to authenticated with check (user_id = (select auth.uid()))', t || '_insert_own', t);
    execute format('create policy %I on public.%I for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))', t || '_update_own', t);
    execute format('create policy %I on public.%I for delete to authenticated using (user_id = (select auth.uid()))', t || '_delete_own', t);
  end loop;
end
$$;
