-- Both tables are empty (never populated) — safe to drop and rebuild with proper
-- columns matching the real spreadsheet structure, rather than the original
-- loose jsonb-heavy design from before the real data was seen.

drop table if exists encounters;
drop table if exists people;

create table people (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  sheet_num integer, -- original "Num." column from the spreadsheet, kept for traceability during/after migration
  name text not null,
  period text, -- life-era/location tag, e.g. "Wanlong", "Zhongshan (single life)"
  score smallint check (score between 1 and 5),
  where_met text, -- Tinder / Bumble / OKC / IRL / Book Club / Munch / Feeld / Massage Republic / Lifestyle Event / Instagram / etc.
  relationship text, -- One Night Stand (in/not in contact) / Friend(s) with Benefits (in/not in contact) / Party Friends / Previous Romantic Relationship / Work / etc.
  gender text, -- Female / MTF / Male / Male CD
  country text,
  age_range text, -- kept as text ("21-29") since the sheet uses ranges, not exact ages
  body_type text,
  activities text[], -- "What we did?" tag list
  interested_in text[], -- "What are they down for?" tag list
  first_met_date date,
  availability text, -- "Yes"/"No"/"Maybe" from the sheet's Availability column
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table encounters (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  sheet_encounter_id text, -- original "E001" style id from the spreadsheet, kept for traceability
  person_id bigint references people(id) on delete set null,
  date date not null,
  encounter_type text, -- First Time / Repeat / FFM / MFM / Party / Couple Swap / GB / etc.
  location_type text, -- My Place / Her Place / Hotel / Motel / Outdoor
  duration_min integer,
  activities text[],
  my_rating smallint check (my_rating between 1 and 5),
  physical_rating smallint check (physical_rating between 1 and 5),
  connection_rating smallint check (connection_rating between 1 and 5),
  notes text,
  status text not null check (status in ('pending','logged')) default 'logged', -- 'pending' = auto-created by calendar detection, awaiting detail; 'logged' = fully filled in (all migrated historical rows are 'logged')
  calendar_event_title text, -- populated only for future calendar-detected rows, null for migrated history
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table people enable row level security;
alter table encounters enable row level security;

create policy "select own people" on people for select using ((select auth.uid()) = user_id);
create policy "insert own people" on people for insert with check ((select auth.uid()) = user_id);
create policy "update own people" on people for update using ((select auth.uid()) = user_id);
create policy "delete own people" on people for delete using ((select auth.uid()) = user_id);

create policy "select own encounters" on encounters for select using ((select auth.uid()) = user_id);
create policy "insert own encounters" on encounters for insert with check ((select auth.uid()) = user_id);
create policy "update own encounters" on encounters for update using ((select auth.uid()) = user_id);
create policy "delete own encounters" on encounters for delete using ((select auth.uid()) = user_id);

create index people_user_id_idx on people (user_id);
create index encounters_user_id_idx on encounters (user_id);
create index encounters_person_id_idx on encounters (person_id);
