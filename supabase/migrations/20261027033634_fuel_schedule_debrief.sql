-- Race fuel rework (docs/race-fuel-rework/plan.md): an editable pre-race schedule and a post-race debrief.
--
--  1. fuel_schedules: the schedule as blocks (a product, the nutrients in one intake, when the first is, how often, how many).
--     Keyed on the event, or on the planned session, or on the synced activity (kind 'event' | 'planned' | 'session'), so a long
--     training run gets one before it exists as an activity. Unlike fuel_plans (append-only, a snapshot of the planner's output)
--     this table is edited in place.
--  2. fuel_plan_reviews: the debrief's extra answers (discomfort ticked, tiredness, thirst, how much of the schedule held) and
--     one debrief per activity, so a run without an event can have one (event_id stays optional).
--  3. fueling_products.kind: water, water with electrolytes, pre-workout, tablet and mix join gel, drink mix, chew and bar.
--  4. fueling_recipe_ingredients: an ingredient can be a supplement product (a pre-workout, an electrolyte tablet) as well as a
--     food, so a mix is built from both catalogs. fueling_intake_log is the ledger a debrief writes; a pre-workout taken in the
--     race must NOT also be written to supplement_log, or nutrient_intake counts it twice.
--  5. fueling_intake_log.elapsed_seconds may be negative down to -4 h: intake before the start (a pre-workout at -30 min).
--     Pre-start intake counts in the totals but not in the per-hour rates (fuelingSummary.ts).
--
-- Own rows only. Safe to run again.
--
-- Rollback: drop table public.fuel_schedules;
--           alter table public.fuel_plan_reviews drop constraint fuel_plan_reviews_user_session_key, drop column symptoms,
--             drop column fatigue_score, drop column thirst_score, drop column schedule_held, drop column schedule_id;
--           alter table public.fueling_recipe_ingredients drop column supplement_product_id, drop column supplement_servings;
--           restore fueling_products_kind_check (gel, drink_mix, chew, bar, homemade, other) and
--           fueling_intake_log_elapsed_seconds_check (elapsed_seconds >= 0) once no row breaks them.

-- 3
alter table public.fueling_products drop constraint if exists fueling_products_kind_check;
alter table public.fueling_products add constraint fueling_products_kind_check
  check (kind in ('gel', 'drink_mix', 'chew', 'bar', 'homemade', 'other', 'water', 'electrolyte_drink', 'preworkout', 'tablet', 'mix'));

-- 4
alter table public.fueling_recipe_ingredients add column if not exists supplement_product_id bigint references public.supplement_products (id) on delete set null;
alter table public.fueling_recipe_ingredients add column if not exists supplement_servings numeric check (supplement_servings is null or supplement_servings > 0);

-- 5
alter table public.fueling_intake_log drop constraint if exists fueling_intake_log_elapsed_seconds_check;
alter table public.fueling_intake_log add constraint fueling_intake_log_elapsed_seconds_check check (elapsed_seconds >= -14400);

-- 1
create table if not exists public.fuel_schedules (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  kind text not null check (kind in ('event', 'planned', 'session')),
  ref_key text not null check (length(ref_key) between 1 and 64),
  duration_min integer not null check (duration_min between 1 and 10080),
  blocks jsonb not null check (jsonb_typeof(blocks) = 'array'),
  seeded_from text check (seeded_from is null or seeded_from in ('plan', 'manual')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, kind, ref_key)
);

alter table public.fuel_schedules enable row level security;

drop policy if exists fuel_schedules_select_own on public.fuel_schedules;
create policy fuel_schedules_select_own on public.fuel_schedules for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists fuel_schedules_insert_own on public.fuel_schedules;
create policy fuel_schedules_insert_own on public.fuel_schedules for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists fuel_schedules_update_own on public.fuel_schedules;
create policy fuel_schedules_update_own on public.fuel_schedules for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists fuel_schedules_delete_own on public.fuel_schedules;
create policy fuel_schedules_delete_own on public.fuel_schedules for delete to authenticated using (user_id = (select auth.uid()));

-- 2
alter table public.fuel_plan_reviews add column if not exists symptoms text[] not null default '{}';
alter table public.fuel_plan_reviews add column if not exists fatigue_score smallint check (fatigue_score is null or fatigue_score between 0 and 10);
alter table public.fuel_plan_reviews add column if not exists thirst_score smallint check (thirst_score is null or thirst_score between 0 and 10);
alter table public.fuel_plan_reviews add column if not exists schedule_held numeric check (schedule_held is null or schedule_held between 0 and 1);
alter table public.fuel_plan_reviews add column if not exists schedule_id bigint references public.fuel_schedules (id) on delete set null;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'fuel_plan_reviews_symptoms_check') then
    alter table public.fuel_plan_reviews add constraint fuel_plan_reviews_symptoms_check
      check (symptoms <@ array['nausea', 'cramps', 'bloating', 'reflux', 'side_stitch', 'vomiting', 'dizziness']::text[]);
  end if;
  -- One debrief per activity. A full unique constraint (not a partial index) so an upsert can name it; nulls stay distinct, so
  -- reviews without a linked activity are not constrained.
  if not exists (select 1 from pg_constraint where conname = 'fuel_plan_reviews_user_session_key') then
    alter table public.fuel_plan_reviews add constraint fuel_plan_reviews_user_session_key unique (user_id, exercise_session_id);
  end if;
end $$;
