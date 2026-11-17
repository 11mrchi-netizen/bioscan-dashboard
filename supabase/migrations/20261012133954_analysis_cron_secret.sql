-- Analysis Layer 3: scheduler authentication that needs no human-held secret.
-- (The first version of private.run_analysis() read `service_role_key` from Vault, but the Vault is empty on this project:
-- the existing reminder cron jobs never noticed because they have no push subscribers to send to.)
--
-- A random secret is generated inside Vault; private.run_analysis() sends it in the x-analysis-cron header together with the
-- public anon key (which only gets the request past the Edge Function gateway). analysis-run asks the service role-only
-- function public.analysis_cron_secret_ok() whether the header matches. The secret is never returned to any client.
-- Rollback: select cron.unschedule('analysis-run-nightly'); drop function private.run_analysis();
--           drop function public.analysis_cron_secret_ok(text); delete from vault.secrets where name = 'analysis_cron_secret';

select vault.create_secret(
  replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''),
  'analysis_cron_secret',
  'Authenticates the nightly pg_cron call to the analysis-run Edge Function'
);

create or replace function public.analysis_cron_secret_ok(candidate text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select candidate is not null and exists (
    select 1 from vault.decrypted_secrets where name = 'analysis_cron_secret' and decrypted_secret = candidate
  );
$$;

revoke all on function public.analysis_cron_secret_ok(text) from public, anon, authenticated;
grant execute on function public.analysis_cron_secret_ok(text) to service_role;

create or replace function private.run_analysis()
returns void
language plpgsql
security definer
set search_path to 'public', 'extensions'
as $$
declare
  cron_secret text;
begin
  select decrypted_secret into cron_secret from vault.decrypted_secrets where name = 'analysis_cron_secret';
  if cron_secret is null then
    raise notice 'analysis_cron_secret not found in vault, skipping analysis run';
    return;
  end if;

  perform net.http_post(
    url := 'https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1/analysis-run',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      -- public anon key: only satisfies the gateway's JWT check, grants no access by itself
      'Authorization', 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVnZnJnbGJjb2l2a3ByanF2anp6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkxMDYxOTAsImV4cCI6MjEwNDY4MjE5MH0.0G_5XnBiIsUTQG7vlSTARND8apI-mI6RdUytCSWY9To',
      'x-analysis-cron', cron_secret
    ),
    body := jsonb_build_object('action', 'run')
  );
end;
$$;
