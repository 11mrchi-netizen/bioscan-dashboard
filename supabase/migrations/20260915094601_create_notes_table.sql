create table public.notes (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id),
  occurred_at timestamptz not null default now(),
  text text not null,
  created_at timestamptz not null default now()
);

alter table public.notes enable row level security;

create policy "select own notes" on public.notes for select
  using ((select auth.uid()) = user_id);

create policy "insert own notes" on public.notes for insert
  with check ((select auth.uid()) = user_id);

create policy "update own notes" on public.notes for update
  using ((select auth.uid()) = user_id);

create policy "delete own notes" on public.notes for delete
  using ((select auth.uid()) = user_id);
