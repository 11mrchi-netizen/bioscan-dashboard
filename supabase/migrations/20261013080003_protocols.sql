-- Intervention & Protocol Intelligence, Phase 08.3: protocols group interventions around one objective. A protocol's
-- own lifecycle is coarser than an intervention's (supabase/functions/_shared/analysis-core/protocol.ts enforces the
-- transition graph, same division of labour as 20261013_interventions.sql). protocol_templates is a shared, read-only
-- reference table (descriptive starting points, not prescriptive rules), seeded below -- same convention as
-- benchmark_artifacts: one `using (true)` select policy, no write policies, rows added only by migration.
-- Rollback: drop table public.protocol_state_history; drop table public.protocol_interventions;
--           drop table public.protocols; drop table public.protocol_templates;

create table public.protocol_templates (
  template_key text primary key,
  name text not null,
  objective_outcome_key text not null,
  description text not null,
  -- descriptive guidance only (e.g. suggested domains, typical windows) -- never a prescriptive rule the app follows automatically
  suggested_structure jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

alter table public.protocol_templates enable row level security;
create policy protocol_templates_select_all on public.protocol_templates for select to authenticated using (true);

insert into public.protocol_templates (template_key, name, objective_outcome_key, description, suggested_structure) values
  ('sleep_optimization', 'Sleep optimization', 'sleep_duration',
   'Interventions aimed at sleep duration and regularity (wind-down routine, caffeine cutoff, consistent schedule, magnesium/glycine-type supplements).',
   '{"suggestedDomains": ["sleep", "supplement", "lifestyle"], "suggestedBaselineDays": 14, "suggestedActiveDays": 28}'::jsonb),
  ('aerobic_base_development', 'Aerobic base development', 'vo2max',
   'Low-intensity volume aimed at aerobic capacity before a harder training block.',
   '{"suggestedDomains": ["training"], "suggestedBaselineDays": 14, "suggestedActiveDays": 56}'::jsonb),
  ('strength_development', 'Strength development', 'strength_level',
   'Progressive resistance training aimed at a measurable strength outcome.',
   '{"suggestedDomains": ["training"], "suggestedBaselineDays": 7, "suggestedActiveDays": 56}'::jsonb),
  ('recovery', 'Recovery', 'hrv',
   'Interventions aimed at reducing accumulated fatigue (deload, sleep, recovery sessions) rather than progressing load.',
   '{"suggestedDomains": ["training", "sleep", "lifestyle"], "suggestedBaselineDays": 7, "suggestedActiveDays": 14}'::jsonb),
  ('metabolic_health', 'Metabolic health', 'hba1c',
   'Nutrition and activity interventions aimed at a measurable metabolic marker.',
   '{"suggestedDomains": ["nutrition", "training"], "suggestedBaselineDays": 14, "suggestedActiveDays": 84}'::jsonb),
  ('micronutrient_sufficiency', 'Micronutrient sufficiency', 'ferritin',
   'A targeted supplement protocol aimed at correcting a specific measured deficiency.',
   '{"suggestedDomains": ["supplement"], "suggestedBaselineDays": 7, "suggestedActiveDays": 84}'::jsonb);

create table public.protocols (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name text not null,
  -- same free-text outcome_key vocabulary as evidence_records.outcome_key / interventions.intended_outcomes
  objective_outcome_key text not null,
  template_key text references public.protocol_templates (template_key) on delete set null,
  lifecycle_state text not null default 'proposed' check (lifecycle_state in ('proposed', 'active', 'evaluating', 'completed', 'stopped')),
  -- windows and measurement plan, same free-form convention as experiments.protocol / interventions.dosing
  baseline_period jsonb not null default '{}'::jsonb,
  active_period jsonb not null default '{}'::jsonb,
  washout jsonb,
  evaluation_window jsonb,
  expected_response_direction text check (expected_response_direction in ('increase', 'decrease', 'stabilize')),
  required_measurements text[] not null default '{}',
  optional_measurements text[] not null default '{}',
  -- 08.7's adaptive rules populate/consume this; free-form notes until then
  adjustment_rules jsonb,
  source text,
  version integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index protocols_user_lifecycle_idx on public.protocols (user_id, lifecycle_state);

create table public.protocol_interventions (
  id bigint generated always as identity primary key,
  protocol_id uuid not null references public.protocols (id) on delete cascade,
  intervention_id uuid not null references public.interventions (id) on delete cascade,
  role text not null default 'primary' check (role in ('primary', 'supporting', 'concurrent')),
  -- steps sharing a sequence_order run concurrently; see generateSchedule in protocol.ts
  sequence_order integer not null default 0,
  dose_frequency_timing_override jsonb,
  prerequisites jsonb,
  added_at timestamptz not null default now(),
  unique (protocol_id, intervention_id)
);

create index protocol_interventions_protocol_idx on public.protocol_interventions (protocol_id, sequence_order);

create table public.protocol_state_history (
  id bigint generated always as identity primary key,
  protocol_id uuid not null references public.protocols (id) on delete cascade,
  from_state text,
  to_state text not null,
  changed_at timestamptz not null default now()
);

create index protocol_state_history_protocol_idx on public.protocol_state_history (protocol_id, changed_at);

create or replace function public.protocols_log_lifecycle_change()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.lifecycle_state is distinct from old.lifecycle_state then
    insert into public.protocol_state_history (protocol_id, from_state, to_state)
    values (new.id, old.lifecycle_state, new.lifecycle_state);
    new.version := old.version + 1;
  end if;
  new.updated_at := now();
  return new;
end;
$$;

create trigger protocols_log_lifecycle_change before update on public.protocols
  for each row execute function public.protocols_log_lifecycle_change();

alter table public.protocols enable row level security;

create policy protocols_select_own on public.protocols for select to authenticated using (user_id = (select auth.uid()));
create policy protocols_insert_own on public.protocols for insert to authenticated with check (user_id = (select auth.uid()));
create policy protocols_update_own on public.protocols for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy protocols_delete_own on public.protocols for delete to authenticated using (user_id = (select auth.uid()));

alter table public.protocol_interventions enable row level security;

-- Scoped via the parent protocol's ownership (this table has no user_id of its own); insert also requires the
-- referenced intervention to belong to the same user, so a protocol can never be built out of someone else's intervention.
create policy protocol_interventions_select_own on public.protocol_interventions for select to authenticated
  using (exists (select 1 from public.protocols p where p.id = protocol_interventions.protocol_id and p.user_id = (select auth.uid())));

create policy protocol_interventions_insert_own on public.protocol_interventions for insert to authenticated
  with check (
    exists (select 1 from public.protocols p where p.id = protocol_interventions.protocol_id and p.user_id = (select auth.uid()))
    and exists (select 1 from public.interventions i where i.id = protocol_interventions.intervention_id and i.user_id = (select auth.uid()))
  );

create policy protocol_interventions_update_own on public.protocol_interventions for update to authenticated
  using (exists (select 1 from public.protocols p where p.id = protocol_interventions.protocol_id and p.user_id = (select auth.uid())))
  with check (exists (select 1 from public.protocols p where p.id = protocol_interventions.protocol_id and p.user_id = (select auth.uid())));

create policy protocol_interventions_delete_own on public.protocol_interventions for delete to authenticated
  using (exists (select 1 from public.protocols p where p.id = protocol_interventions.protocol_id and p.user_id = (select auth.uid())));

alter table public.protocol_state_history enable row level security;

create policy protocol_state_history_select_own on public.protocol_state_history for select to authenticated
  using (exists (select 1 from public.protocols p where p.id = protocol_state_history.protocol_id and p.user_id = (select auth.uid())));

create policy protocol_state_history_insert_own on public.protocol_state_history for insert to authenticated
  with check (exists (select 1 from public.protocols p where p.id = protocol_state_history.protocol_id and p.user_id = (select auth.uid())));
