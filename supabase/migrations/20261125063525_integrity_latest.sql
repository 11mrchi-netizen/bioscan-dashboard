-- Integrity's collector and writer lists (console/src/workspaces/Integrity.tsx) took the newest 500 rows and kept the
-- latest per stream or writer, so a source that went quiet fell off the list instead of showing as stale (the Zepp sync
-- writes about 640 provenance rows a day). These views give one row per stream and per writer, read with the caller's
-- row level security.
-- Rollback: drop view public.collector_latest; drop view public.provenance_writer_latest;

create or replace view public.collector_latest with (security_invoker = true) as
  select distinct on (user_id, stream) user_id, stream, ts
  from public.collector_heartbeats
  order by user_id, stream, ts desc;

create or replace view public.provenance_writer_latest with (security_invoker = true) as
  select distinct on (user_id, writer, origin) user_id, writer, origin, written_at
  from public.daily_field_provenance
  order by user_id, writer, origin, written_at desc;

revoke all on public.collector_latest, public.provenance_writer_latest from anon;
grant select on public.collector_latest, public.provenance_writer_latest to authenticated;
