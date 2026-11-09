-- Intervention & Protocol Intelligence, Phase 08.2: canonical interventions, generalized across domains (supplement,
-- nutrition, training, sleep, lifestyle). Unlike `experiments` (pre-registered, protocol-locked), an intervention is a
-- living record: dose, dates and measurement plan can be edited while it runs. Only the lifecycle transition itself is
-- audited, into `intervention_state_history`, via a trigger -- the allowed-transition graph is enforced in the app
-- (supabase/functions/_shared/analysis-core/intervention.ts), the same division of labour `Experiment.kt` uses ahead of
-- the (looser) database CHECK constraints.
-- Rollback: drop table public.intervention_state_history; drop table public.interventions;

create table public.interventions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  domain text not null check (domain in ('supplement', 'nutrition', 'training', 'sleep', 'lifestyle', 'other')),
  -- shares vocabulary with evidence_records.subject_type / subject_key, so an intervention can look up its own evidence
  subject_type text not null,
  subject_key text not null,
  name text not null,
  mechanism text,
  target_systems text[] not null default '{}',
  -- outcome_key vocabulary, reused by evidence_records.outcome_key
  intended_outcomes text[] not null default '{}',
  -- dose/intensity/frequency/timing, free-form per domain (same convention as experiments.protocol)
  dosing jsonb not null default '{}'::jsonb,
  lifecycle_state text not null default 'proposed'
    check (lifecycle_state in ('proposed', 'baseline', 'active', 'evaluating', 'validated', 'maintained', 'modified', 'stopped')),
  baseline_started_at date,
  started_at date,
  ended_at date,
  required_measurements text[] not null default '{}',
  optional_measurements text[] not null default '{}',
  burden_cost_risk jsonb,
  -- domain pointer: which domain-owned row this intervention represents. Nullable, one column per domain so far, added
  -- as domains are wired in -- never duplicating a domain's own canonical data (e.g. supplement name/dose live on
  -- public.supplements; this just links to it).
  supplement_id bigint references public.supplements (id) on delete set null,
  source text,
  version integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index interventions_user_domain_idx on public.interventions (user_id, domain);
create index interventions_user_lifecycle_idx on public.interventions (user_id, lifecycle_state);

create table public.intervention_state_history (
  id bigint generated always as identity primary key,
  intervention_id uuid not null references public.interventions (id) on delete cascade,
  from_state text,
  to_state text not null,
  changed_at timestamptz not null default now()
);

create index intervention_state_history_intervention_idx on public.intervention_state_history (intervention_id, changed_at);

create or replace function public.interventions_log_lifecycle_change()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.lifecycle_state is distinct from old.lifecycle_state then
    insert into public.intervention_state_history (intervention_id, from_state, to_state)
    values (new.id, old.lifecycle_state, new.lifecycle_state);
    new.version := old.version + 1;
  end if;
  new.updated_at := now();
  return new;
end;
$$;

create trigger interventions_log_lifecycle_change before update on public.interventions
  for each row execute function public.interventions_log_lifecycle_change();

alter table public.interventions enable row level security;

create policy interventions_select_own on public.interventions for select to authenticated using (user_id = (select auth.uid()));
create policy interventions_insert_own on public.interventions for insert to authenticated with check (user_id = (select auth.uid()));
create policy interventions_update_own on public.interventions for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy interventions_delete_own on public.interventions for delete to authenticated using (user_id = (select auth.uid()));

alter table public.intervention_state_history enable row level security;

-- The trigger above runs as the calling user (not security definer), so it still needs an insert policy of its own;
-- no update/delete policies, since history rows are append-only. Both policies check the parent intervention's
-- ownership rather than a user_id column on this table, since this table only ever exists behind its parent.
create policy intervention_state_history_select_own on public.intervention_state_history for select to authenticated
  using (exists (
    select 1 from public.interventions i
    where i.id = intervention_state_history.intervention_id and i.user_id = (select auth.uid())
  ));

create policy intervention_state_history_insert_own on public.intervention_state_history for insert to authenticated
  with check (exists (
    select 1 from public.interventions i
    where i.id = intervention_state_history.intervention_id and i.user_id = (select auth.uid())
  ));
