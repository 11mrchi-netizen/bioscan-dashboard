-- Lab › TESTS › BREATHWORK: one row per guided breathing session, saved when it finishes or is ended after at least
-- 30 s of breathing. Own rows only.
-- Rollback: drop table public.breathwork_sessions;

create table public.breathwork_sessions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- when the breathing started (after the lead-in): local wall-clock time labelled +00, same convention as stool_log / notes
  started_at timestamptz not null,
  -- "preset:box" for a preset, "custom:<n>" for the phone's own pattern (custom patterns live on the phone only)
  pattern_key text not null,
  pattern_name text not null,
  -- the pattern as it was run: [{"kind": "INHALE" | "HOLD_IN" | "EXHALE" | "HOLD_OUT", "seconds": 4}, ...]
  steps jsonb not null,
  rounds_planned integer not null check (rounds_planned between 1 and 1000),
  rounds_done integer not null check (rounds_done between 0 and 1000),
  -- seconds of breathing, pauses and the lead-in excluded
  duration_s integer not null check (duration_s between 0 and 86400),
  -- false when it was ended before its last round
  completed boolean not null,
  created_at timestamptz not null default now()
);

create index breathwork_sessions_user_time_idx on public.breathwork_sessions (user_id, started_at desc);

alter table public.breathwork_sessions enable row level security;

create policy breathwork_sessions_select_own on public.breathwork_sessions for select to authenticated using (user_id = (select auth.uid()));
create policy breathwork_sessions_insert_own on public.breathwork_sessions for insert to authenticated with check (user_id = (select auth.uid()));
create policy breathwork_sessions_update_own on public.breathwork_sessions for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy breathwork_sessions_delete_own on public.breathwork_sessions for delete to authenticated using (user_id = (select auth.uid()));
