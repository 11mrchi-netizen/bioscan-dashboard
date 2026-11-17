-- Event-log tables: one row per occurrence, not per day. Dashboard aggregates
-- meals by day at query time rather than losing per-meal detail upfront.

create table meals (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  logged_at timestamptz not null,
  description text,
  calories numeric,
  protein_g numeric,
  fat_g numeric,
  carbs_g numeric,
  created_at timestamptz not null default now()
);

create table recovery_sessions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null,
  name text not null,
  category text,
  duration_min integer,
  notes text,
  created_at timestamptz not null default now()
);

create table rest_days (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null,
  source text,
  created_at timestamptz not null default now(),
  unique (user_id, date)
);

create table runs (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null,
  distance_km numeric,
  duration_min numeric,
  pace_min_per_km numeric,
  route_type text check (route_type in ('road','trail')),
  avg_hr numeric,
  rpe smallint check (rpe between 0 and 10),
  notes text,
  created_at timestamptz not null default now()
);

-- P4: daily subjective sexual-health signals — same shape/spirit as
-- wellbeing_daily (quick 1-10 scale), kept as its own table since it's a
-- distinct data domain the person explicitly wanted separated out.
create table arousal_daily (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null,
  morning_erection_quality smallint check (morning_erection_quality between 0 and 10),
  arousal_level smallint check (arousal_level between 0 and 10),
  notes text,
  created_at timestamptz not null default now(),
  unique (user_id, date)
);

create table masturbation_log (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  occurred_at timestamptz not null,
  notes text,
  created_at timestamptz not null default now()
);
