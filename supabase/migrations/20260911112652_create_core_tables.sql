-- injuries: mirrors DASHBOARD_DATA.injuries field-for-field from the dashboard's
-- existing client-side data model, so swapping the fetch source later is a clean match.
create table injuries (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  part text not null,
  type text not null,
  severity smallint not null check (severity between 0 and 10),
  status text not null check (status in ('active','monitoring','resolved')),
  start_date date not null,
  end_date date,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- supplements: mirrors DASHBOARD_DATA.supplements
create table supplements (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  dose text not null,
  time_of_day text not null check (time_of_day in ('morning','afternoon','night','as-needed')),
  status text not null check (status in ('active','ended')),
  start_date date not null,
  end_date date,
  target text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- lab_draws: one row per bloodwork draw event (date + lab name)
create table lab_draws (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  draw_date date not null,
  lab_name text,
  created_at timestamptz not null default now()
);

-- lab_results: individual marker values, one row per marker per draw
create table lab_results (
  id bigint generated always as identity primary key,
  draw_id bigint not null references lab_draws(id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  marker_name text not null,
  value numeric,
  value_text text,
  unit text,
  ref_low numeric,
  ref_high numeric,
  flag text check (flag in ('normal','low','high','watch')),
  created_at timestamptz not null default now()
);

-- sync_log: persists the dashboard's new/changed/resolved diff state server-side
-- (upgrade from the browser-only window.storage snapshot used before the backend existed)
create table sync_log (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  synced_at timestamptz not null default now(),
  sync_source text not null,
  changes_summary jsonb
);
