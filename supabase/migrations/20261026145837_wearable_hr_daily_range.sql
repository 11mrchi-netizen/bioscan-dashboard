-- Redesign (Health › Cardio › Heart Rate): a range-trend card draws one floating column per day from the day's lowest to
-- its highest heart rate. wearable_daily.hr_timeseries holds ~50 kB of minute readings per day, far too heavy to pull
-- for a year of columns, so the lowest and highest minute are computed here. Minutes are already limited to 30..220 bpm
-- by the decoder (20261018_hr_timeseries_cleanup.sql).
--
-- security_invoker: the view reads wearable_daily with the caller's own row-level security.
-- The column's type (json or jsonb) was created outside the migrations, so it goes through to_jsonb.
--
-- Rollback: drop view public.wearable_hr_daily_range;

create or replace view public.wearable_hr_daily_range with (security_invoker = true) as
select w.date,
       min((e.value ->> 'bpm')::numeric) as hr_min,
       max((e.value ->> 'bpm')::numeric) as hr_max
from public.wearable_daily w
cross join lateral jsonb_array_elements(to_jsonb(w.hr_timeseries)) as e(value)
where w.hr_timeseries is not null
  and jsonb_typeof(to_jsonb(w.hr_timeseries)) = 'array'
group by w.date;
