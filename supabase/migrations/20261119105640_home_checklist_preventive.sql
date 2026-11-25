-- Risk profile R4 (docs/risk-profile/01-plan.md):
-- 1. home_checklist_answers: the home and water checklist (supabase/functions/_shared/risk/home-checklist.json), one row
--    per answer; the latest per item counts, earlier ones are its history.
-- 2. preventive_records: when a screening, vaccine or check-up from preventive.json was done (local date), how (a method
--    with its own interval), or that it does not apply to the person.
-- Both append-only (no update policy): a mistake is deleted and entered again. Own rows only.
-- Rollback: drop table public.preventive_records, public.home_checklist_answers;

create table public.home_checklist_answers (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  item text not null check (length(item) between 1 and 60),
  value text not null check (length(value) between 1 and 60),
  defs_version text not null,
  answered_at timestamptz not null default now()
);

create index home_checklist_answers_user_idx on public.home_checklist_answers (user_id, item, answered_at desc);

create table public.preventive_records (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  item text not null check (length(item) between 1 and 60),
  -- the local date it was done; null with not_applicable
  done_on date,
  method text check (method is null or length(method) between 1 and 40),
  not_applicable boolean not null default false,
  note text check (note is null or length(note) <= 500),
  created_at timestamptz not null default now(),
  check (not_applicable or done_on is not null)
);

create index preventive_records_user_idx on public.preventive_records (user_id, item, done_on desc);

alter table public.home_checklist_answers enable row level security;
alter table public.preventive_records enable row level security;

create policy home_checklist_answers_select_own on public.home_checklist_answers for select to authenticated using (user_id = (select auth.uid()));
create policy home_checklist_answers_insert_own on public.home_checklist_answers for insert to authenticated with check (user_id = (select auth.uid()));
create policy home_checklist_answers_delete_own on public.home_checklist_answers for delete to authenticated using (user_id = (select auth.uid()));
create policy preventive_records_select_own on public.preventive_records for select to authenticated using (user_id = (select auth.uid()));
create policy preventive_records_insert_own on public.preventive_records for insert to authenticated with check (user_id = (select auth.uid()));
create policy preventive_records_delete_own on public.preventive_records for delete to authenticated using (user_id = (select auth.uid()));

comment on table public.home_checklist_answers is 'Home and water checklist answers (risk profile R4); the latest per item counts. Own rows only.';
comment on table public.preventive_records is 'Preventive care done or not applicable (risk profile R4). done_on is a local date. Own rows only.';
