create table public.calendar_sync_state (
    user_id uuid not null default auth.uid(),
    calendar_id text not null default 'primary',
    sync_token text,
    last_synced_at timestamptz,
    primary key (user_id, calendar_id)
);

alter table public.calendar_sync_state enable row level security;
create policy "select own calendar_sync_state" on public.calendar_sync_state for select using ((select auth.uid()) = user_id);
create policy "insert own calendar_sync_state" on public.calendar_sync_state for insert with check ((select auth.uid()) = user_id);
create policy "update own calendar_sync_state" on public.calendar_sync_state for update using ((select auth.uid()) = user_id);
create policy "delete own calendar_sync_state" on public.calendar_sync_state for delete using ((select auth.uid()) = user_id);

-- DAV-145. A local cache of upcoming events kept fresh via Calendar's real
-- incremental-sync protocol (syncToken), not a full re-download each time.
-- Only what DAV-146/148 actually need to resolve a trail-relevant GPX
-- attachment and hand a preview off downstream -- not a general-purpose
-- calendar mirror.
create table public.calendar_events (
    id bigint generated always as identity primary key,
    user_id uuid not null default auth.uid(),
    calendar_id text not null default 'primary',
    event_id text not null,
    title text,
    description text,
    start_time timestamptz,
    end_time timestamptz,
    color_id text,
    attachments jsonb,
    is_cancelled boolean not null default false,
    google_updated_at timestamptz,
    synced_at timestamptz not null default now(),

    unique (user_id, calendar_id, event_id)
);

alter table public.calendar_events enable row level security;
create policy "select own calendar_events" on public.calendar_events for select using ((select auth.uid()) = user_id);
create policy "insert own calendar_events" on public.calendar_events for insert with check ((select auth.uid()) = user_id);
create policy "update own calendar_events" on public.calendar_events for update using ((select auth.uid()) = user_id);
create policy "delete own calendar_events" on public.calendar_events for delete using ((select auth.uid()) = user_id);

create index calendar_events_start_time_idx on public.calendar_events(start_time) where not is_cancelled;
