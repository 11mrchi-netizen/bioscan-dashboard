-- Analysis review phase E (docs/analysis-review/README.md): from a hint to an answer.
--
-- 1. evidence_ledger: every nightly verdict of every question, kept. analysis_results keeps only the newest row per
--    question; the ledger keeps the history, so a change of verdict is visible with its date, engine version and inputs
--    hash. Append-only: users can read their own rows and nothing else; the analysis job inserts with the service role;
--    a trigger refuses updates from anyone (rows still go with the account, through the cascade).
-- 2. registered_questions: a hint (what came before off mornings) registered as a question of its own. It is then
--    tested only on days after its registration date, with the same nightly sequential boundary as the catalogue, and
--    reads "confirmed on new days" when its interval clears the no-effect zone. The registered fields cannot change
--    afterwards (a trigger), registration cannot be backdated, and a question is withdrawn, never deleted.
--
-- Rollback: drop table public.evidence_ledger; drop table public.registered_questions;
--           drop function public.evidence_ledger_no_update(); drop function public.registered_questions_guard();

create table public.evidence_ledger (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- a catalogue question id (alcohol_hrv) or a registered question (reg_<id>)
  question_id text not null,
  source text not null check (source in ('catalogue', 'registered')),
  as_of date not null,
  engine_version text not null,
  catalogue_version text,
  inputs_hash text,
  verdict text not null check (verdict in ('clear', 'negligible', 'unclear', 'not_yet')),
  estimate double precision,
  lo double precision,
  hi double precision,
  unit text,
  -- the interval the verdict used (wider while information is short) and the information fraction behind it
  verdict_lo double precision,
  verdict_hi double precision,
  information double precision,
  no_effect double precision,
  n integer,
  n_on integer,
  created_at timestamptz not null default now(),
  unique (user_id, question_id, as_of, engine_version)
);

create index evidence_ledger_user_question_idx on public.evidence_ledger (user_id, question_id, as_of desc);

alter table public.evidence_ledger enable row level security;

create policy evidence_ledger_select_own on public.evidence_ledger for select to authenticated using (user_id = (select auth.uid()));
-- No insert, update or delete policies on purpose.

create or replace function public.evidence_ledger_no_update()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'evidence_ledger is append-only';
end;
$$;

create trigger evidence_ledger_no_update before update on public.evidence_ledger
  for each row execute function public.evidence_ledger_no_update();

comment on table public.evidence_ledger is 'Analysis review phase E: every nightly verdict of every question, append-only.';

create table public.registered_questions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  exposure text not null check (exposure in ('alcohol_any', 'caffeine_pm_any', 'late_meal', 'stress_high', 'evening_training', 'training_day')),
  outcome text not null check (outcome in ('hrv', 'rhr', 'sleep_hours')),
  lag smallint not null default 1 check (lag between 0 and 3),
  -- the no-effect zone, in the effect's unit (% for HRV, bpm, minutes of sleep)
  no_effect double precision not null check (no_effect > 0),
  -- the local date it was registered; only days after it count
  registered_on date not null,
  source text not null default 'case_crossover',
  note text,
  withdrawn_at timestamptz,
  created_at timestamptz not null default now()
);

-- One live registration per pair; a withdrawn one can be registered again (with a new date).
create unique index registered_questions_live_idx on public.registered_questions (user_id, exposure, outcome, lag) where withdrawn_at is null;

create or replace function public.registered_questions_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    -- A day of slack for time zones; anything older would let past days pass as new ones.
    if new.registered_on < (now() at time zone 'utc')::date - 1 then
      raise exception 'registered_on cannot be in the past';
    end if;
    return new;
  end if;
  if new.exposure is distinct from old.exposure or new.outcome is distinct from old.outcome or new.lag is distinct from old.lag
     or new.no_effect is distinct from old.no_effect or new.registered_on is distinct from old.registered_on
     or new.user_id is distinct from old.user_id then
    raise exception 'a registered question cannot change; withdraw it and register a new one';
  end if;
  return new;
end;
$$;

create trigger registered_questions_guard before insert or update on public.registered_questions
  for each row execute function public.registered_questions_guard();

alter table public.registered_questions enable row level security;

create policy registered_questions_select_own on public.registered_questions for select to authenticated using (user_id = (select auth.uid()));
create policy registered_questions_insert_own on public.registered_questions for insert to authenticated with check (user_id = (select auth.uid()));
-- Only withdrawn_at (and the note) may change, which the guard enforces; there is no delete policy.
create policy registered_questions_update_own on public.registered_questions for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

comment on table public.registered_questions is 'Analysis review phase E: hints registered as questions, tested only on days after registration.';
