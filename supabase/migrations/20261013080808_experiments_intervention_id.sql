-- Intervention & Protocol Intelligence, Phase 08.5: generalize the N-of-1 engine from supplement-only to any
-- intervention. Additive only: supplement_id is untouched (0 live rows in `experiments` today, so this is safe),
-- `intervention_id` is a new nullable column alongside it. experiment.ts/stats.ts need no change at all -- they
-- already operate on arbitrary metric series from the shared daily frame, never supplement semantics; the metric
-- whitelist (primary_metric/secondary_metrics) also needs no change here, since those eight outcomes were already
-- domain-agnostic biometric/wellbeing signals, not supplement-specific -- only the thing an experiment concerns
-- (`intervention_id` alongside `supplement_id`) was supplement-locked, and that's what this migration generalizes.
-- Rollback: alter table public.experiments drop constraint experiments_has_a_subject;
--           drop index public.experiments_user_intervention_idx; alter table public.experiments drop column intervention_id;
--           (and restore the previous experiments_lock_protocol body, without intervention_id)

alter table public.experiments add column intervention_id uuid references public.interventions (id) on delete set null;

alter table public.experiments add constraint experiments_has_a_subject
  check (supplement_id is not null or intervention_id is not null);

create index experiments_user_intervention_idx on public.experiments (user_id, intervention_id);

create or replace function public.experiments_lock_protocol()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.user_id is distinct from old.user_id
     or new.supplement_id is distinct from old.supplement_id
     or new.intervention_id is distinct from old.intervention_id
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
