-- Analysis hub phase E (docs/analysis-console/PLAN.md; IA.md §3.4 "Save workspace"): named console views. A view is the
-- console route with its filters (path, e.g. '/explore?series=hrv,rhr&grain=week') and the date range. A preset range is
-- kept as a number of days ({"days": 90}) so it stays relative to today; a custom range as its two dates (the console's
-- rangeStore convention).
--
-- Rollback: drop table public.saved_views;

create table public.saved_views (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 80),
  path text not null check (path like '/%' and length(path) <= 2000),
  range jsonb not null,
  created_at timestamptz not null default now(),
  unique (user_id, name)
);

alter table public.saved_views enable row level security;

create policy saved_views_select_own on public.saved_views for select to authenticated using (user_id = (select auth.uid()));
create policy saved_views_insert_own on public.saved_views for insert to authenticated with check (user_id = (select auth.uid()));
create policy saved_views_update_own on public.saved_views for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy saved_views_delete_own on public.saved_views for delete to authenticated using (user_id = (select auth.uid()));

comment on table public.saved_views is 'Analysis hub phase E: named console views (route with filters and date range).';
