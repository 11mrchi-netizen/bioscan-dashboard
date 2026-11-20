-- Risk profile R3 (docs/risk-profile/01-plan.md): the one-time answers no sensor or lab gives, read by the risk screens
-- (android domain/risk/Screens.kt: PREVENT, Life's Essential 8, FINDRISC, the Lancet dementia factors). One row per
-- person, edited in place; every field nullable (not answered). Own row only.
-- Rollback: drop table public.health_background;

create table public.health_background (
  user_id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  smoking text check (smoking is null or smoking in ('never', 'former', 'current')),
  quit_year smallint check (quit_year is null or quit_year between 1940 and 2100),
  secondhand_smoke boolean,
  diabetes_diagnosed boolean,
  bp_meds boolean,
  lipid_meds boolean,
  high_glucose_history boolean,
  family_diabetes text check (family_diabetes is null or family_diabetes in ('none', 'second', 'first')),
  veg_fruit_daily boolean,
  hearing text check (hearing is null or hearing in ('good', 'aided', 'difficulty')),
  vision text check (vision is null or vision in ('good', 'corrected', 'difficulty')),
  head_injury boolean,
  education text check (education is null or education in ('primary', 'secondary', 'tertiary')),
  updated_at timestamptz not null default now()
);

alter table public.health_background enable row level security;

create policy health_background_select_own on public.health_background for select to authenticated using (user_id = (select auth.uid()));
create policy health_background_insert_own on public.health_background for insert to authenticated with check (user_id = (select auth.uid()));
create policy health_background_update_own on public.health_background for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

comment on table public.health_background is 'One-time health background answers for the risk screens (risk profile R3). Own row only.';
