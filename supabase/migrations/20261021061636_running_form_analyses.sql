-- Running form analyses (docs/running-form/README.md, phase F4): the report of one clip read on the phone, so form can be
-- compared from one clip to the next. The video and the per-frame points stay on the phone; only the setup, the clip's
-- quality, the metric summaries (median, spread, steps) and the per-step values are stored, a few kilobytes a row.
--
-- One row per analysis, optionally pointing at the run the clip was filmed during (its watch values are read from that
-- session's Zepp workout, not copied here). Own rows only. Safe to run again.
--
-- Rollback: drop table public.running_form_analyses;

create table if not exists public.running_form_analyses (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  recorded_on date not null,
  exercise_session_id bigint references public.exercise_sessions (id) on delete set null,
  view text not null check (view in ('side', 'rear')),
  surface text not null check (surface in ('treadmill', 'overground')),
  side_facing_camera text check (side_facing_camera is null or side_facing_camera in ('left', 'right')),
  treadmill_speed_kmh numeric check (treadmill_speed_kmh is null or treadmill_speed_kmh between 2 and 30),
  fps numeric not null check (fps > 0 and fps <= 1000),
  steps_measured integer not null check (steps_measured between 0 and 1000),
  quality text not null check (quality in ('good', 'usable', 'poor')),
  quality_issues text[] not null default '{}',
  -- {"cadence": {"median": 172.1, "spread": 1.2, "n": 9}, "shin_angle": {...}, ...} plus "foot_strike": "REARFOOT".
  metrics jsonb not null check (jsonb_typeof(metrics) = 'object'),
  -- One object per step of the measured foot: landing time and that step's values.
  steps jsonb not null default '[]'::jsonb check (jsonb_typeof(steps) = 'array' and jsonb_array_length(steps) <= 500),
  analyser_version text not null check (length(analyser_version) <= 20),
  notes text check (notes is null or length(notes) <= 1000),
  created_at timestamptz not null default now()
);

create index if not exists running_form_analyses_user_date_idx on public.running_form_analyses (user_id, recorded_on desc);

alter table public.running_form_analyses enable row level security;

drop policy if exists running_form_analyses_select_own on public.running_form_analyses;
create policy running_form_analyses_select_own on public.running_form_analyses for select to authenticated
  using (user_id = (select auth.uid()));
drop policy if exists running_form_analyses_insert_own on public.running_form_analyses;
create policy running_form_analyses_insert_own on public.running_form_analyses for insert to authenticated
  with check (user_id = (select auth.uid()));
drop policy if exists running_form_analyses_update_own on public.running_form_analyses;
create policy running_form_analyses_update_own on public.running_form_analyses for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists running_form_analyses_delete_own on public.running_form_analyses;
create policy running_form_analyses_delete_own on public.running_form_analyses for delete to authenticated
  using (user_id = (select auth.uid()));
