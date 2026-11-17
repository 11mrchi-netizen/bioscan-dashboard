-- Analysis Layer 3, Phase 0: versioned results published by the analysis-run Edge Function (docs/analysis-layer-3).
-- Written only by the service role; clients read their own rows. Convention matches the existing tables
-- (user_id default auth.uid() with a cascade FK, policies using the (select auth.uid()) initplan form).
-- Rollback: drop table public.analysis_results;

create table public.analysis_results (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  kind text not null,                 -- series_break | relationship | typicality | experiment | ...
  key text not null,                  -- e.g. 'hrv', 'alcohol->hrv@1', 'user'
  as_of date not null,                -- the data date the result describes
  engine_version text not null,       -- bump when the algorithm or its parameters change
  inputs_hash text,                   -- hash of the inputs; staleness = hash differs from current data
  state text,                         -- EvalState / ComparisonState name, mirrors the Kotlin contract
  confidence_have integer,            -- Confidence(have, need), docs/analysis-layer-2/05
  confidence_need integer,
  payload jsonb not null default '{}'::jsonb,
  calculated_at timestamptz not null default now(),
  unique (user_id, kind, key, as_of, engine_version)
);

create index analysis_results_user_kind_asof_idx on public.analysis_results (user_id, kind, as_of desc);

alter table public.analysis_results enable row level security;

create policy "select own analysis_results" on public.analysis_results
  for select to authenticated using ((select auth.uid()) = user_id);

-- No insert/update/delete policies on purpose: results are computed server-side with the service role.
comment on table public.analysis_results is 'Analysis Layer 3 published results (series breaks, relationships, typicality, experiments). Service-role writes, own-row reads.';
