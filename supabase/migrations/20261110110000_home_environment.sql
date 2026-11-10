-- Home environment, phase E1 (docs/home-environment/README.md): the Home Terminal app on the Google TV Streamer reads the
-- home's Matter sensors through the Google Home APIs and writes what they measure here.
--
-- 1. home_sensors: one row per Google Home device the collector has seen (TIMMERFLOTTE, MYGGSPRAY, the Lengceoi
--    thermometer, later ALPSTUGA). id is the Home APIs device id, stable while the device stays in the home.
-- 2. home_env_readings: one row per measurement. measured_at is a real instant (when the collector received the value),
--    stored as true UTC; convert it with the zone, never read its digits as local time.
--    The collector writes a value when it changes, and the same value again when the sensor reports it 15 minutes or
--    more after the last write, so a flat line still shows the sensor was alive. raw keeps the value exactly as Matter reported it (hundredths of a degree,
--    hundredths of a percent, Matter's log-scaled illuminance), so a conversion can be redone.
-- 3. collector_heartbeats gains the 'home_env' stream: proof the collector was running, so a gap reads as unknown.
--
-- Every new table: RLS, owner only, for all four commands.
--
-- Rollback: drop table public.home_env_readings, public.home_sensors;
--   delete from public.collector_heartbeats where stream = 'home_env'; then restore the stream check without it.

create table public.home_sensors (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  name text not null,
  room text,
  -- Home APIs device types, e.g. {TemperatureSensorDevice, HumiditySensorDevice}
  device_types text[] not null default '{}',
  vendor text,
  product text,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  source text not null default 'google_home_api',
  primary key (user_id, id)
);

create table public.home_env_readings (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  sensor_id text not null,
  metric text not null check (metric in ('temperature_c', 'humidity_pct', 'illuminance_lux', 'co2_ppm', 'pm25_ugm3',
                                         'air_quality')),
  measured_at timestamptz not null,
  value double precision not null,
  raw double precision,
  source text not null default 'google_home_api',
  primary key (user_id, sensor_id, metric, measured_at),
  foreign key (user_id, sensor_id) references public.home_sensors (user_id, id) on delete cascade
);

create index home_env_readings_recent on public.home_env_readings (user_id, metric, measured_at desc);

do $$
declare t text;
begin
  foreach t in array array['home_sensors', 'home_env_readings']
  loop
    execute format('alter table public.%I enable row level security', t);
    execute format('create policy %I on public.%I for select to authenticated using (user_id = (select auth.uid()))', t || '_select_own', t);
    execute format('create policy %I on public.%I for insert to authenticated with check (user_id = (select auth.uid()))', t || '_insert_own', t);
    execute format('create policy %I on public.%I for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))', t || '_update_own', t);
    execute format('create policy %I on public.%I for delete to authenticated using (user_id = (select auth.uid()))', t || '_delete_own', t);
  end loop;
end
$$;

alter table public.collector_heartbeats drop constraint collector_heartbeats_stream_check;
alter table public.collector_heartbeats add constraint collector_heartbeats_stream_check
  check (stream in ('location', 'phone_usage', 'health_connect_sync', 'zepp_sync', 'home_env'));
