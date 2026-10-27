-- Body-scale weigh-ins read straight from the scale's BLE advertisement (domain/ScaleBroadcast.kt), bypassing the OKOK app.
-- One row per saved weigh-in: the decoded weight and impedance plus the raw payload, so body composition can be recomputed
-- later with better formulas. The weight is also written to body_metrics for the day by the app (daily series, goals, fuel).
-- Own rows only.
--
-- Rollback: drop table public.scale_readings;

create table public.scale_readings (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- local wall-clock time labelled UTC, same convention as body_checkins
  occurred_at timestamptz not null,
  weight_kg numeric not null check (weight_kg between 5 and 400),
  impedance_ohm numeric check (impedance_ohm is null or impedance_ohm between 50 and 3000),
  raw_hex text check (raw_hex is null or length(raw_hex) <= 80),
  device_mac text check (device_mac is null or length(device_mac) <= 17),
  created_at timestamptz not null default now()
);

create index scale_readings_user_time_idx on public.scale_readings (user_id, occurred_at desc);

alter table public.scale_readings enable row level security;

create policy scale_readings_select_own on public.scale_readings for select to authenticated using (user_id = (select auth.uid()));
create policy scale_readings_insert_own on public.scale_readings for insert to authenticated with check (user_id = (select auth.uid()));
create policy scale_readings_update_own on public.scale_readings for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy scale_readings_delete_own on public.scale_readings for delete to authenticated using (user_id = (select auth.uid()));
