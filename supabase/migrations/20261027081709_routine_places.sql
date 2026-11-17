-- Routine intelligence, phase R3 (docs/routine-intelligence/01-research-and-plan.md): places, trips and a day of mobility.
--
-- 1. places: where visits happen. Created on the phone when a visit falls outside every known place's circle; the person
--    names them and gives a category. osm_* hold one reverse lookup's suggestion (a point rounded to about 100 m).
-- 2. place_visits.place_id now references places (on delete set null: deleting a place keeps its visits).
-- 3. trips: between consecutive visits, with legs by activity, distance, active minutes and whether it is a commute.
-- 4. mobility_daily: one row per local day (00:00 to 24:00). coverage_pct is the share of the day a visit or trip covers;
--    the rest is unknown, never home.
--
-- Rollback: drop table public.mobility_daily, public.trips;
--           alter table public.place_visits drop constraint place_visits_place_id_fkey;
--           drop table public.places;

create table public.places (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  lat double precision not null check (lat between -90 and 90),
  lon double precision not null check (lon between -180 and 180),
  radius_m real not null default 150 check (radius_m between 25 and 2000),
  label text,
  category text check (category is null or category in
    ('home', 'work', 'exercise', 'food', 'social', 'shop', 'health', 'transit', 'outdoor', 'other')),
  -- learned: from visits; home_setting: Setup's home location; manual: added by hand
  source text not null default 'learned' check (source in ('learned', 'home_setting', 'manual')),
  osm_name text,
  osm_category text,
  osm_checked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index places_user on public.places (user_id);

alter table public.place_visits
  add constraint place_visits_place_id_fkey foreign key (place_id) references public.places (id) on delete set null;
create index place_visits_place on public.place_visits (place_id);

create table public.trips (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  start_at timestamptz not null,
  end_at timestamptz not null,
  from_place_id uuid references public.places (id) on delete set null,
  to_place_id uuid references public.places (id) on delete set null,
  distance_km real not null check (distance_km >= 0),
  -- [{mode, minutes}], modes from activity transitions (still, walking, running, on_bicycle, in_vehicle) or unknown
  legs jsonb not null default '[]'::jsonb,
  active_min real not null default 0 check (active_min >= 0),
  is_commute boolean not null default false,
  primary key (user_id, start_at),
  check (end_at > start_at)
);

create index trips_from_place on public.trips (from_place_id);
create index trips_to_place on public.trips (to_place_id);

create table public.mobility_daily (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  date date not null,
  coverage_pct smallint not null check (coverage_pct between 0 and 100),
  -- {category: minutes}, plus unlabelled, transit and unknown
  minutes_by_category jsonb not null default '{}'::jsonb,
  n_places integer not null check (n_places >= 0),
  new_places integer not null check (new_places >= 0),
  entropy_norm real check (entropy_norm is null or entropy_norm between 0 and 1),
  radius_gyration_km real check (radius_gyration_km is null or radius_gyration_km >= 0),
  time_from_home_min real not null check (time_from_home_min >= 0),
  outdoor_daylight_min real not null check (outdoor_daylight_min >= 0),
  routine_similarity real check (routine_similarity is null or routine_similarity between 0 and 1),
  day_type text not null check (day_type in ('workday', 'home_day', 'mixed', 'travel', 'unknown')),
  recorded_at timestamptz not null default now(),
  primary key (user_id, date)
);

do $$
declare t text;
begin
  foreach t in array array['places', 'trips', 'mobility_daily']
  loop
    execute format('alter table public.%I enable row level security', t);
    execute format('create policy %I on public.%I for select to authenticated using (user_id = (select auth.uid()))', t || '_select_own', t);
    execute format('create policy %I on public.%I for insert to authenticated with check (user_id = (select auth.uid()))', t || '_insert_own', t);
    execute format('create policy %I on public.%I for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))', t || '_update_own', t);
    execute format('create policy %I on public.%I for delete to authenticated using (user_id = (select auth.uid()))', t || '_delete_own', t);
  end loop;
end
$$;
