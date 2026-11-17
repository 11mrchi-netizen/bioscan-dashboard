-- Health concerns (docs/health-concerns/01-plan.md §2): longitudinal injuries, recurring pain, symptoms and illness.
--
-- Raw input (written by the app, own rows only):
--   health_concerns         a persistent issue in the person's own words; no diagnosis needed
--   health_observations     one check-in; append-only. symptom_free = true is an explicit zero, and no observation is
--                           "unobserved", never zero. An edit is a new row that supersedes the old one; a trigger
--                           refuses any other change (the session link may move when duplicate sessions are folded)
--   health_signals          the symptoms and regions inside one check-in
--   health_episode_marks    the person's corrections to the derived episode boundaries
--   intervention_concerns   links an intervention (a trial) to the concerns it is meant to help, with its hypothesis
--   intervention_adherence  one row per trial day: done, partly, not
-- Derived (service role, recomputable): analysis_results kinds health_concern_state, health_finding, health_trial_read;
-- evidence_ledger rows with source 'health' (both checks widened below).
-- Reference: health_vocab, seeded from supabase/functions/_shared/health-vocab/health-vocab.json (a Node test keeps the
-- seed equal to the JSON).
-- Also: the duplicate-session fold moves health_observations.session_id; a 21:00 health_checkin reminder; the legacy
-- injuries and illnesses are copied in (one concern per row; the owner's two right-knee contusions stay separate);
-- v_health_events gives the web dashboard and the console the legacy shape for both.
--
-- Rollback: drop view public.v_health_events; drop table public.intervention_adherence, public.intervention_concerns,
--           public.health_episode_marks, public.health_signals, public.health_observations, public.health_concerns,
--           public.health_vocab; drop function private.backfill_health_concerns(uuid);
--           drop function public.health_observations_guard(); drop function public.health_signals_guard();
--           drop function public.health_observations_supersede(); drop function public.health_touch();
--           delete from public.notification_rules where kind = 'health_checkin';
--           restore notification_rules_kind_check from 20261027_fuel_notification_kinds.sql, the evidence_ledger checks
--           from 20261016_evidence_ledger.sql and private.fold_exercise_session from 20261023_session_duplicates.sql.

-- ---------- vocabulary ----------

create table if not exists public.health_vocab (
  code text primary key,
  kind text not null check (kind in ('region', 'symptom')),
  label text not null,
  parent text,
  sided boolean not null default false,
  vocab_version text not null
);

alter table public.health_vocab enable row level security;
drop policy if exists health_vocab_read on public.health_vocab;
create policy health_vocab_read on public.health_vocab for select to authenticated using (true);

insert into public.health_vocab (code, kind, label, parent, sided, vocab_version) values
  ('head', 'region', 'Head', 'head_neck', false, '1.0.0'),
  ('neck', 'region', 'Neck', 'head_neck', false, '1.0.0'),
  ('upper_back', 'region', 'Upper back', 'trunk', false, '1.0.0'),
  ('lower_back', 'region', 'Lower back', 'trunk', false, '1.0.0'),
  ('chest', 'region', 'Chest', 'trunk', false, '1.0.0'),
  ('abdomen', 'region', 'Abdomen', 'trunk', false, '1.0.0'),
  ('shoulder', 'region', 'Shoulder', 'upper_limb', true, '1.0.0'),
  ('upper_arm', 'region', 'Upper arm', 'upper_limb', true, '1.0.0'),
  ('elbow', 'region', 'Elbow', 'upper_limb', true, '1.0.0'),
  ('forearm', 'region', 'Forearm', 'upper_limb', true, '1.0.0'),
  ('wrist', 'region', 'Wrist', 'upper_limb', true, '1.0.0'),
  ('hand', 'region', 'Hand / fingers', 'upper_limb', true, '1.0.0'),
  ('hip', 'region', 'Hip', 'lower_limb', true, '1.0.0'),
  ('glute', 'region', 'Glute', 'lower_limb', true, '1.0.0'),
  ('groin', 'region', 'Groin / inner thigh', 'lower_limb', true, '1.0.0'),
  ('quad', 'region', 'Front of thigh', 'lower_limb', true, '1.0.0'),
  ('hamstring', 'region', 'Back of thigh', 'lower_limb', true, '1.0.0'),
  ('lateral_thigh', 'region', 'Outer thigh', 'lower_limb', true, '1.0.0'),
  ('knee', 'region', 'Knee', 'lower_limb', true, '1.0.0'),
  ('shin', 'region', 'Shin', 'lower_limb', true, '1.0.0'),
  ('calf', 'region', 'Calf', 'lower_limb', true, '1.0.0'),
  ('achilles', 'region', 'Achilles', 'lower_limb', true, '1.0.0'),
  ('ankle', 'region', 'Ankle', 'lower_limb', true, '1.0.0'),
  ('heel', 'region', 'Heel', 'lower_limb', true, '1.0.0'),
  ('plantar_foot', 'region', 'Sole of foot', 'lower_limb', true, '1.0.0'),
  ('foot_top', 'region', 'Top of foot', 'lower_limb', true, '1.0.0'),
  ('toes', 'region', 'Toes', 'lower_limb', true, '1.0.0'),
  ('sym.pain', 'symptom', 'Pain', null, false, '1.0.0'),
  ('sym.ache', 'symptom', 'Ache / soreness', null, false, '1.0.0'),
  ('sym.stiffness', 'symptom', 'Stiffness', null, false, '1.0.0'),
  ('sym.swelling', 'symptom', 'Swelling', null, false, '1.0.0'),
  ('sym.bruising', 'symptom', 'Bruising', null, false, '1.0.0'),
  ('sym.instability', 'symptom', 'Instability', null, false, '1.0.0'),
  ('sym.numbness', 'symptom', 'Numbness / tingling', null, false, '1.0.0'),
  ('sym.cramp', 'symptom', 'Cramp', null, false, '1.0.0'),
  ('sym.fatigue', 'symptom', 'Fatigue', 'general', false, '1.0.0'),
  ('sym.fever', 'symptom', 'Fever / chills', 'general', false, '1.0.0'),
  ('sym.sore_throat', 'symptom', 'Sore throat', 'respiratory', false, '1.0.0'),
  ('sym.cough', 'symptom', 'Cough', 'respiratory', false, '1.0.0'),
  ('sym.congestion', 'symptom', 'Blocked / runny nose', 'respiratory', false, '1.0.0'),
  ('sym.headache', 'symptom', 'Headache', 'general', false, '1.0.0'),
  ('sym.gi_upset', 'symptom', 'Stomach / gut upset', 'digestive', false, '1.0.0'),
  ('sym.nausea', 'symptom', 'Nausea', 'digestive', false, '1.0.0'),
  ('sym.dizziness', 'symptom', 'Dizziness', 'general', false, '1.0.0'),
  ('sym.muscle_aches', 'symptom', 'Body aches', 'general', false, '1.0.0'),
  ('sym.low_mood', 'symptom', 'Low mood', 'general', false, '1.0.0'),
  ('sym.poor_sleep', 'symptom', 'Poor sleep', 'general', false, '1.0.0'),
  ('sym.other', 'symptom', 'Other', null, false, '1.0.0')
on conflict (code) do update set kind = excluded.kind, label = excluded.label, parent = excluded.parent,
  sided = excluded.sided, vocab_version = excluded.vocab_version;

create or replace function public.health_touch()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- ---------- concerns ----------

create table if not exists public.health_concerns (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  title text not null check (char_length(title) between 1 and 80),
  kind text not null check (kind in ('anatomical', 'systemic', 'mixed')),
  -- acute = a traumatic event (a contusion, a sprain): timeline and recovery, but no exposure-association findings
  onset_type text not null default 'unknown' check (onset_type in ('acute', 'gradual', 'unknown')),
  -- [{"code": "knee", "side": "right"}]
  regions jsonb not null default '[]'::jsonb check (jsonb_typeof(regions) = 'array'),
  symptoms text[] not null default '{}',
  -- systemic illness: its episode days are left out of HRV, resting heart rate and sleep comparisons
  excludes_from_baselines boolean not null default false,
  label_diagnosis text,
  diagnosed_by text,
  -- symptom-free days that end an episode; null = 7 (anatomical, mixed) or 3 (systemic)
  resolution_days smallint check (resolution_days is null or resolution_days between 1 and 60),
  archived_at timestamptz,
  legacy_source text check (legacy_source is null or legacy_source in ('injuries', 'illnesses')),
  legacy_id bigint,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, legacy_source, legacy_id)
);

create index if not exists health_concerns_user_idx on public.health_concerns (user_id);

drop trigger if exists health_concerns_touch on public.health_concerns;
create trigger health_concerns_touch before update on public.health_concerns
  for each row execute function public.health_touch();

alter table public.health_concerns enable row level security;
drop policy if exists health_concerns_select_own on public.health_concerns;
drop policy if exists health_concerns_insert_own on public.health_concerns;
drop policy if exists health_concerns_update_own on public.health_concerns;
drop policy if exists health_concerns_delete_own on public.health_concerns;
create policy health_concerns_select_own on public.health_concerns for select to authenticated using (user_id = (select auth.uid()));
create policy health_concerns_insert_own on public.health_concerns for insert to authenticated with check (user_id = (select auth.uid()));
create policy health_concerns_update_own on public.health_concerns for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy health_concerns_delete_own on public.health_concerns for delete to authenticated using (user_id = (select auth.uid()));

-- ---------- observations ----------

create table if not exists public.health_observations (
  -- generated on the phone, so a retried save is the same row
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  observed_at timestamptz not null,
  -- the local day the answers are about (decided by the app, as the evening check-in)
  local_date date not null,
  utc_offset_min smallint check (utc_offset_min is null or utc_offset_min between -840 and 840),
  concern_id uuid references public.health_concerns (id) on delete cascade,
  -- concern: about one concern; sweep: everything bothering the person now (concerns without a signal are symptom-free);
  -- session: prompted by a session, about one concern
  scope text not null check (scope in ('concern', 'sweep', 'session')),
  symptom_free boolean not null,
  recall text not null default 'today' check (recall in ('now', 'today', 'since_last')),
  recall_from date,
  function_impact smallint check (function_impact is null or function_impact between 0 and 3),
  change_vs_last text check (change_vs_last is null or change_vs_last in ('better', 'same', 'worse')),
  onset boolean not null default false,
  mechanism text,
  session_id bigint references public.exercise_sessions (id) on delete set null,
  timing text check (timing is null or timing in ('during_session', 'after_session', 'next_morning', 'at_rest', 'night')),
  -- description only: {"medication": "...", "saw_clinician": true}
  care jsonb,
  note text,
  source text not null default 'app' check (source in ('app', 'legacy_backfill', 'console')),
  supersedes uuid references public.health_observations (id),
  superseded_at timestamptz,
  -- a correction that withdraws the entry it supersedes (an entry is never deleted)
  retracted boolean not null default false,
  created_at timestamptz not null default now(),
  check ((scope = 'sweep') = (concern_id is null)),
  check ((recall = 'since_last') = (recall_from is not null)),
  check (recall_from is null or (recall_from < local_date and recall_from >= local_date - 7)),
  check (not retracted or supersedes is not null)
);

create index if not exists health_observations_user_date_idx on public.health_observations (user_id, local_date);
create index if not exists health_observations_concern_idx on public.health_observations (concern_id, local_date);
create index if not exists health_observations_session_idx on public.health_observations (session_id) where session_id is not null;

-- Only superseded_at (set once, by a correction) and session_id (moved when duplicate sessions are folded) may change.
create or replace function public.health_observations_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if (to_jsonb(new) - 'superseded_at' - 'session_id') is distinct from (to_jsonb(old) - 'superseded_at' - 'session_id') then
    raise exception 'health_observations are append-only: save a correction that supersedes this entry';
  end if;
  if old.superseded_at is not null and new.superseded_at is distinct from old.superseded_at then
    raise exception 'health_observations: superseded_at is set once';
  end if;
  if old.session_id is not null and new.session_id is null then
    raise exception 'health_observations: a session link is moved, not removed';
  end if;
  return new;
end;
$$;

drop trigger if exists health_observations_guard on public.health_observations;
create trigger health_observations_guard before update on public.health_observations
  for each row execute function public.health_observations_guard();

-- A correction marks the entry it replaces. Runs as the caller, so RLS keeps it to the caller's own rows.
create or replace function public.health_observations_supersede()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  n integer;
begin
  if new.supersedes is not null then
    update public.health_observations set superseded_at = now()
      where id = new.supersedes and user_id = new.user_id and superseded_at is null;
    get diagnostics n = row_count;
    if n = 0 then
      raise exception 'health_observations: % is not an open entry of this account', new.supersedes;
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists health_observations_supersede on public.health_observations;
create trigger health_observations_supersede after insert on public.health_observations
  for each row execute function public.health_observations_supersede();

alter table public.health_observations enable row level security;
drop policy if exists health_observations_select_own on public.health_observations;
drop policy if exists health_observations_insert_own on public.health_observations;
drop policy if exists health_observations_update_own on public.health_observations;
create policy health_observations_select_own on public.health_observations for select to authenticated using (user_id = (select auth.uid()));
-- a foreign key does not look through RLS, so the policy itself keeps references to the caller's own concerns
create policy health_observations_insert_own on public.health_observations for insert to authenticated with check (
  user_id = (select auth.uid())
  and (concern_id is null or exists (select 1 from public.health_concerns c where c.id = concern_id and c.user_id = (select auth.uid()))));
-- updates are limited by the guard trigger; there is no delete policy on purpose
create policy health_observations_update_own on public.health_observations for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- ---------- signals ----------

create table if not exists public.health_signals (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  observation_id uuid not null references public.health_observations (id) on delete cascade,
  -- assigned when the person taps an existing concern or region; may be assigned later, never changed otherwise
  concern_id uuid references public.health_concerns (id) on delete set null,
  symptom text not null references public.health_vocab (code),
  region text references public.health_vocab (code),
  side text check (side is null or side in ('left', 'right', 'both', 'midline')),
  -- 0-10; null = present but not rated (legacy entries)
  severity smallint check (severity is null or severity between 0 and 10),
  -- answers to the conditional follow-ups (health_vocab questions)
  details jsonb not null default '{}'::jsonb check (jsonb_typeof(details) = 'object'),
  temperature_c numeric(3, 1) check (temperature_c is null or temperature_c between 34 and 43),
  created_at timestamptz not null default now()
);

create index if not exists health_signals_observation_idx on public.health_signals (observation_id);
create index if not exists health_signals_concern_idx on public.health_signals (concern_id);

create or replace function public.health_signals_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if (to_jsonb(new) - 'concern_id') is distinct from (to_jsonb(old) - 'concern_id') then
    raise exception 'health_signals are append-only: save a correction of the check-in instead';
  end if;
  if old.concern_id is not null and new.concern_id is distinct from old.concern_id then
    raise exception 'health_signals: a signal is assigned to a concern once';
  end if;
  return new;
end;
$$;

drop trigger if exists health_signals_guard on public.health_signals;
create trigger health_signals_guard before update on public.health_signals
  for each row execute function public.health_signals_guard();

alter table public.health_signals enable row level security;
drop policy if exists health_signals_select_own on public.health_signals;
drop policy if exists health_signals_insert_own on public.health_signals;
drop policy if exists health_signals_update_own on public.health_signals;
create policy health_signals_select_own on public.health_signals for select to authenticated using (user_id = (select auth.uid()));
create policy health_signals_insert_own on public.health_signals for insert to authenticated with check (
  user_id = (select auth.uid())
  and exists (select 1 from public.health_observations o where o.id = observation_id and o.user_id = (select auth.uid()))
  and (concern_id is null or exists (select 1 from public.health_concerns c where c.id = concern_id and c.user_id = (select auth.uid()))));
create policy health_signals_update_own on public.health_signals for update to authenticated
  using (user_id = (select auth.uid())) with check (
    user_id = (select auth.uid())
    and (concern_id is null or exists (select 1 from public.health_concerns c where c.id = concern_id and c.user_id = (select auth.uid()))));

-- ---------- episode marks ----------

create table if not exists public.health_episode_marks (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  concern_id uuid not null references public.health_concerns (id) on delete cascade,
  -- start / end: an episode starts or ends on this day; merge_across: the gap around this day is one episode;
  -- split_at: a new episode starts on this day even without a symptom-free run before it
  kind text not null check (kind in ('start', 'end', 'merge_across', 'split_at')),
  date date not null,
  note text,
  source text not null default 'app' check (source in ('app', 'legacy_backfill', 'console')),
  retracted_at timestamptz,
  created_at timestamptz not null default now()
);

create index if not exists health_episode_marks_concern_idx on public.health_episode_marks (concern_id, date);

alter table public.health_episode_marks enable row level security;
drop policy if exists health_episode_marks_select_own on public.health_episode_marks;
drop policy if exists health_episode_marks_insert_own on public.health_episode_marks;
drop policy if exists health_episode_marks_update_own on public.health_episode_marks;
create policy health_episode_marks_select_own on public.health_episode_marks for select to authenticated using (user_id = (select auth.uid()));
create policy health_episode_marks_insert_own on public.health_episode_marks for insert to authenticated with check (
  user_id = (select auth.uid())
  and exists (select 1 from public.health_concerns c where c.id = concern_id and c.user_id = (select auth.uid())));
-- a mark is withdrawn (retracted_at), not deleted
create policy health_episode_marks_update_own on public.health_episode_marks for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- ---------- trials ----------

create table if not exists public.intervention_concerns (
  intervention_id uuid not null references public.interventions (id) on delete cascade,
  concern_id uuid not null references public.health_concerns (id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  hypothesis text not null check (char_length(hypothesis) between 3 and 400),
  outcome text not null check (outcome in ('flare_rate_per_exposure', 'mean_severity', 'symptom_free_share', 'impact_days')),
  exposure_unit text not null default 'days' check (exposure_unit in ('days', 'run_km', 'run_hours', 'sessions')),
  pre_days smallint not null default 28 check (pre_days between 14 and 180),
  registered_at timestamptz not null default now(),
  primary key (intervention_id, concern_id)
);

alter table public.intervention_concerns enable row level security;
drop policy if exists intervention_concerns_select_own on public.intervention_concerns;
drop policy if exists intervention_concerns_insert_own on public.intervention_concerns;
drop policy if exists intervention_concerns_delete_own on public.intervention_concerns;
create policy intervention_concerns_select_own on public.intervention_concerns for select to authenticated using (user_id = (select auth.uid()));
create policy intervention_concerns_insert_own on public.intervention_concerns for insert to authenticated with check (
  user_id = (select auth.uid())
  and exists (select 1 from public.health_concerns c where c.id = concern_id and c.user_id = (select auth.uid()))
  and exists (select 1 from public.interventions i where i.id = intervention_id and i.user_id = (select auth.uid())));
-- no update policy: the hypothesis and outcome are fixed at registration
create policy intervention_concerns_delete_own on public.intervention_concerns for delete to authenticated using (user_id = (select auth.uid()));

create table if not exists public.intervention_adherence (
  intervention_id uuid not null references public.interventions (id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  date date not null,
  adhered text not null check (adhered in ('yes', 'partial', 'no')),
  note text,
  updated_at timestamptz not null default now(),
  primary key (intervention_id, date)
);

drop trigger if exists intervention_adherence_touch on public.intervention_adherence;
create trigger intervention_adherence_touch before update on public.intervention_adherence
  for each row execute function public.health_touch();

alter table public.intervention_adherence enable row level security;
drop policy if exists intervention_adherence_select_own on public.intervention_adherence;
drop policy if exists intervention_adherence_insert_own on public.intervention_adherence;
drop policy if exists intervention_adherence_update_own on public.intervention_adherence;
drop policy if exists intervention_adherence_delete_own on public.intervention_adherence;
create policy intervention_adherence_select_own on public.intervention_adherence for select to authenticated using (user_id = (select auth.uid()));
create policy intervention_adherence_insert_own on public.intervention_adherence for insert to authenticated with check (
  user_id = (select auth.uid())
  and exists (select 1 from public.interventions i where i.id = intervention_id and i.user_id = (select auth.uid())));
create policy intervention_adherence_update_own on public.intervention_adherence for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy intervention_adherence_delete_own on public.intervention_adherence for delete to authenticated using (user_id = (select auth.uid()));

-- ---------- ledger and reminders ----------

alter table public.evidence_ledger drop constraint if exists evidence_ledger_source_check;
alter table public.evidence_ledger add constraint evidence_ledger_source_check
  check (source in ('catalogue', 'registered', 'health'));
alter table public.evidence_ledger drop constraint if exists evidence_ledger_verdict_check;
alter table public.evidence_ledger add constraint evidence_ledger_verdict_check
  check (verdict in ('clear', 'negligible', 'unclear', 'not_yet',
                     'insufficient', 'observed', 'possible', 'persistent', 'faded'));

alter table public.notification_rules drop constraint if exists notification_rules_kind_check;
alter table public.notification_rules add constraint notification_rules_kind_check
  check (kind = any (array['supplement_due', 'checkin_morning', 'checkin_evening', 'wellbeing_checkin', 'evening_log',
                           'evening_checkin', 'body_checkin', 'fuel_session', 'fuel_event', 'health_checkin']));

insert into public.notification_rules (user_id, kind, enabled, schedule, conditions, limits)
select u.user_id, 'health_checkin', true, '{"times": ["21:00"]}'::jsonb, '{"skip_if_logged": true}'::jsonb,
       '{"max_per_day": 1, "min_gap_min": 60, "snooze_min": 30}'::jsonb
from (select distinct user_id from public.notification_rules) u
where not exists (select 1 from public.notification_rules r where r.user_id = u.user_id and r.kind = 'health_checkin');

-- ---------- the duplicate-session fold also moves check-in links ----------

create or replace function private.fold_exercise_session(p_drop bigint, p_keep bigint, p_reason text) returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  d public.exercise_sessions;
  k public.exercise_sessions;
begin
  select * into d from public.exercise_sessions where id = p_drop;
  if not found then return false; end if;
  select * into k from public.exercise_sessions where id = p_keep;
  if not found or d.user_id is distinct from k.user_id or p_drop = p_keep then return false; end if;

  update public.exercise_sessions set
    rpe = coalesce(k.rpe, d.rpe),
    notes = case when coalesce(k.notes, '') = '' then d.notes else k.notes end,
    details = case
      when private.session_has_list(k.details) or not private.session_has_list(d.details) then k.details
      else coalesce(k.details, '{}'::jsonb) || jsonb_strip_nulls(jsonb_build_object(
        'exercises', d.details->'exercises',
        'drills', d.details->'drills',
        'exercises_source', d.details->'exercises_source',
        'zepp_fingerprint', d.details->'zepp_fingerprint'))
    end
  where id = p_keep;

  update public.zepp_workout_detail set exercise_session_id = p_keep where exercise_session_id = p_drop;
  update public.planned_routes set exercise_session_id = p_keep where exercise_session_id = p_drop;
  update public.fueling_intake_log set exercise_session_id = p_keep where exercise_session_id = p_drop;
  update public.calendar_strength_staging set session_id = p_keep where session_id = p_drop;
  update public.health_observations set session_id = p_keep where session_id = p_drop;
  if not exists (select 1 from public.planned_sessions where exercise_session_id = p_keep) then
    update public.planned_sessions set exercise_session_id = p_keep where exercise_session_id = p_drop;
  end if;

  if d.health_connect_record_id is not null then
    insert into public.exercise_session_tombstones (user_id, health_connect_record_id, merged_into, reason)
    values (d.user_id, d.health_connect_record_id, p_keep, p_reason)
    on conflict (user_id, health_connect_record_id) do nothing;
  end if;
  delete from public.exercise_sessions where id = p_drop;
  return true;
end
$$;
revoke all on function private.fold_exercise_session(bigint, bigint, text) from public, anon, authenticated;

-- ---------- legacy injuries and illnesses ----------

-- One concern per legacy row (owner decision: the two right-knee injuries were different contusions). Each gets an
-- onset check-in on its start date, a start mark on that date (the old log said it began; a rating of 1 of 10 alone is
-- below the symptomatic line, as both knee rows were) and, when resolved, an end mark on its end date (the old log said
-- it was over; a single symptom-free check-in could not end an episode by the 7-day rule). Re-runnable: a row already
-- copied is skipped.
create or replace function private.backfill_health_concerns(p_user uuid default null) returns integer
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  r record;
  cid uuid;
  oid uuid;
  reg text;
  sd text;
  n integer := 0;
begin
  for r in select * from public.injuries where p_user is null or user_id = p_user loop
    if exists (select 1 from public.health_concerns where user_id = r.user_id and legacy_source = 'injuries' and legacy_id = r.id) then
      continue;
    end if;
    -- the longest region label or code the free text names ("Knee (right)" -> knee)
    select v.code into reg from public.health_vocab v
      where v.kind = 'region'
        and (lower(r.part) like '%' || lower(v.label) || '%' or lower(r.part) like '%' || replace(v.code, '_', ' ') || '%')
      order by char_length(v.label) desc limit 1;
    sd := case when lower(r.part) ~ '\mright\M' or lower(r.part) ~ '\(r\)' then 'right'
               when lower(r.part) ~ '\mleft\M' or lower(r.part) ~ '\(l\)' then 'left' end;
    insert into public.health_concerns (user_id, title, kind, onset_type, regions, symptoms, legacy_source, legacy_id, created_at)
    values (r.user_id, left(r.part || ' · ' || r.type || ' · ' || to_char(r.start_date, 'DD Mon YYYY'), 80), 'anatomical', 'acute',
            case when reg is null then '[]'::jsonb else jsonb_build_array(jsonb_strip_nulls(jsonb_build_object('code', reg, 'side', sd))) end,
            array['sym.pain'], 'injuries', r.id, r.created_at)
    returning id into cid;
    insert into public.health_observations (user_id, observed_at, local_date, concern_id, scope, symptom_free, onset, mechanism, note, source, created_at)
    values (r.user_id, r.start_date + time '12:00', r.start_date, cid, 'concern', false, true, r.type, r.notes, 'legacy_backfill', r.created_at)
    returning id into oid;
    insert into public.health_signals (user_id, observation_id, concern_id, symptom, region, side, severity)
    values (r.user_id, oid, cid, 'sym.pain', reg, sd, nullif(r.severity, 0));
    insert into public.health_episode_marks (user_id, concern_id, kind, date, note, source)
    values (r.user_id, cid, 'start', r.start_date, 'Started in the old injury log', 'legacy_backfill');
    if r.status = 'resolved' and r.end_date is not null then
      insert into public.health_episode_marks (user_id, concern_id, kind, date, note, source)
      values (r.user_id, cid, 'end', r.end_date, 'Resolved in the old injury log', 'legacy_backfill');
    end if;
    n := n + 1;
  end loop;

  for r in select * from public.illnesses where p_user is null or user_id = p_user loop
    if exists (select 1 from public.health_concerns where user_id = r.user_id and legacy_source = 'illnesses' and legacy_id = r.id) then
      continue;
    end if;
    insert into public.health_concerns (user_id, title, kind, onset_type, symptoms, excludes_from_baselines, label_diagnosis,
                                        legacy_source, legacy_id, created_at)
    values (r.user_id, left(r.name || ' · ' || to_char(r.start_date, 'DD Mon YYYY'), 80), 'systemic', 'unknown', array['sym.other'], true,
            r.diagnosis, 'illnesses', r.id, r.created_at)
    returning id into cid;
    insert into public.health_observations (user_id, observed_at, local_date, concern_id, scope, symptom_free, onset, care, note, source, created_at)
    values (r.user_id, r.start_date + time '12:00', r.start_date, cid, 'concern', false, true,
            jsonb_strip_nulls(jsonb_build_object('saw_clinician', r.doctor_seen, 'medications', nullif(r.medications, '[]'::jsonb),
                                                 'medication', r.medication)),
            nullif(concat_ws(' · ', r.symptoms, r.notes), ''), 'legacy_backfill', r.created_at)
    returning id into oid;
    insert into public.health_signals (user_id, observation_id, concern_id, symptom, severity)
    values (r.user_id, oid, cid, 'sym.other', nullif(r.severity, 0));
    insert into public.health_episode_marks (user_id, concern_id, kind, date, note, source)
    values (r.user_id, cid, 'start', r.start_date, 'Started in the old illness log', 'legacy_backfill');
    if r.status = 'resolved' and r.end_date is not null then
      insert into public.health_episode_marks (user_id, concern_id, kind, date, note, source)
      values (r.user_id, cid, 'end', r.end_date, 'Resolved in the old illness log', 'legacy_backfill');
    end if;
    n := n + 1;
  end loop;
  return n;
end
$$;
revoke all on function private.backfill_health_concerns(uuid) from public, anon, authenticated;

select private.backfill_health_concerns();

-- ---------- the legacy shape, for the web dashboard and the console ----------

-- Legacy rows as they are, plus concerns created in the app (not the copies of legacy rows), with their state from the
-- newest published health_concern_state: open (active, improving, stable, worsening) reads 'active', an episode with no
-- recent check-in 'monitoring', otherwise 'resolved'.
create or replace view public.v_health_events with (security_invoker = true) as
select 'injury'::text as kind, i.id::text as id, i.user_id, i.part || ' — ' || i.type as title, i.notes as detail,
       i.status, i.start_date, i.end_date
from public.injuries i
union all
select 'illness', l.id::text, l.user_id, l.name, l.symptoms, l.status, l.start_date, l.end_date
from public.illnesses l
union all
select case when c.kind = 'systemic' then 'illness' else 'injury' end, c.id::text, c.user_id, c.title, null,
       case
         when s.payload->>'state' in ('active', 'improving', 'stable', 'worsening') then 'active'
         when s.payload->>'state' = 'unobserved' and s.payload->'currentEpisode' is not null
              and s.payload->'currentEpisode' <> 'null'::jsonb then 'monitoring'
         else 'resolved'
       end,
       coalesce((s.payload->'currentEpisode'->>'start')::date, (s.payload->'lastEpisode'->>'start')::date, c.created_at::date),
       case when s.payload->'currentEpisode' is null or s.payload->'currentEpisode' = 'null'::jsonb
            then (s.payload->'lastEpisode'->>'end')::date end
from public.health_concerns c
left join lateral (
  select a.payload from public.analysis_results a
  where a.user_id = c.user_id and a.kind = 'health_concern_state' and a.key = c.id::text
  order by a.as_of desc, a.calculated_at desc limit 1
) s on true
where c.legacy_source is null and c.archived_at is null;
