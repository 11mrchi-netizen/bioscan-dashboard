-- benchmark_artifacts (DAV-193/196, docs/analysis-layer-2/25-population-benchmark-engine.md) was created on production
-- by a migration that never reached this folder. This mirrors the live definition (columns, checks, index, RLS and its one
-- select policy) so a fresh database can be rebuilt from the repo. On production every statement is a no-op.
-- It does not seed the existing VO2max and steps rows; the grip rows follow in 20261103_grip_strength_norms.sql.
-- Rollback (a fresh database only; on production this would also remove the seeded reference rows):
--   drop table public.benchmark_artifacts;

create table if not exists public.benchmark_artifacts (
  id bigint generated always as identity primary key,
  metric text not null,
  artifact_type text not null check (artifact_type in ('quantile_table', 'reference_curve', 'reference_equation', 'threshold_range')),
  source_name text not null,
  source_tier text not null check (source_tier in ('A', 'B', 'C', 'D')),
  -- {sex, age_low, age_high, geography, sampling_frame}
  population jsonb not null,
  measurement_protocol text,
  device_or_algorithm text,
  sample_size integer,
  -- percentile -> value, e.g. {"10": 38, "50": 51, "90": 64}
  quantiles jsonb,
  -- reference equation, or named threshold bands ({"bands": [{name, min, max}]})
  equation jsonb,
  directionality text not null check (directionality in ('HIGHER_BETTER', 'LOWER_BETTER', 'OPTIMAL_RANGE', 'TARGET_VALUE', 'NON_DIRECTIONAL')),
  supports_percentile boolean not null,
  access_license text not null,
  publication_date date,
  validity_start date,
  validity_end date,
  artifact_version integer not null default 1,
  superseded_by bigint references public.benchmark_artifacts (id),
  created_at timestamptz not null default now()
);

create index if not exists idx_benchmark_artifacts_metric on public.benchmark_artifacts (metric) where superseded_by is null;

-- Shared reference data: any signed-in user reads it, nobody writes it from the app; rows are added only by migration.
alter table public.benchmark_artifacts enable row level security;

do $$
begin
  if not exists (
    select 1 from pg_policies
    where schemaname = 'public' and tablename = 'benchmark_artifacts'
      and policyname = 'benchmark_artifacts are readable by any authenticated user'
  ) then
    create policy "benchmark_artifacts are readable by any authenticated user"
      on public.benchmark_artifacts for select to authenticated using (true);
  end if;
end $$;
