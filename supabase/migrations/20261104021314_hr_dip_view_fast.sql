create or replace view public.v_hr_dip with (security_invoker = true) as
with s as (
  select user_id, date, bedtime, wake_time,
         lag(wake_time) over (partition by user_id order by date) as prev_wake
  from public.sleep_daily
  where bedtime is not null and wake_time is not null and wake_time > bedtime and date >= current_date - 120
), w as (
  select s.*, coalesce(case when prev_wake < bedtime and prev_wake > bedtime - interval '24 hours' then prev_wake end,
                       bedtime - interval '14 hours') as day_start
  from s
), agg as (
  select w.user_id, w.date, x.night_hr, x.night_n, x.day_hr, x.day_n
  from w cross join lateral (
    select avg(m.bpm) filter (where m.at >= w.bedtime and m.at < w.wake_time) as night_hr,
           count(*) filter (where m.at >= w.bedtime and m.at < w.wake_time) as night_n,
           avg(m.bpm) filter (where m.at >= w.day_start and m.at < w.bedtime - interval '30 minutes') as day_hr,
           count(*) filter (where m.at >= w.day_start and m.at < w.bedtime - interval '30 minutes') as day_n
    from (
      select ((wd.date::timestamp + make_interval(mins => (p->>'offsetMinutes')::int)) at time zone 'Asia/Taipei') as at,
             (p->>'bpm')::int as bpm
      from public.wearable_daily wd cross join lateral jsonb_array_elements(wd.hr_timeseries) p
      where wd.user_id = w.user_id and wd.date in (w.date - 1, w.date) and wd.hr_timeseries is not null
    ) m
  ) x
)
select user_id, date, round((100 * (day_hr - night_hr) / day_hr)::numeric, 2)::double precision as dip_pct
from agg
where night_n >= 60 and day_n >= 240 and day_hr > 0;
