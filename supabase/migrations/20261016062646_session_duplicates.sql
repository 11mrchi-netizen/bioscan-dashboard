-- One session per workout (docs/zepp-integration/04-data-model-and-provenance.md, "Duplicates inside Health Connect and
-- from Notion").
--
-- 1. Health Connect duplicates. The source app wrote many workouts to Health Connect twice (two records, same or nearly
--    the same times), so exercise_sessions holds two Health Connect rows for one workout; the run and log lists hid one,
--    but load, the intensity card and the analysis frame counted both. Same-type Health Connect sessions overlapping by
--    at least half of the longer of the two are one workout; per connected group one row is kept (the one linked to a
--    Zepp workout, else one carrying exercises, RPE or notes, else the longer, else the oldest id).
-- 2. Notion strength logs. The Notion archive import created a session at a placeholder time (12:00) for each logged
--    strength workout; on days with a Zepp-linked strength session of a similar length (within half of the longer) that
--    is the same workout: Zepp's session is kept and takes the Notion exercise list. Notion conditioning ("other") rows
--    are not folded: Zepp's "other" sessions on those days are much shorter, different activities.
--
-- Folding (private.fold_exercise_session): the kept row takes the dropped row's RPE and notes only where it has none and
-- its exercise list (exercises, or Notion's conditioning drills) only where it has none; its measurements and times stay.
-- Every reference to the dropped row is moved over, then it is deleted. A dropped Health Connect row's record id is kept
-- in exercise_session_tombstones, and a trigger skips any later insert of it, so a Health Connect re-sync of that period
-- (an upsert by record id) cannot bring the copy back.
--
-- The plan (private.exercise_session_duplicate_plan) and the fold stay, so the same check can be run again; running this
-- migration again folds only what has appeared since.
--
-- Live, it went in two steps because the migration endpoint timed out: the table, trigger and plan on 16 Oct 2026 with
-- the SQL tool; private.fold_exercise_session and the fold (everything below the plan) from the SQL editor, by the owner.
--
-- Rollback (the folds themselves are not undone): drop trigger exercise_sessions_skip_tombstoned on public.exercise_sessions;
--   drop function public.skip_tombstoned_session(); drop function private.exercise_session_duplicate_plan();
--   drop function private.fold_exercise_session(bigint, bigint, text); drop function private.session_has_list(jsonb);
--   drop table public.exercise_session_tombstones. Removing a tombstone and re-syncing that period from Health Connect
--   restores that copy.

create table if not exists public.exercise_session_tombstones (
  user_id uuid not null references auth.users (id) on delete cascade,
  health_connect_record_id text not null,
  merged_into bigint,
  reason text not null,
  created_at timestamptz not null default now(),
  primary key (user_id, health_connect_record_id)
);
alter table public.exercise_session_tombstones enable row level security;
drop policy if exists "own tombstones readable" on public.exercise_session_tombstones;
create policy "own tombstones readable" on public.exercise_session_tombstones
  for select using (user_id = (select auth.uid()));

create or replace function public.skip_tombstoned_session() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if new.health_connect_record_id is not null and exists (
    select 1 from public.exercise_session_tombstones t
    where t.user_id = new.user_id and t.health_connect_record_id = new.health_connect_record_id
  ) then
    return null;
  end if;
  return new;
end
$$;
revoke all on function public.skip_tombstoned_session() from public, anon, authenticated;

create or replace trigger exercise_sessions_skip_tombstoned
  before insert on public.exercise_sessions
  for each row execute function public.skip_tombstoned_session();

-- Whether a details object carries a list a person logged: strength exercises or conditioning drills.
create or replace function private.session_has_list(d jsonb) returns boolean
language sql immutable as $$
  select coalesce(
    (jsonb_typeof(d->'exercises') = 'array' and jsonb_array_length(d->'exercises') > 0)
    or (jsonb_typeof(d->'drills') = 'array' and jsonb_array_length(d->'drills') > 0),
    false)
$$;

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

create or replace function private.exercise_session_duplicate_plan()
returns table (drop_id bigint, keep_id bigint, reason text)
language sql stable security definer set search_path = public, pg_temp as $$
  with recursive
  spans as (
    select s.id, s.user_id, s.type, s.source, s.rpe, s.notes, s.details,
      s.start_time as st,
      coalesce(s.end_time, s.start_time + make_interval(secs => coalesce(s.duration_min, 0) * 60)) as en,
      exists (select 1 from public.zepp_workout_detail z where z.exercise_session_id = s.id) as zepp_linked
    from public.exercise_sessions s
  ),
  hc as (select * from spans where source = 'health_connect' and en > st),
  edge as (
    select a.id as a, b.id as b
    from hc a join hc b
      on a.id <> b.id and a.user_id = b.user_id and a.type = b.type and b.st < a.en and b.en > a.st
     and extract(epoch from least(a.en, b.en) - greatest(a.st, b.st))
         >= 0.5 * greatest(extract(epoch from a.en - a.st), extract(epoch from b.en - b.st))
  ),
  reach (node, other) as (
    select a, b from edge
    union
    select r.node, e.b from reach r join edge e on e.a = r.other
  ),
  grp as (select node, least(node, min(other)) as g from reach group by node),
  ranked as (
    select grp.g, hc.id, row_number() over (
      partition by grp.g
      order by hc.zepp_linked desc,
        (hc.rpe is not null or coalesce(hc.notes, '') <> '' or private.session_has_list(hc.details)) desc,
        (hc.en - hc.st) desc, hc.id
    ) as rn
    from grp join hc on hc.id = grp.node
  ),
  hc_plan as (
    select r.id as drop_id, k.id as keep_id, 'hc_duplicate'::text as reason
    from ranked r join ranked k on k.g = r.g and k.rn = 1
    where r.rn > 1
  ),
  zepp_strength as (
    select * from spans
    where type = 'strength' and zepp_linked and id not in (select drop_id from hc_plan)
  ),
  notion_plan as (
    select distinct on (n.id) n.id as drop_id, z.id as keep_id, 'notion_into_zepp'::text as reason
    from spans n join zepp_strength z
      on z.user_id = n.user_id and z.st::date = n.st::date
     and abs(extract(epoch from (z.en - z.st) - (n.en - n.st)))
         <= 0.5 * greatest(extract(epoch from z.en - z.st), extract(epoch from n.en - n.st))
    where n.source = 'notion' and n.type = 'strength'
    order by n.id, abs(extract(epoch from (z.en - z.st) - (n.en - n.st))), z.id
  )
  select * from hc_plan
  union all
  select * from notion_plan
$$;
revoke all on function private.exercise_session_duplicate_plan() from public, anon, authenticated;

-- Fold everything the plan lists, the plan taken once before any fold.
do $$
declare
  r record;
  folded int := 0;
begin
  create temp table _session_fold_plan on commit drop as select * from private.exercise_session_duplicate_plan();
  for r in select * from _session_fold_plan order by reason, drop_id loop
    if private.fold_exercise_session(r.drop_id, r.keep_id, r.reason) then folded := folded + 1; end if;
  end loop;
  raise notice 'session duplicates folded: %', folded;
end
$$;
