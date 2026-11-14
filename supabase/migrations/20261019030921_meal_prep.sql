-- Sunday meal prep and calendar answers (docs/fuel-planning/README.md, phase 11).
--   meal_prep_weeks           one prep week per person and Monday: the picks (food ids), the four recipes, the meals
--                             marked as eaten out, the containers once cooked (grams per food), and the status.
--   breakfast_templates       the person's fixed breakfast (food ids and usual grams), scaled each day by the app.
--   calendar_event_overrides  what the person said a calendar entry is when the plan could not read it (WHAT IS THIS?):
--                             ignore it, or count it as a session of a length and class.
-- Food ids are the app's ("staple:rice_brown", or "food:<foods.id>"). Own rows only. Safe to run again.
--
-- Rollback: drop table public.meal_prep_weeks, public.breakfast_templates, public.calendar_event_overrides;

create table if not exists public.meal_prep_weeks (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  week_start date not null check (extract(isodow from week_start) = 1),
  picks jsonb not null default '{}'::jsonb check (jsonb_typeof(picks) = 'object'),
  recipes jsonb not null default '[]'::jsonb check (jsonb_typeof(recipes) = 'array' and jsonb_array_length(recipes) <= 4),
  skipped jsonb not null default '[]'::jsonb check (jsonb_typeof(skipped) = 'array' and jsonb_array_length(skipped) <= 10),
  containers jsonb check (containers is null or (jsonb_typeof(containers) = 'array' and jsonb_array_length(containers) <= 10)),
  status text not null default 'planned' check (status in ('planned', 'cooked')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, week_start)
);

create table if not exists public.breakfast_templates (
  user_id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  items jsonb not null check (jsonb_typeof(items) = 'array' and jsonb_array_length(items) between 1 and 8),
  updated_at timestamptz not null default now()
);

create table if not exists public.calendar_event_overrides (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  event_id text not null check (length(event_id) between 1 and 1024),
  treat_as text not null check (treat_as in ('ignore', 'long_session')),
  duration_min numeric check (duration_min is null or duration_min between 10 and 1440),
  intensity text check (intensity is null or intensity in ('easy', 'moderate', 'hard', 'long')),
  created_at timestamptz not null default now(),
  unique (user_id, event_id)
);

alter table public.meal_prep_weeks enable row level security;
alter table public.breakfast_templates enable row level security;
alter table public.calendar_event_overrides enable row level security;

drop policy if exists meal_prep_weeks_select_own on public.meal_prep_weeks;
create policy meal_prep_weeks_select_own on public.meal_prep_weeks for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists meal_prep_weeks_insert_own on public.meal_prep_weeks;
create policy meal_prep_weeks_insert_own on public.meal_prep_weeks for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists meal_prep_weeks_update_own on public.meal_prep_weeks;
create policy meal_prep_weeks_update_own on public.meal_prep_weeks for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists meal_prep_weeks_delete_own on public.meal_prep_weeks;
create policy meal_prep_weeks_delete_own on public.meal_prep_weeks for delete to authenticated using (user_id = (select auth.uid()));

drop policy if exists breakfast_templates_select_own on public.breakfast_templates;
create policy breakfast_templates_select_own on public.breakfast_templates for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists breakfast_templates_insert_own on public.breakfast_templates;
create policy breakfast_templates_insert_own on public.breakfast_templates for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists breakfast_templates_update_own on public.breakfast_templates;
create policy breakfast_templates_update_own on public.breakfast_templates for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists breakfast_templates_delete_own on public.breakfast_templates;
create policy breakfast_templates_delete_own on public.breakfast_templates for delete to authenticated using (user_id = (select auth.uid()));

drop policy if exists calendar_event_overrides_select_own on public.calendar_event_overrides;
create policy calendar_event_overrides_select_own on public.calendar_event_overrides for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists calendar_event_overrides_insert_own on public.calendar_event_overrides;
create policy calendar_event_overrides_insert_own on public.calendar_event_overrides for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists calendar_event_overrides_update_own on public.calendar_event_overrides;
create policy calendar_event_overrides_update_own on public.calendar_event_overrides for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists calendar_event_overrides_delete_own on public.calendar_event_overrides;
create policy calendar_event_overrides_delete_own on public.calendar_event_overrides for delete to authenticated using (user_id = (select auth.uid()));
