-- Analysis hub phase B (docs/analysis-console/PLAN.md): a stored daily summary of the minute heart-rate series.
--
-- wearable_hr_daily_range (20261104) unnested every day's hr_timeseries (about 1,370 minutes, ~7 kB) on each read:
-- 167 ms for 28 days and 1.5 s for the 488 days that have minute data (EXPLAIN ANALYZE, 2026-10-27). The summary is now
-- kept in a table, one row per day with minute data, refreshed by a trigger whenever a day's series is written, and the
-- view reads the table, with the same columns, so the app's Range Trend card needs no change.
--
-- minutes (minutes with a reading) is also the band's wear time, a coverage signal for the integrity workspace.
-- Parity with the old view: a day whose series is missing, not an array, empty or without a numeric bpm has no row.
--
-- Rollback: create or replace the view as in 20261104_wearable_hr_daily_range.sql;
--   drop trigger wearable_hr_daily_sync on public.wearable_daily; drop function private.wearable_hr_daily_sync();
--   drop function private.wearable_hr_daily_refresh(uuid, date, jsonb); drop table public.wearable_hr_daily;

create table public.wearable_hr_daily (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  date date not null,
  hr_min numeric not null,
  hr_max numeric not null,
  hr_mean numeric not null,
  minutes integer not null check (minutes > 0),
  updated_at timestamptz not null default now(),
  primary key (user_id, date)
);

alter table public.wearable_hr_daily enable row level security;

create policy wearable_hr_daily_select_own on public.wearable_hr_daily for select to authenticated using (user_id = (select auth.uid()));
-- No insert, update or delete policies: rows are written only by the trigger below.

comment on table public.wearable_hr_daily is 'Daily lowest, highest and mean minute heart rate and minutes with a reading, kept from wearable_daily.hr_timeseries by trigger (analysis hub phase B).';

create or replace function private.wearable_hr_daily_refresh(p_user uuid, p_date date, p_series jsonb)
returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  n integer;
begin
  if p_series is not null and jsonb_typeof(p_series) = 'array' then
    insert into public.wearable_hr_daily (user_id, date, hr_min, hr_max, hr_mean, minutes, updated_at)
    select p_user, p_date, min(b), max(b), avg(b), count(b), now()
    from (select (e.value ->> 'bpm')::numeric as b from jsonb_array_elements(p_series) as e(value)) s
    having count(b) > 0
    on conflict (user_id, date) do update
      set hr_min = excluded.hr_min, hr_max = excluded.hr_max, hr_mean = excluded.hr_mean,
          minutes = excluded.minutes, updated_at = excluded.updated_at;
    get diagnostics n = row_count;
    if n > 0 then return; end if;
  end if;
  delete from public.wearable_hr_daily where user_id = p_user and date = p_date;
end;
$$;

revoke all on function private.wearable_hr_daily_refresh(uuid, date, jsonb) from public, anon, authenticated;

create or replace function private.wearable_hr_daily_sync()
returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if tg_op = 'DELETE' or (tg_op = 'UPDATE' and (old.date is distinct from new.date or old.user_id is distinct from new.user_id)) then
    delete from public.wearable_hr_daily where user_id = old.user_id and date = old.date;
  end if;
  if tg_op <> 'DELETE' then
    perform private.wearable_hr_daily_refresh(new.user_id, new.date, to_jsonb(new.hr_timeseries));
  end if;
  return null;
end;
$$;

revoke all on function private.wearable_hr_daily_sync() from public, anon, authenticated;

create trigger wearable_hr_daily_sync
  after insert or delete or update of hr_timeseries, date, user_id on public.wearable_daily
  for each row execute function private.wearable_hr_daily_sync();

-- Backfill every day already stored.
insert into public.wearable_hr_daily (user_id, date, hr_min, hr_max, hr_mean, minutes)
select w.user_id, w.date, min(b), max(b), avg(b), count(b)
from public.wearable_daily w
cross join lateral (
  select (e.value ->> 'bpm')::numeric as b from jsonb_array_elements(to_jsonb(w.hr_timeseries)) as e(value)
) s
where w.hr_timeseries is not null
  and jsonb_typeof(to_jsonb(w.hr_timeseries)) = 'array'
group by w.user_id, w.date
having count(b) > 0
on conflict (user_id, date) do nothing;

-- Same columns as before; security_invoker keeps the caller's own row-level security.
create or replace view public.wearable_hr_daily_range with (security_invoker = true) as
select date, hr_min, hr_max from public.wearable_hr_daily;
