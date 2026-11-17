-- Analysis Layer 3 / Phase 5: supplement N-of-1 experiments, pre-registered.
-- The protocol (what is compared, on which days, which outcome is primary) is fixed when the row is created: a trigger rejects
-- any later change to the protocol columns, so the analysis cannot be re-chosen after seeing the data. Only `status` (and
-- updated_at) may change. Results are published by analysis-run into analysis_results (kind 'experiment', key = experiment id).
-- Rollback: drop table public.experiments;

create table public.experiments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  supplement_id bigint references public.supplements (id) on delete set null,
  name text not null,
  primary_metric text not null check (primary_metric in ('hrv', 'rhr', 'sleep_hours', 'deep_rem_min', 'energy', 'mood', 'stress', 'soreness')),
  secondary_metrics text[] not null default '{}'
    check (cardinality(secondary_metrics) <= 3 and secondary_metrics <@ array['hrv', 'rhr', 'sleep_hours', 'deep_rem_min', 'energy', 'mood', 'stress', 'soreness']::text[]),
  design text not null check (design in ('AB', 'ABAB')),
  -- {"phases":[{"label":"A","start":"2026-11-01","end":"2026-11-28"},{"label":"B",...}],"washoutDays":0,"minAdherence":0.7,"excludeAlcoholDays":true}
  protocol jsonb not null,
  protocol_version text not null default '1',
  -- minimum detectable effect per candidate metric at registration (what the person was told before starting)
  mde_preview jsonb,
  status text not null default 'planned' check (status in ('planned', 'running', 'completed', 'abandoned')),
  preregistered_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index experiments_user_status_idx on public.experiments (user_id, status);

create or replace function public.experiments_lock_protocol()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.user_id is distinct from old.user_id
     or new.supplement_id is distinct from old.supplement_id
     or new.name is distinct from old.name
     or new.primary_metric is distinct from old.primary_metric
     or new.secondary_metrics is distinct from old.secondary_metrics
     or new.design is distinct from old.design
     or new.protocol is distinct from old.protocol
     or new.protocol_version is distinct from old.protocol_version
     or new.mde_preview is distinct from old.mde_preview
     or new.preregistered_at is distinct from old.preregistered_at then
    raise exception 'experiment protocol is pre-registered and cannot be changed (only status may be updated)';
  end if;
  new.updated_at := now();
  return new;
end;
$$;

create trigger experiments_lock_protocol before update on public.experiments
  for each row execute function public.experiments_lock_protocol();

alter table public.experiments enable row level security;

create policy experiments_select_own on public.experiments for select to authenticated using (user_id = (select auth.uid()));
create policy experiments_insert_own on public.experiments for insert to authenticated with check (user_id = (select auth.uid()));
create policy experiments_update_own on public.experiments for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy experiments_delete_own on public.experiments for delete to authenticated using (user_id = (select auth.uid()));
