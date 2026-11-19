-- Risk profile R1 (docs/risk-profile/01-plan.md): the air a person breathed, day by day.
--
-- 1. outdoor_air_hourly: a shared cache of modelled outdoor air (Open-Meteo Air Quality API, CAMS) per 0.1 degree grid cell
--    and UTC hour. Written and read only by the analysis run (service role): the cells a person was in say where they
--    went, so no end-user policy exists.
-- 2. air_exposure_daily: one row per person and local date (analysis-core/airExposure.ts). Indoor readings at home from
--    Home Terminal, the outdoor cell elsewhere, outdoor during outdoor sessions. Service-role writes, own-row reads.
-- 3. The nightly run gains an `exposures` group (runGroups.ts) at 20:45 UTC, after the others.
-- Times: `hour` is a real instant (UTC). `date` is the person's local calendar date (daily_context offset).
-- Rollback: select cron.unschedule('analysis-run-exposures'); drop table public.air_exposure_daily, public.outdoor_air_hourly;
--           and re-run 20261029052203_analysis_run_groups.sql's function definition.

create table public.outdoor_air_hourly (
  cell_lat real not null check (cell_lat between -90 and 90),
  cell_lon real not null check (cell_lon between -180 and 180),
  hour timestamptz not null,
  pm25_ugm3 real,
  no2_ugm3 real,
  o3_ugm3 real,
  uv_index real,
  source text not null default 'open-meteo-cams',
  fetched_at timestamptz not null default now(),
  primary key (cell_lat, cell_lon, hour)
);

alter table public.outdoor_air_hourly enable row level security;
comment on table public.outdoor_air_hourly is 'Modelled outdoor air per 0.1 degree cell and UTC hour (Open-Meteo / CAMS). Service role only.';

create table public.air_exposure_daily (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  date date not null,
  -- hours of the day with a PM2.5 value from any source (0-24)
  coverage_h smallint not null,
  -- time-weighted personal PM2.5 over the covered hours
  pm25_ugm3 real,
  -- indoor PM2.5 over the hours at home with a sensor reading
  pm25_indoor_ugm3 real,
  -- the outdoor PM2.5 at home's cell over the whole day, for reference
  pm25_outdoor_home_ugm3 real,
  no2_ugm3 real,
  -- highest 8-hour mean of outdoor ozone where the person was
  o3_mda8_ugm3 real,
  uv_max real,
  uv_session_max real,
  -- bedroom ventilation marker: mean indoor CO2 at home 22:00-07:00, and the hours above 1,400 ppm
  co2_night_ppm real,
  co2_night_poor_h smallint,
  -- share of the day's PM2.5 dose (concentration x hours) breathed at home and during outdoor sessions
  dose_home_share real,
  dose_session_share real,
  -- {indoor, outdoor_home, outdoor_away, session}: hours from each source
  sources jsonb not null default '{}'::jsonb,
  -- one entry per local hour: [hour, source, pm25, no2, o3, uv, co2]
  hours jsonb not null default '[]'::jsonb,
  -- outdoor sessions that day: [{start, type, minutes, pm25, o3, uv}]
  sessions jsonb not null default '[]'::jsonb,
  engine_version text not null,
  calculated_at timestamptz not null default now(),
  primary key (user_id, date)
);

alter table public.air_exposure_daily enable row level security;
create policy air_exposure_daily_select_own on public.air_exposure_daily for select to authenticated using (user_id = (select auth.uid()));
-- No insert/update/delete policies: written by the analysis run with the service role.
comment on table public.air_exposure_daily is 'Personal air exposure per local date (risk profile R1). Service-role writes, own-row reads.';

create or replace function private.run_analysis(p_group text default 'all')
returns void
language plpgsql
security definer
set search_path to 'public', 'extensions'
as $$
declare
  cron_secret text;
begin
  if p_group not in ('all', 'core', 'bands', 'interventions', 'fitness', 'health', 'exposures') then
    raise exception 'unknown analysis group %', p_group;
  end if;
  select decrypted_secret into cron_secret from vault.decrypted_secrets where name = 'analysis_cron_secret';
  if cron_secret is null then
    raise notice 'analysis_cron_secret not found in vault, skipping analysis run';
    return;
  end if;

  perform net.http_post(
    url := 'https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1/analysis-run',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      -- public anon key: only satisfies the gateway's JWT check, grants no access by itself
      'Authorization', 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVnZnJnbGJjb2l2a3ByanF2anp6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkxMDYxOTAsImV4cCI6MjEwNDY4MjE5MH0.0G_5XnBiIsUTQG7vlSTARND8apI-mI6RdUytCSWY9To',
      'x-analysis-cron', cron_secret
    ),
    body := jsonb_build_object('action', 'run', 'group', p_group),
    timeout_milliseconds := 60000
  );
end;
$$;

select cron.schedule('analysis-run-exposures', '45 20 * * *', $$select private.run_analysis('exposures')$$);
