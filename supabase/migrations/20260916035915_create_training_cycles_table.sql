create type public.focus_quality as enum (
  'hypertrophy', 'strength', 'power', 'aerobic_base', 'race_specific_endurance',
  'peaking_realization', 'sport_skill', 'recovery_deload', 'maintenance'
);

create table public.training_cycles (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  start_date date not null,
  end_date date,
  focus jsonb not null,
  undulation jsonb,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.training_cycles enable row level security;

create policy "select own training_cycles" on public.training_cycles
  for select using ((select auth.uid()) = user_id);

create policy "insert own training_cycles" on public.training_cycles
  for insert with check ((select auth.uid()) = user_id);

create policy "update own training_cycles" on public.training_cycles
  for update using ((select auth.uid()) = user_id);

create policy "delete own training_cycles" on public.training_cycles
  for delete using ((select auth.uid()) = user_id);
