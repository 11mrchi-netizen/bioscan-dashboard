-- Risk profile R0 (docs/risk-profile/01-plan.md): home blood pressure readings.
-- One row per reading. Readings taken within a few minutes of each other form one sitting; the app groups them, so there
-- is no sitting column. Clinic readings stay where they are (lab_results: 'SBP, Sitting' / 'DBP, Sitting').
-- source: 'health_connect' (a cuff app wrote it to Health Connect; hc_record_id is that record's id, so a re-import
-- changes nothing) or 'manual' (typed in the add sheet).
-- Rollback: drop table public.bp_readings;

create table public.bp_readings (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- logged time: the local wall-clock reading labelled +00, same convention as grip_strength_log (WallClock)
  occurred_at timestamptz not null,
  systolic smallint not null check (systolic between 60 and 260),
  diastolic smallint not null check (diastolic between 30 and 160),
  pulse smallint check (pulse is null or pulse between 25 and 220),
  arm text check (arm is null or arm in ('left', 'right')),
  position text check (position is null or position in ('seated', 'standing', 'lying')),
  -- the person's answer at the time; null = not asked
  on_bp_meds boolean,
  source text not null default 'manual' check (source in ('manual', 'health_connect')),
  hc_record_id text,
  hc_origin text,
  note text,
  created_at timestamptz not null default now(),
  check (systolic > diastolic),
  check ((source = 'health_connect') = (hc_record_id is not null)),
  unique (user_id, hc_record_id)
);

create index bp_readings_user_time_idx on public.bp_readings (user_id, occurred_at desc);

alter table public.bp_readings enable row level security;

create policy bp_readings_select_own on public.bp_readings for select to authenticated using (user_id = (select auth.uid()));
create policy bp_readings_insert_own on public.bp_readings for insert to authenticated with check (user_id = (select auth.uid()));
create policy bp_readings_update_own on public.bp_readings for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy bp_readings_delete_own on public.bp_readings for delete to authenticated using (user_id = (select auth.uid()));

comment on table public.bp_readings is 'Home blood pressure readings (Health Connect import or typed). occurred_at is a logged (wall-clock) time.';
