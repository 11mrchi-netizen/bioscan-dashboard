-- Analysis review phase B (docs/analysis-review/README.md): capture built for cause-and-effect questions.
--
-- 1. evening_checkins: one row per local evening, asked at 22:25. Every exposure is answered explicitly (null = not
--    answered, 0 / false = none), so a quiet evening is a real zero instead of "no food log that day", which the engine
--    used to read as sober and caffeine-free.
-- 2. daily_context: what the phone knows passively about each day: its UTC offset (the analysis engine turns timestamps
--    into local dates and hours with it; it used UTC, so a 15:00 coffee in UTC+8 counted as morning caffeine) and,
--    when location is available, the day's weather and day length.
-- 3. notification_rules: a new evening_checkin reminder at 22:25, seeded for accounts that already exist.
--
-- Rollback: drop table public.evening_checkins; drop table public.daily_context;
--           delete from public.notification_rules where kind = 'evening_checkin';
--           then re-add the five-value kind check from 20261012_notification_capture_kinds.sql.

create table public.evening_checkins (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- the local evening the answers are about (a check-in saved at 00:40 belongs to the evening before; the app decides)
  date date not null,
  drinks smallint check (drinks is null or drinks between 0 and 30),
  caffeine_after_14 boolean,
  late_meal boolean,
  stress smallint check (stress is null or stress between 1 and 5),
  late_screen boolean,
  note text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, date)
);

create or replace function public.evening_checkins_touch()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger evening_checkins_touch before update on public.evening_checkins
  for each row execute function public.evening_checkins_touch();

alter table public.evening_checkins enable row level security;

create policy evening_checkins_select_own on public.evening_checkins for select to authenticated using (user_id = (select auth.uid()));
create policy evening_checkins_insert_own on public.evening_checkins for insert to authenticated with check (user_id = (select auth.uid()));
create policy evening_checkins_update_own on public.evening_checkins for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy evening_checkins_delete_own on public.evening_checkins for delete to authenticated using (user_id = (select auth.uid()));

create table public.daily_context (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  date date not null,
  utc_offset_min smallint not null check (utc_offset_min between -840 and 840),
  time_zone text,
  temp_max_c real,
  humidity_mean_pct real,
  daylight_hours real check (daylight_hours is null or daylight_hours between 0 and 24),
  source text not null default 'phone',
  recorded_at timestamptz not null default now(),
  primary key (user_id, date)
);

alter table public.daily_context enable row level security;

create policy daily_context_select_own on public.daily_context for select to authenticated using (user_id = (select auth.uid()));
create policy daily_context_insert_own on public.daily_context for insert to authenticated with check (user_id = (select auth.uid()));
create policy daily_context_update_own on public.daily_context for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

alter table public.notification_rules drop constraint notification_rules_kind_check;
alter table public.notification_rules add constraint notification_rules_kind_check
  check (kind = any (array['supplement_due', 'checkin_morning', 'checkin_evening', 'wellbeing_checkin', 'evening_log', 'evening_checkin']));

insert into public.notification_rules (user_id, kind, enabled, schedule, conditions, limits)
select u.user_id, 'evening_checkin', true, '{"times": ["22:25"]}'::jsonb, '{"skip_if_logged": true}'::jsonb,
       '{"max_per_day": 1, "min_gap_min": 60, "snooze_min": 20}'::jsonb
from (select distinct user_id from public.notification_rules) u
on conflict (user_id, kind) do nothing;
