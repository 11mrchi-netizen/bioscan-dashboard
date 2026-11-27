-- Lab › WELLNESS: one row per guided sexual-wellness session (stop-start training first; pelvic floor and the later stages
-- reuse the same table). Saved when a session finishes or is ended after at least 3 minutes. Own rows only.
-- Counts and ratings only: no free text, so nothing here can leak a name or a note.
-- Rollback: drop table public.wellness_sessions;

create table public.wellness_sessions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- when the session started: local wall-clock time labelled +00, same convention as breathwork_sessions / stool_log
  started_at timestamptz not null,
  -- the course and stage: "stopstart" / "baseline", "stopstart" / "stopstart", later "pelvic_floor", ...
  programme_key text not null,
  stage_key text not null,
  -- the script as it was when run ("stopstart-v1"), so a later rewrite does not change what an old row meant
  script_version text not null,
  duration_s integer not null check (duration_s between 0 and 86400),
  -- false when it was ended before its last planned cycle
  completed boolean not null,
  cycles_planned smallint not null check (cycles_planned between 0 and 50),
  -- stop-start cycles finished: arousal raised, stopped, allowed to settle, resumed (or ended)
  cycles_done smallint not null check (cycles_done between 0 and 50),
  -- the highest arousal rating (0-10) reached when a stop was called
  peak_arousal smallint check (peak_arousal between 0 and 10),
  -- how many times a stop was called after the target rating was passed or reached (a "late" stop), out of cycles_done
  late_stops smallint not null default 0 check (late_stops between 0 and 50),
  -- how the session went, 1-5, asked at the end
  felt smallint check (felt between 1 and 5),
  created_at timestamptz not null default now()
);

create index wellness_sessions_user_time_idx on public.wellness_sessions (user_id, started_at desc);

alter table public.wellness_sessions enable row level security;

create policy wellness_sessions_select_own on public.wellness_sessions for select to authenticated using (user_id = (select auth.uid()));
create policy wellness_sessions_insert_own on public.wellness_sessions for insert to authenticated with check (user_id = (select auth.uid()));
create policy wellness_sessions_update_own on public.wellness_sessions for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy wellness_sessions_delete_own on public.wellness_sessions for delete to authenticated using (user_id = (select auth.uid()));
