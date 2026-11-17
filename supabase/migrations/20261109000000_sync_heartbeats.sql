-- Analysis hub phase C (docs/analysis-console/PLAN.md): the Health Connect and Zepp syncs record a collector heartbeat
-- after each successful run (android-app data/SyncHeartbeat.kt), so the Integrity workspace can tell a sync that ran and
-- found nothing new from one that has not run. collector_heartbeats (20261104_routine_capture.sql) allowed only the
-- location and phone-use collectors.
--
-- Rollback: delete from public.collector_heartbeats where stream in ('health_connect_sync', 'zepp_sync');
--   then restore the check with only 'location' and 'phone_usage'.

alter table public.collector_heartbeats drop constraint collector_heartbeats_stream_check;
alter table public.collector_heartbeats add constraint collector_heartbeats_stream_check
  check (stream in ('location', 'phone_usage', 'health_connect_sync', 'zepp_sync'));
