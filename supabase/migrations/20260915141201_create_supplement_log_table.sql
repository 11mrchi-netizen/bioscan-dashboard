create table public.supplement_log (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id),
  supplement_id bigint references public.supplements(id) on delete set null,
  supplement_name text not null,
  taken_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);

alter table public.supplement_log enable row level security;

create policy "select own supplement_log" on public.supplement_log for select
  using ((select auth.uid()) = user_id);

create policy "insert own supplement_log" on public.supplement_log for insert
  with check ((select auth.uid()) = user_id);

create policy "update own supplement_log" on public.supplement_log for update
  using ((select auth.uid()) = user_id);

create policy "delete own supplement_log" on public.supplement_log for delete
  using ((select auth.uid()) = user_id);
