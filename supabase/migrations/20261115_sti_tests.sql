-- Sex tracking: STI test results. One row is one infection's result from one test; a panel is several rows on one date.
--
-- Its own table, not lab_draws / lab_results: a draw is unique per date and ME › BLOODWORK compares the earliest draw
-- with the latest, so an STI panel logged there would become "the latest draw" and empty that comparison.
--
--  - result: negative / positive / inconclusive / pending. A pending row does not count as the last test.
--  - treated_on: set when a positive result has been treated; until then the app lists it as open.
--  - infection and site are free text: the vocabulary lives in the app (domain/IntimacyStats.kt StiVocab).
--
-- Re-runnable. Rollback: drop table public.sti_tests;

create table if not exists public.sti_tests (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  test_date date not null,
  infection text not null,
  result text not null check (result in ('negative', 'positive', 'inconclusive', 'pending')),
  site text,
  lab_name text,
  treated_on date,
  notes text,
  created_at timestamptz not null default now()
);

create index if not exists sti_tests_user_date on public.sti_tests (user_id, test_date desc);

alter table public.sti_tests enable row level security;

drop policy if exists sti_tests_select_own on public.sti_tests;
drop policy if exists sti_tests_insert_own on public.sti_tests;
drop policy if exists sti_tests_update_own on public.sti_tests;
drop policy if exists sti_tests_delete_own on public.sti_tests;
create policy sti_tests_select_own on public.sti_tests for select to authenticated using (user_id = (select auth.uid()));
create policy sti_tests_insert_own on public.sti_tests for insert to authenticated with check (user_id = (select auth.uid()));
create policy sti_tests_update_own on public.sti_tests for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy sti_tests_delete_own on public.sti_tests for delete to authenticated using (user_id = (select auth.uid()));

comment on table public.sti_tests is 'STI test results: one infection per row, several rows per test date.';
