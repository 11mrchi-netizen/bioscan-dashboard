-- Personal records (docs/performance/records.md). Two parts:
--   run_best_efforts   each run's fastest stretch of 1K, 5K, 10K, half, marathon, 50K, 60K, 75K and 100K, read once
--                      from its per-second distance (zepp_workout_detail.decoded.distanceKm) and stored: a trigger reads
--                      a run when its detail is decoded or re-decoded, the backfill below reads every run once;
--   v_personal_records every time a record was set: a run that beat the best of every run before it (the first run of
--                      a kind starts the line), for those distances and the longest run, longest trail run and biggest
--                      climb. Worked out from the stored rows on every read, so an edited or deleted run is never stale.
-- Elapsed time, as race times are (a stop inside the stretch counts). Treadmill runs (no track) give no efforts.
-- A stretch faster than 2:30 /km is a GPS jump, not a run, and is left out.
--
-- Rollback: drop view public.v_personal_records; drop trigger zepp_workout_detail_best_efforts on public.zepp_workout_detail;
--           drop function public.zepp_workout_best_efforts_trigger(); drop function public.compute_run_best_efforts(bigint);
--           drop table public.run_best_efforts;

create table if not exists public.run_best_efforts (
  user_id uuid not null references auth.users (id) on delete cascade,
  zepp_workout_detail_id bigint not null references public.zepp_workout_detail (id) on delete cascade,
  exercise_session_id bigint references public.exercise_sessions (id) on delete set null,
  distance_m integer not null check (distance_m > 0),
  elapsed_sec real not null check (elapsed_sec > 0),
  start_offset_sec real not null,
  computed_at timestamptz not null default now(),
  primary key (zepp_workout_detail_id, distance_m)
);
create index if not exists run_best_efforts_user on public.run_best_efforts (user_id, distance_m);

alter table public.run_best_efforts enable row level security;
drop policy if exists run_best_efforts_select_own on public.run_best_efforts;
create policy run_best_efforts_select_own on public.run_best_efforts for select to authenticated using (user_id = (select auth.uid()));
revoke all on public.run_best_efforts from anon;

-- The record distances, metres.
create or replace function public.record_distances_m() returns integer[] language sql immutable as
$$ select array[1000, 5000, 10000, 21097, 42195, 50000, 60000, 75000, 100000] $$;

-- One run's best efforts: for each distance, the shortest elapsed time between two moments the run covered it in
-- (the start interpolated between samples), by two pointers over the series.
create or replace function public.compute_run_best_efforts(detail_id bigint) returns void
language plpgsql security definer set search_path = public as $$
declare
  r record;
  t float8[];
  d float8[];
  n int;
  dist int;
  target float8;
  i int;
  j int;
  best float8;
  best_start float8;
  need float8;
  ts float8;
  el float8;
begin
  select id, user_id, exercise_session_id, sport_type, decoded into r from zepp_workout_detail where id = detail_id;
  delete from run_best_efforts where zepp_workout_detail_id = detail_id;
  if r.id is null or r.sport_type not in ('1', '7') or r.decoded is null then return; end if;
  select array_agg((e->>'offsetSeconds')::float8 order by (e->>'offsetSeconds')::float8),
         array_agg((e->>'value')::float8 * 1000 order by (e->>'offsetSeconds')::float8)
    into t, d
    from jsonb_array_elements(coalesce(r.decoded->'distanceKm', '[]'::jsonb)) e
   where e->>'value' is not null and e->>'offsetSeconds' is not null;
  n := coalesce(array_length(t, 1), 0);
  if n < 2 then return; end if;
  foreach dist in array record_distances_m() loop
    target := dist;
    exit when d[n] - d[1] < target;
    best := null;
    i := 1;
    for j in 2..n loop
      -- the latest start i that still leaves the full distance to j
      while i + 1 < j and d[j] - d[i + 1] >= target loop i := i + 1; end loop;
      if d[j] - d[i] >= target then
        need := d[j] - target; -- the distance mark the stretch starts at, between samples i and i+1
        if d[i + 1] > d[i] and need > d[i] then
          ts := t[i] + (t[i + 1] - t[i]) * least(1.0, (need - d[i]) / (d[i + 1] - d[i]));
        else
          ts := t[i];
        end if;
        el := t[j] - ts;
        if el >= target / 1000.0 * 150 and (best is null or el < best) then best := el; best_start := ts; end if;
      end if;
    end loop;
    if best is not null then
      insert into run_best_efforts (user_id, zepp_workout_detail_id, exercise_session_id, distance_m, elapsed_sec, start_offset_sec)
      values (r.user_id, r.id, r.exercise_session_id, dist, best, best_start);
    end if;
  end loop;
end $$;

create or replace function public.zepp_workout_best_efforts_trigger() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform compute_run_best_efforts(new.id);
  return null;
end $$;

drop trigger if exists zepp_workout_detail_best_efforts on public.zepp_workout_detail;
create trigger zepp_workout_detail_best_efforts after insert or update of decoded, exercise_session_id, sport_type on public.zepp_workout_detail
  for each row execute function public.zepp_workout_best_efforts_trigger();

-- Every record set, oldest first per record. value: seconds for a distance (lower is better), km for the longest runs,
-- metres for the climb (higher is better). previous_value / previous_on: the record it beat (null for the first).
create or replace view public.v_personal_records with (security_invoker = true) as
with candidates as (
  select e.user_id,
         'fastest_' || e.distance_m as record_key,
         e.elapsed_sec::float8 as value,
         false as higher_better,
         (s.start_time at time zone 'UTC')::date as achieved_on,
         s.start_time,
         e.exercise_session_id
    from run_best_efforts e
    join exercise_sessions s on s.id = e.exercise_session_id
  union all
  select s.user_id, 'longest_run', s.distance_km::float8, true, (s.start_time at time zone 'UTC')::date, s.start_time, s.id
    from exercise_sessions s
   where s.type = 'run' and s.distance_km > 0 and s.duration_min > 0
     and s.duration_min / s.distance_km between 2.5 and 20
  union all
  select s.user_id, 'longest_trail_run', s.distance_km::float8, true, (s.start_time at time zone 'UTC')::date, s.start_time, s.id
    from exercise_sessions s
   where s.type = 'run' and s.distance_km > 0 and s.duration_min > 0
     and s.duration_min / s.distance_km between 2.5 and 30
     and (s.details->>'routeType' = 'trail'
          or exists (select 1 from zepp_workout_detail z where z.exercise_session_id = s.id and z.sport_type = '7'))
  union all
  select s.user_id, 'biggest_climb', s.elevation_gain_m::float8, true, (s.start_time at time zone 'UTC')::date, s.start_time, s.id
    from exercise_sessions s
   where s.type = 'run' and s.elevation_gain_m > 0 and s.elevation_gain_m < 10000
),
ranked as (
  select c.*,
         case when c.higher_better
              then max(c.value) over w
              else min(c.value) over w end as best_before
    from candidates c
  window w as (partition by c.user_id, c.record_key order by c.start_time, c.exercise_session_id rows between unbounded preceding and 1 preceding)
),
events as (
  select * from ranked
   where best_before is null
      or (higher_better and value > best_before)
      or (not higher_better and value < best_before)
)
select e.user_id, e.record_key, e.value, e.higher_better, e.achieved_on, e.exercise_session_id,
       lag(e.value) over p as previous_value,
       lag(e.achieved_on) over p as previous_on
  from events e
window p as (partition by e.user_id, e.record_key order by e.start_time, e.exercise_session_id);

grant select on public.v_personal_records to authenticated;
revoke all on public.v_personal_records from anon;

-- Read every run once.
select public.compute_run_best_efforts(id) from public.zepp_workout_detail where sport_type in ('1', '7') and decoded is not null;
