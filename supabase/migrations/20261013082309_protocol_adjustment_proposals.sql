-- Intervention & Protocol Intelligence, Phase 08.7: mutable proposal rows from the adaptive-rules engine
-- (protocolRules.ts). Unlike analysis_results (service-role writes, no user writes at all), a proposal's `status`
-- must be settable by the owning user -- accepting, rejecting or modifying a suggestion is the whole point -- so
-- this is its own table rather than another analysis_results kind.
-- Rollback: drop table public.protocol_adjustment_proposals;

create table public.protocol_adjustment_proposals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  protocol_id uuid references public.protocols (id) on delete cascade,
  intervention_id uuid references public.interventions (id) on delete cascade,
  action text not null check (action in ('increase', 'decrease', 'maintain', 'pause', 'extend_evaluation', 'terminate', 'reassess')),
  -- plain-sentence rationale (protocolRules.ts's `rationale: string[]`) and the exact inputs that produced it
  -- (`sourceInputs`), so a proposal stays auditable independent of whatever analysis_results row it was read from
  rationale text[] not null default '{}',
  source_inputs jsonb not null,
  engine_version text not null,
  status text not null default 'proposed' check (status in ('proposed', 'accepted', 'rejected', 'modified')),
  -- if the person modified rather than accepted/rejected outright, what they changed it to (free-form, since the
  -- possible modifications are as varied as the protocols/interventions themselves)
  modification jsonb,
  decided_at timestamptz,
  created_at timestamptz not null default now(),
  constraint protocol_adjustment_proposals_has_a_subject check (protocol_id is not null or intervention_id is not null)
);

create index protocol_adjustment_proposals_user_status_idx on public.protocol_adjustment_proposals (user_id, status);

-- The engine's own output (action/rationale/source_inputs/engine_version/protocol_id/intervention_id) is locked the
-- same way experiments_lock_protocol locks a pre-registered protocol: a person may only ever patch their own
-- decision (status/modification/decided_at), never rewrite what the engine actually said.
create or replace function public.protocol_adjustment_proposals_lock_output()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.user_id is distinct from old.user_id
     or new.protocol_id is distinct from old.protocol_id
     or new.intervention_id is distinct from old.intervention_id
     or new.action is distinct from old.action
     or new.rationale is distinct from old.rationale
     or new.source_inputs is distinct from old.source_inputs
     or new.engine_version is distinct from old.engine_version
     or new.created_at is distinct from old.created_at then
    raise exception 'a protocol_adjustment_proposals row is the engine''s own output and cannot be changed (only status, modification and decided_at may be updated)';
  end if;
  return new;
end;
$$;

create trigger protocol_adjustment_proposals_lock_output before update on public.protocol_adjustment_proposals
  for each row execute function public.protocol_adjustment_proposals_lock_output();

alter table public.protocol_adjustment_proposals enable row level security;

create policy protocol_adjustment_proposals_select_own on public.protocol_adjustment_proposals for select to authenticated
  using (user_id = (select auth.uid()));
create policy protocol_adjustment_proposals_insert_own on public.protocol_adjustment_proposals for insert to authenticated
  with check (user_id = (select auth.uid()));
create policy protocol_adjustment_proposals_update_own on public.protocol_adjustment_proposals for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy protocol_adjustment_proposals_delete_own on public.protocol_adjustment_proposals for delete to authenticated
  using (user_id = (select auth.uid()));
