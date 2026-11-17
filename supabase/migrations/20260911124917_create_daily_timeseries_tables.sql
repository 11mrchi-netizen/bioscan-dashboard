-- Daily time-series tables: one row per user per day, upsertable via
-- (user_id, date) unique constraint — matches the dashboard's existing
-- DASHBOARD_DATA.wearable/.sleep/.wellbeing/.hydration/.bodyMetrics shapes.

create table wearable_daily (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null,
  steps integer,
  rhr numeric,
  hrv numeric,
  vo2max numeric,
  zone_minutes integer,
  created_at timestamptz not null default now(),
  unique (user_id, date)
);

create table sleep_daily (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null,
  hours numeric,
  score smallint,
  respiratory_rate numeric,
  created_at timestamptz not null default now(),
  unique (user_id, date)
);

create table wellbeing_daily (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null,
  energy smallint check (energy between 0 and 10),
  mood smallint check (mood between 0 and 10),
  stress smallint check (stress between 0 and 10),
  soreness smallint check (soreness between 0 and 10),
  created_at timestamptz not null default now(),
  unique (user_id, date)
);

create table hydration_daily (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null,
  ml integer,
  created_at timestamptz not null default now(),
  unique (user_id, date)
);

create table body_metrics (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null,
  weight_kg numeric,
  body_fat_pct numeric,
  lean_mass_kg numeric,
  created_at timestamptz not null default now(),
  unique (user_id, date)
);
