-- A workout's heart rate as seconds per bpm (zepp-extract hrHistogram.ts): zepp_workout_detail.hr_histogram, e.g.
-- {"148": 312, "149": 287}. The phone's weekly intensity card (Training › Load) reads twelve weeks of these at once and
-- places them in zones of any method and anchors, without loading each workout's per-second series.
--
-- Same rules as hrHistogram.ts: each sample's time runs to the next sample, capped at 10 s (a pause or a lost signal is
-- not counted at the last heart rate before it); only 30-230 bpm is kept; bpm rounded half up. zepp-extract writes it on
-- every import once redeployed; the backfill below fills stored workouts and can be re-run (it only fills empty rows), for
-- example after workouts imported by an older deploy.
--
-- Rollback: alter table public.zepp_workout_detail drop column hr_histogram;

alter table public.zepp_workout_detail add column if not exists hr_histogram jsonb;

update public.zepp_workout_detail z
set hr_histogram = h.hist
from (
  select id, jsonb_object_agg(bpm::text, secs) as hist
  from (
    select id, floor(v + 0.5)::int as bpm, sum(least(next_o - o, 10)) as secs
    from (
      select d.id, (e->>'offsetSeconds')::float8 as o, (e->>'value')::float8 as v,
             lead((e->>'offsetSeconds')::float8) over (partition by d.id order by (e->>'offsetSeconds')::float8) as next_o
      from public.zepp_workout_detail d
      cross join lateral jsonb_array_elements(d.decoded->'heartRate') as e
      where d.hr_histogram is null and jsonb_typeof(d.decoded->'heartRate') = 'array'
    ) samples
    where next_o > o and v between 30 and 230
    group by id, floor(v + 0.5)::int
  ) per_bpm
  group by id
) h
where z.id = h.id;
