-- illnesses: mirrors the injuries lifecycle pattern (onset/resolution/severity/status)
-- but with illness-specific fields instead of injury-specific ones.
create table illnesses (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  symptoms text,
  diagnosis text,
  medication text,
  severity smallint check (severity between 0 and 10),
  status text not null check (status in ('active','monitoring','resolved')),
  start_date date not null,
  end_date date,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- people: demographics, referenced by encounters below.
create table people (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  demographics jsonb,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- encounters: pending -> logged lifecycle, per the roadmap's calendar-driven
-- auto-detection design (Flamingo-colored events create a 'pending' row,
-- next-morning prompt fills in detail -> 'logged').
create table encounters (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  person_id bigint references people(id) on delete set null,
  date date not null,
  duration_min integer,
  location text,
  status text not null check (status in ('pending','logged')) default 'pending',
  detail jsonb,
  evaluation jsonb,
  calendar_event_title text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
