-- Health concerns (docs/health-concerns/01-plan.md §2.5, H-03): the two legacy event tables as they exist in the live
-- project. They were created before supabase/migrations existed, so the repository never described them. Every
-- statement is "if not exists", so on the live project this file changes nothing; on a fresh database it creates the
-- tables the app, the web dashboard, the console and the analysis core (illness days left out of comparisons) read.
-- Checked against information_schema, pg_constraint, pg_indexes and pg_policies on 21 Oct 2026.
--
-- Rollback: none needed (no-op on the live project).

create table if not exists public.injuries (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  part text not null,
  type text not null,
  severity smallint not null constraint injuries_severity_check check (severity >= 0 and severity <= 10),
  status text not null constraint injuries_status_check check (status = any (array['active', 'monitoring', 'resolved'])),
  start_date date not null,
  end_date date,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists injuries_user_id_idx on public.injuries (user_id);

create table if not exists public.illnesses (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name text not null,
  symptoms text,
  diagnosis text,
  medication text,
  severity smallint constraint illnesses_severity_check check (severity >= 0 and severity <= 10),
  status text not null constraint illnesses_status_check check (status = any (array['active', 'monitoring', 'resolved'])),
  start_date date not null,
  end_date date,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  doctor_seen boolean not null default false,
  medications jsonb not null default '[]'::jsonb
);
create index if not exists illnesses_user_id_idx on public.illnesses (user_id);

alter table public.injuries enable row level security;
alter table public.illnesses enable row level security;

do $$
begin
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'injuries') then
    create policy "select own injuries" on public.injuries for select using ((select auth.uid()) = user_id);
    create policy "insert own injuries" on public.injuries for insert with check ((select auth.uid()) = user_id);
    create policy "update own injuries" on public.injuries for update using ((select auth.uid()) = user_id);
    create policy "delete own injuries" on public.injuries for delete using ((select auth.uid()) = user_id);
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'illnesses') then
    create policy "select own illnesses" on public.illnesses for select using ((select auth.uid()) = user_id);
    create policy "insert own illnesses" on public.illnesses for insert with check ((select auth.uid()) = user_id);
    create policy "update own illnesses" on public.illnesses for update using ((select auth.uid()) = user_id);
    create policy "delete own illnesses" on public.illnesses for delete using ((select auth.uid()) = user_id);
  end if;
end
$$;
