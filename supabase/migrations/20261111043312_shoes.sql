-- Shoes (docs/zepp-integration/03-stress-days-and-shoes.md). Zepp tags a run with the shoes picked on the watch or in
-- the app (the summary's gearId) but sends no names, so the person keeps their shoes here and gives each the Zepp code
-- it goes by. A run's shoes: the person's own choice (exercise_sessions.shoe_id) first, else the shoe its Zepp code is
-- matched to. Bike rides carry no code.
--
-- Rollback: alter table public.exercise_sessions drop column shoe_id; alter table public.zepp_workout_detail drop column gear_id;
--           drop table public.shoes;

create table if not exists public.shoes (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 80),
  use text not null default 'road' check (use in ('road', 'trail', 'treadmill', 'any')),
  zepp_gear_id text check (zepp_gear_id is null or length(zepp_gear_id) between 1 and 100),
  -- a small square JPEG (base64), the shoe's icon
  photo_jpeg text check (photo_jpeg is null or length(photo_jpeg) <= 400000),
  start_km numeric not null default 0 check (start_km between 0 and 10000),
  retire_km numeric not null default 700 check (retire_km between 100 and 5000),
  started_on date,
  retired_on date,
  notes text check (notes is null or length(notes) <= 2000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, zepp_gear_id)
);

alter table public.shoes enable row level security;
drop policy if exists shoes_select_own on public.shoes;
create policy shoes_select_own on public.shoes for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists shoes_insert_own on public.shoes;
create policy shoes_insert_own on public.shoes for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists shoes_update_own on public.shoes;
create policy shoes_update_own on public.shoes for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists shoes_delete_own on public.shoes;
create policy shoes_delete_own on public.shoes for delete to authenticated using (user_id = (select auth.uid()));
revoke all on public.shoes from anon;

-- The person's own choice of shoes for a session (wins over the Zepp code).
alter table public.exercise_sessions add column if not exists shoe_id bigint references public.shoes (id) on delete set null;
create index if not exists exercise_sessions_shoe_id on public.exercise_sessions (shoe_id) where shoe_id is not null;

-- Zepp's code for the shoes of a workout (the sport_history summary's gearId), from the stored summaries.
alter table public.zepp_workout_detail add column if not exists gear_id text;
comment on column public.zepp_workout_detail.gear_id is 'Zepp gearId of the shoes picked for this workout (summary entry); null when none. Zepp sends no names: shoes.zepp_gear_id names it.';

update public.zepp_workout_detail d set gear_id = x.gear
from (
  select r.user_id, s->>'trackid' as track, max(nullif(s->>'gearId', '')) as gear
  from public.zepp_raw_extracts r, jsonb_array_elements(coalesce(r.raw_body->'data'->'summary', '[]'::jsonb)) s
  where r.metric = 'sport_history'
  group by 1, 2
) x
where d.user_id = x.user_id and d.zepp_track_id = x.track and x.gear is not null and d.gear_id is distinct from x.gear;
