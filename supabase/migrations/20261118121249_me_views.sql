-- The Me tabs as the phone worked them out (web transition plan, phase 3, Nov 2026).
--   me_views   one row per person and Me tab (BODY, PERFORMANCE, HEALTHSPAN, ...): a plain, display-ready document of the
--              tab's sections (tiles, rows, charts, rings), every value already formatted as the phone shows it. Written by
--              the phone when the tab is built and every few hours in the background; the web Dashboard renders it and
--              recomputes nothing. Replaced as a whole on every write. Own rows only.
--
-- Rollback: drop table public.me_views;

create table if not exists public.me_views (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  tab text not null check (tab ~ '^[A-Z]+$'),
  view jsonb not null check (jsonb_typeof(view) = 'object'),
  built_at timestamptz not null default now(),
  unique (user_id, tab)
);

alter table public.me_views enable row level security;

drop policy if exists me_views_select_own on public.me_views;
create policy me_views_select_own on public.me_views for select to authenticated using (user_id = (select auth.uid()));
drop policy if exists me_views_insert_own on public.me_views;
create policy me_views_insert_own on public.me_views for insert to authenticated with check (user_id = (select auth.uid()));
drop policy if exists me_views_update_own on public.me_views;
create policy me_views_update_own on public.me_views for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
drop policy if exists me_views_delete_own on public.me_views;
create policy me_views_delete_own on public.me_views for delete to authenticated using (user_id = (select auth.uid()));
