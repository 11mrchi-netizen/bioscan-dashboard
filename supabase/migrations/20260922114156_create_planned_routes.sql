create table public.planned_routes (
    id bigint generated always as identity primary key,
    user_id uuid not null default auth.uid(),

    -- Calendar/Drive identity (DAV-145/146)
    calendar_id text not null default 'primary',
    calendar_event_id text not null,
    event_title text,
    event_start_time timestamptz not null,

    drive_file_id text,
    drive_source_url text,

    -- Cache/versioning (DAV-151): lets a caller decide whether to recompute
    -- without re-downloading, and represent a stale-but-still-shown preview
    -- after a failed refresh rather than showing nothing.
    gpx_checksum text,
    gpx_storage_path text,
    parser_version text,
    terrain_analysis_version text,
    is_stale boolean not null default false,
    last_error text,

    -- Course demand only (doc 03's "shared" metrics) -- never a performance
    -- metric, since nothing has happened yet to measure performance on.
    distance_m double precision,
    elevation_gain_m double precision,
    elevation_loss_m double precision,
    mountain_index double precision,
    km_effort double precision,
    grade_distribution jsonb,
    confidence_tier text,
    computed_at timestamptz,

    -- Planned vs observed stay explicitly separate tables; this is a plain
    -- reference for "which real session this was for," never a target for
    -- reconciliation math (DAV-150 was dropped for exactly this reason).
    exercise_session_id bigint references public.exercise_sessions(id) on delete set null,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    unique (user_id, calendar_event_id)
);

alter table public.planned_routes enable row level security;

create policy "select own planned_routes" on public.planned_routes for select using ((select auth.uid()) = user_id);
create policy "insert own planned_routes" on public.planned_routes for insert with check ((select auth.uid()) = user_id);
create policy "update own planned_routes" on public.planned_routes for update using ((select auth.uid()) = user_id);
create policy "delete own planned_routes" on public.planned_routes for delete using ((select auth.uid()) = user_id);

create index planned_routes_event_start_time_idx on public.planned_routes(event_start_time);
