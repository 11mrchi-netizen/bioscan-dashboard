-- SUPERSEDED IN PART by 20261012_analysis_cron_secret.sql: the function body below read `service_role_key` from Vault, which
-- is empty on this project. The job (`analysis-run-nightly`) created here is still the live one; private.run_analysis()
-- was replaced by the version in that later migration (random Vault secret + public.analysis_cron_secret_ok()).
--
-- Analysis Layer 3: nightly publisher run. Same pattern as private.send_daily_reminder (migration history of the push
-- reminders): pg_cron calls a SECURITY DEFINER function in schema `private`, which reads the project's service role key
-- from Vault and posts to the Edge Function with pg_net. analysis-run recognises the service role key as the scheduler
-- and runs for every user that has wearable data; no separate secret exists.
-- Rollback: select cron.unschedule('analysis-run-nightly'); drop function private.run_analysis();

create or replace function private.run_analysis()
returns void
language plpgsql
security definer
set search_path to 'public', 'extensions'
as $$
declare
  service_key text;
begin
  select decrypted_secret into service_key from vault.decrypted_secrets where name = 'service_role_key';
  if service_key is null then
    raise notice 'service_role_key not found in vault, skipping analysis run';
    return;
  end if;

  perform net.http_post(
    url := 'https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1/analysis-run',
    headers := jsonb_build_object('Content-Type', 'application/json', 'Authorization', 'Bearer ' || service_key),
    body := jsonb_build_object('action', 'run')
  );
end;
$$;

-- 20:30 UTC = 04:30 in UTC+8, before the morning sync and check-ins.
select cron.schedule('analysis-run-nightly', '30 20 * * *', 'select private.run_analysis()');
