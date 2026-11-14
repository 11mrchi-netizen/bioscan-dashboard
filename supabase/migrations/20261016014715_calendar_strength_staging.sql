-- Staging for the calendar -> exercise_sessions.details back-fill (29 Jun - 8 Oct 2026). Temporary: drop once applied.
-- Nothing here changes exercise_sessions; `approved` marks the rows to apply, `applied_at` is set when they are.
create table public.calendar_strength_staging (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  session_id bigint not null references public.exercise_sessions (id) on delete cascade,
  calendar text not null,
  event_date date not null,
  event_title text not null,
  event_edited boolean not null,
  -- fill_empty: session had no exercises; update_sets: stored list kept (warm-ups, codes, heart rate), reps and loads
  -- from the calendar; replace_existing: stored list disregarded; no_change: stored list already agrees; hold: not written
  change_kind text not null check (change_kind in ('fill_empty', 'update_sets', 'replace_existing', 'no_change', 'hold')),
  exercises_compact jsonb not null,
  percent_1rm numeric,
  current_details jsonb,
  proposed_details jsonb,
  review_flags text[] not null default '{}',
  info_notes text[] not null default '{}',
  raw_text text not null,
  approved boolean not null default false,
  applied_at timestamptz,
  created_at timestamptz not null default now(),
  unique (session_id)
);

alter table public.calendar_strength_staging enable row level security;
create policy calendar_strength_staging_select_own on public.calendar_strength_staging for select to authenticated
  using (user_id = (select auth.uid()));
create policy calendar_strength_staging_update_own on public.calendar_strength_staging for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- One line per exercise: "Name: 5x42.5, 5x42.5 ..." (reps x kg per set) for the stored and the proposed lists.
create or replace function public.calendar_staging_lines(d jsonb)
returns text
language sql
immutable
set search_path = ''
as $$
  select coalesce(string_agg(e ->> 'name' || ': ' || (
           select string_agg((s ->> 'reps') || 'x' || (s ->> 'weight_kg'), ', ' order by o)
           from jsonb_array_elements(e -> 'sets') with ordinality as t(s, o)), E'\n' order by p), '')
  from jsonb_array_elements(coalesce(d -> 'exercises', '[]'::jsonb)) with ordinality as x(e, p)
$$;

create view public.calendar_strength_staging_review with (security_invoker = true) as
select id, session_id, event_date, event_title, event_edited, change_kind,
       cardinality(review_flags) as review_flags_n, review_flags, info_notes,
       public.calendar_staging_lines(current_details) as stored_now,
       public.calendar_staging_lines(proposed_details) as proposed,
       approved, applied_at
from public.calendar_strength_staging
order by event_date;
