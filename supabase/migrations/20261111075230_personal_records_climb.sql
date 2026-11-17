-- Personal records: biggest climb reads Zepp's smoothed ascent when the run has one (Health Connect's elevation sums
-- barometer noise), and a climb under 5 m is not a record.
--
-- Rollback: re-run the view from 20261111075131_personal_records.sql.

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
  select c.user_id, 'biggest_climb', c.climb, true, (c.start_time at time zone 'UTC')::date, c.start_time, c.id
    from (
      -- Zepp's smoothed ascent when the run has one: Health Connect's elevation sums barometer noise (1,932 m on a
      -- 13 km run that climbed 655 m).
      select s.user_id, s.start_time, s.id,
             coalesce((select (z.summary->>'smoothedAscentM')::float8 from zepp_workout_detail z
                        where z.exercise_session_id = s.id and z.summary->>'smoothedAscentM' is not null limit 1),
                      s.elevation_gain_m::float8) as climb
        from exercise_sessions s
       where s.type = 'run'
    ) c
   where c.climb >= 5 and c.climb < 10000
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

