-- Computed values the pages show over time (nutrition proposal, filling the display gaps; docs/nutrition/README.md).
-- Written by analysis-run (engine `derived`, group `reports`) from analysis-core, read by the console and the phone.
--
-- derived_daily: one value per user, metric and local date. Metrics:
--   gut_day_score         the daily digestion score, 0-100 (mealExposures.ts gutDayScore)
--   energy_availability   kcal per kg fat-free mass after training (trainingFuel.ts energyAvailability);
--                         detail carries intake_kcal, exercise_kcal and ffm_kg
-- session_fuel: the food around each training session of 20 min or more (trainingFuel.ts sessionFuel).

create table public.derived_daily (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  metric text not null,
  date date not null,
  value double precision not null,
  detail jsonb,
  computed_at timestamptz not null default now(),
  unique (user_id, metric, date)
);
alter table public.derived_daily enable row level security;
create policy derived_daily_read on public.derived_daily for select to authenticated using (user_id = auth.uid());

create table public.session_fuel (
  session_id bigint primary key references public.exercise_sessions (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  date date not null,
  duration_min integer not null,
  long boolean not null,
  hours_since_meal double precision,
  fasted boolean not null,
  carbs_before_g integer not null,
  carbs_during_g integer,
  protein_after_g integer not null,
  carbs_after_g integer not null,
  protein_dose_g integer not null,
  computed_at timestamptz not null default now()
);
create index session_fuel_user_date on public.session_fuel (user_id, date);
alter table public.session_fuel enable row level security;
create policy session_fuel_read on public.session_fuel for select to authenticated using (user_id = auth.uid());
