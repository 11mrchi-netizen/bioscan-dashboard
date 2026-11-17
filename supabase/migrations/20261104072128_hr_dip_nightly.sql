-- BALANCE's night heart-rate dip, worked out once per night and kept, instead of on every read. The view
-- (20261119_hr_dip_view.sql) unpacked two days of minute JSON for each of 120 nights on every score build: 1.6 s on a
-- quiet database and 6 s next to the app's startup reads, close to the 8 s statement timeout of `authenticated`.
--
-- A night is computed when its data lands: a write to sleep_daily or wearable_daily recomputes the nights that row feeds
-- (its own date, and the next one, whose waking day it is). Writes older than 120 days are not scored and are skipped, so a
-- history backfill costs nothing. The minute offsets are local to the zone the phone recorded for that day
-- (daily_context.time_zone), else Asia/Taipei, the Zepp extractor's fallback zone. v_hr_dip keeps its name and columns, so
-- the app is unchanged.
--
-- Not handled: deleting a sleep or wearable row leaves its night's dip in place until that night is written again.
--
-- Rollback: drop trigger hr_dip_sleep_ins on public.sleep_daily; drop trigger hr_dip_sleep_upd on public.sleep_daily;
-- drop trigger hr_dip_wearable_ins on public.wearable_daily; drop trigger hr_dip_wearable_upd on public.wearable_daily;
-- drop function public.hr_dip_after_write(); drop function public.refresh_hr_dip(uuid, date[]);
-- then re-run 20261119_hr_dip_view.sql (create or replace view) and drop table public.hr_dip_nightly.

create table if not exists public.hr_dip_nightly (
  user_id uuid not null references auth.users (id) on delete cascade,
  date date not null,
  dip_pct double precision not null,
  computed_at timestamptz not null default now(),
  primary key (user_id, date)
);
alter table public.hr_dip_nightly enable row level security;
drop policy if exists "select own hr_dip_nightly" on public.hr_dip_nightly;
create policy "select own hr_dip_nightly" on public.hr_dip_nightly for select using ((select auth.uid()) = user_id);

-- Recomputes the given nights of one user. Only the triggers below call it (no grant to app roles).
create or replace function public.refresh_hr_dip(p_user uuid, p_dates date[]) returns void
language plpgsql security definer set search_path = '' as $$
declare
  lo date := (select min(d) from unnest(p_dates) d);
  hi date := (select max(d) from unnest(p_dates) d);
begin
  delete from public.hr_dip_nightly where user_id = p_user and date = any (p_dates);
  insert into public.hr_dip_nightly (user_id, date, dip_pct)
  with s as (
    select date, bedtime, wake_time, lag(wake_time) over (order by date) as prev_wake
    from public.sleep_daily
    where user_id = p_user and bedtime is not null and wake_time is not null and wake_time > bedtime
      and date between lo - 1 and hi
  ), w as (
    select s.*, coalesce(case when prev_wake < bedtime and prev_wake > bedtime - interval '24 hours' then prev_wake end,
                         bedtime - interval '14 hours') as day_start
    from s
    where date = any (p_dates)
  ), agg as (
    select w.date, x.night_hr, x.night_n, x.day_hr, x.day_n
    from w cross join lateral (
      select avg(m.bpm) filter (where m.at >= w.bedtime and m.at < w.wake_time) as night_hr,
             count(*) filter (where m.at >= w.bedtime and m.at < w.wake_time) as night_n,
             avg(m.bpm) filter (where m.at >= w.day_start and m.at < w.bedtime - interval '30 minutes') as day_hr,
             count(*) filter (where m.at >= w.day_start and m.at < w.bedtime - interval '30 minutes') as day_n
      from (
        select ((wd.date::timestamp + make_interval(mins => (p->>'offsetMinutes')::int))
                 at time zone coalesce(dc.time_zone, 'Asia/Taipei')) as at,
               (p->>'bpm')::int as bpm
        from public.wearable_daily wd
        left join public.daily_context dc on dc.user_id = wd.user_id and dc.date = wd.date
        cross join lateral jsonb_array_elements(wd.hr_timeseries) p
        where wd.user_id = p_user and wd.date in (w.date - 1, w.date) and wd.hr_timeseries is not null
      ) m
    ) x
  )
  select p_user, date, round((100 * (day_hr - night_hr) / day_hr)::numeric, 2)::double precision
  from agg
  where night_n >= 60 and day_n >= 240 and day_hr > 0;
end $$;
revoke all on function public.refresh_hr_dip(uuid, date[]) from public, anon, authenticated;

-- One statement trigger per write, so a sync that upserts many rows recomputes each night once.
create or replace function public.hr_dip_after_write() returns trigger
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  for r in
    select user_id, array_agg(distinct d) as ds
    from (select user_id, date as d from changed union select user_id, date + 1 from changed) x
    -- No upper bound: current_date is UTC and sleep dates are local, so this morning can be "tomorrow" here.
    where d >= current_date - 120
    group by user_id
  loop
    perform public.refresh_hr_dip(r.user_id, r.ds);
  end loop;
  return null;
end $$;
revoke all on function public.hr_dip_after_write() from public, anon, authenticated;

drop trigger if exists hr_dip_sleep_ins on public.sleep_daily;
drop trigger if exists hr_dip_sleep_upd on public.sleep_daily;
drop trigger if exists hr_dip_wearable_ins on public.wearable_daily;
drop trigger if exists hr_dip_wearable_upd on public.wearable_daily;
create trigger hr_dip_sleep_ins after insert on public.sleep_daily
  referencing new table as changed for each statement execute function public.hr_dip_after_write();
create trigger hr_dip_sleep_upd after update on public.sleep_daily
  referencing new table as changed for each statement execute function public.hr_dip_after_write();
create trigger hr_dip_wearable_ins after insert on public.wearable_daily
  referencing new table as changed for each statement execute function public.hr_dip_after_write();
create trigger hr_dip_wearable_upd after update on public.wearable_daily
  referencing new table as changed for each statement execute function public.hr_dip_after_write();

-- Backfill the scored window for everyone who has sleep in it.
do $$
declare r record;
begin
  for r in
    select user_id, array_agg(date) as ds from public.sleep_daily where date >= current_date - 120 group by user_id
  loop
    perform public.refresh_hr_dip(r.user_id, r.ds);
  end loop;
end $$;

create or replace view public.v_hr_dip with (security_invoker = true) as
select user_id, date, dip_pct from public.hr_dip_nightly where date >= current_date - 120;
