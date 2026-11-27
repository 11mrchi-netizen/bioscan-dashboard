-- The phone's scored morning, saved per day (web desk, saved day view D1, Nov 2026).
--   today_views   one row per person and day: the whole ScoresView the phone's TODAY drew (scores, CHARGE strip, domain strips,
--                 series, scorecards, weights, stool, last sleep, stress days, exercise windows, illness, signals, sleep target),
--                 as the phone's own serializer wrote it (view.v = model_version). The web Today reads it to open any day's
--                 rings with the same depth as the phone; it never recomputes a score. Replaced as a whole on every write.
--                 The phone keeps the last 120 days and deletes older rows. Own rows only.
--
-- Rollback: drop table public.today_views;

create table if not exists public.today_views (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  day date not null,
  model_version int not null default 1,
  view jsonb not null check (jsonb_typeof(view) = 'object'),
  updated_at timestamptz not null default now(),
  unique (user_id, day)
);

alter table public.today_views enable row level security;

drop policy if exists today_views_select_own on public.today_views;
create policy today_views_select_own on public.today_views for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists today_views_insert_own on public.today_views;
create policy today_views_insert_own on public.today_views for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists today_views_update_own on public.today_views;
create policy today_views_update_own on public.today_views for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists today_views_delete_own on public.today_views;
create policy today_views_delete_own on public.today_views for delete to authenticated using (user_id = (select auth.uid()));
