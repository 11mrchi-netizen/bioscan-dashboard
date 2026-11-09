create table public.ostrc_checkins (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  check_date date not null,
  body_area text not null,
  q1 smallint not null check (q1 in (0, 8, 17, 25)),
  q2 smallint not null check (q2 in (0, 6, 13, 19, 25)),
  q3 smallint not null check (q3 in (0, 6, 13, 19, 25)),
  q4 smallint not null check (q4 in (0, 8, 17, 25)),
  severity_score smallint generated always as (q1 + q2 + q3 + q4) stored,
  notes text,
  created_at timestamptz not null default now()
);

alter table public.ostrc_checkins enable row level security;

create policy "select own ostrc_checkins" on public.ostrc_checkins
  for select using ((select auth.uid()) = user_id);

create policy "insert own ostrc_checkins" on public.ostrc_checkins
  for insert with check ((select auth.uid()) = user_id);

create policy "update own ostrc_checkins" on public.ostrc_checkins
  for update using ((select auth.uid()) = user_id);

create policy "delete own ostrc_checkins" on public.ostrc_checkins
  for delete using ((select auth.uid()) = user_id);
