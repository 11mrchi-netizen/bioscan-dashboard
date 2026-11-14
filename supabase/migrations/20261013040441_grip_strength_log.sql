-- Analysis Layer 3 / Phase 8 follow-up: handgrip strength readings, logged by hand.
-- One row per hand per measurement (best of the attempts, in kg, from a hand dynamometer). Own rows only.
-- Rollback: drop table public.grip_strength_log;

create table public.grip_strength_log (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- local wall-clock time labelled with an offset, same convention as stool_log / notes
  occurred_at timestamptz not null,
  hand text not null check (hand in ('left', 'right')),
  kg numeric not null check (kg >= 5 and kg <= 120),
  created_at timestamptz not null default now()
);

create index grip_strength_log_user_time_idx on public.grip_strength_log (user_id, occurred_at desc);

alter table public.grip_strength_log enable row level security;

create policy grip_strength_log_select_own on public.grip_strength_log for select to authenticated using (user_id = (select auth.uid()));
create policy grip_strength_log_insert_own on public.grip_strength_log for insert to authenticated with check (user_id = (select auth.uid()));
create policy grip_strength_log_update_own on public.grip_strength_log for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy grip_strength_log_delete_own on public.grip_strength_log for delete to authenticated using (user_id = (select auth.uid()));
