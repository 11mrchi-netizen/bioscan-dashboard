
create table benchmark_artifacts (
  id bigint generated always as identity primary key,
  metric text not null,
  artifact_type text not null check (artifact_type in ('quantile_table', 'reference_curve', 'reference_equation', 'threshold_range')),
  source_name text not null,
  source_tier text not null check (source_tier in ('A', 'B', 'C', 'D')),
  population jsonb not null,
  measurement_protocol text,
  device_or_algorithm text,
  sample_size integer,
  quantiles jsonb,
  equation jsonb,
  directionality text not null check (directionality in ('HIGHER_BETTER', 'LOWER_BETTER', 'OPTIMAL_RANGE', 'TARGET_VALUE', 'NON_DIRECTIONAL')),
  supports_percentile boolean not null,
  access_license text not null,
  publication_date date,
  validity_start date,
  validity_end date,
  artifact_version integer not null default 1,
  superseded_by bigint references benchmark_artifacts(id),
  created_at timestamptz not null default now()
);

comment on table benchmark_artifacts is 'DAV-193. Shared reference data (not per-user) -- versioned benchmark artifacts (published quantile tables / reference curves / reference equations), never raw participant-level datasets. Read-only from the app; seeded via migration/research, matching exercise_library''s own precedent.';

alter table benchmark_artifacts enable row level security;

create policy "benchmark_artifacts are readable by any authenticated user"
  on benchmark_artifacts for select
  to authenticated
  using (true);

create index idx_benchmark_artifacts_metric on benchmark_artifacts (metric) where superseded_by is null;
