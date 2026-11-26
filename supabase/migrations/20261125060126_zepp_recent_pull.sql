-- An hourly server pull of the last few days of Zepp data (supabase/functions/zepp-extract/recentPull.ts). The Zepp app
-- uploads to the Zepp cloud in the background; until now Bioscan only read it when the app was opened. No phone is
-- needed: the Zepp token, user id and host are already Edge Function secrets, and the call authenticates with the
-- history pull's Vault secret (20261016033737_zepp_history_pull.sql), checked by zepp_history_secret_ok().
--
-- pg_cron calls private.run_zepp_recent() at minute 7 of every hour; it posts to zepp-extract with x-zepp-recent, which
-- re-reads today and the 3 days before it on the person's own calendar (daily_context.utc_offset_min).
--
-- Rollback: select cron.unschedule('zepp-recent-pull'); drop function private.run_zepp_recent();

create or replace function private.run_zepp_recent()
returns void
language plpgsql
security definer
set search_path to 'public', 'extensions'
as $$
declare
  cron_secret text;
begin
  if not exists (select 1 from public.zepp_sync_state) then
    return;
  end if;

  select decrypted_secret into cron_secret from vault.decrypted_secrets where name = 'zepp_history_cron_secret';
  if cron_secret is null then
    raise notice 'zepp_history_cron_secret not found in vault, skipping';
    return;
  end if;

  perform net.http_post(
    url := 'https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1/zepp-extract',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      -- public anon key: only satisfies the gateway's JWT check, grants no access by itself
      'Authorization', 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVnZnJnbGJjb2l2a3ByanF2anp6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkxMDYxOTAsImV4cCI6MjEwNDY4MjE5MH0.0G_5XnBiIsUTQG7vlSTARND8apI-mI6RdUytCSWY9To',
      'x-zepp-recent', cron_secret
    ),
    body := '{}'::jsonb
  );
end;
$$;

revoke all on function private.run_zepp_recent() from public;

select cron.schedule('zepp-recent-pull', '7 * * * *', 'select private.run_zepp_recent()');
