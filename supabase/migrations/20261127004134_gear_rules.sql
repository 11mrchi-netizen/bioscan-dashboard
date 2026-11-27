-- Lifting gear. A set's gear needs no column: it is the optional `gear` list on each set inside exercise_sessions.details.exercises
-- (absent = not recorded, [] = recorded as none). What is new is the person's RULES for when they use it ("straps on deadlifts from
-- 140 kg"), one row per lift and gear tag, read by the phone and the web to pre-fill a set from its weight.
-- movement_key is movementKey(name) (domain/training/MovementKey.kt, training-core/maxes.ts): "Deadlift" and "Barbell Deadlift" share one.
-- Rollback: drop table public.gear_rules;
create table public.gear_rules (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  movement_key text not null,
  gear text not null check (gear in ('straps','belt','sleeves','wraps','chalk','hook','shoes')),
  from_kg numeric check (from_kg is null or from_kg >= 0),
  created_at timestamptz not null default now(),
  unique (user_id, movement_key, gear)
);
create index gear_rules_user on public.gear_rules (user_id);

alter table public.gear_rules enable row level security;
create policy gear_rules_select_own on public.gear_rules for select to authenticated using (user_id = (select auth.uid()));
create policy gear_rules_insert_own on public.gear_rules for insert to authenticated with check (user_id = (select auth.uid()));
create policy gear_rules_update_own on public.gear_rules for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy gear_rules_delete_own on public.gear_rules for delete to authenticated using (user_id = (select auth.uid()));
