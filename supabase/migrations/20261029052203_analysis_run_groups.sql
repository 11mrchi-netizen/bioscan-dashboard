-- The nightly analysis run, split into groups of engines, one request each (supabase/functions/_shared/analysis-core/
-- runGroups.ts). A single request running every engine for every user was killed with HTTP 546 "CPU Time exceeded" after
-- ~10-12 s, so the health engine (last in order) never finished. Each group is its own invocation with its own CPU budget,
-- on the same endpoint with the same Vault secret.
--
-- Order matters: `bands` (typicality, expected bands, evidence) restarts baselines at the series breaks `core` writes, so the
-- jobs are staggered a few minutes apart in dependency order. `private.run_analysis()` with no argument still means
-- every engine in one request (group 'all'), as before.
-- pg_net keeps the response for 60 s instead of 5 s, so net._http_response shows status and per-engine timings.
-- Rollback: select cron.unschedule('analysis-run-bands'); ... ('analysis-run-interventions'), ('analysis-run-fitness'),
--           ('analysis-run-health'); select cron.schedule('analysis-run-nightly', '30 20 * * *', 'select private.run_analysis()');
--           and restore the zero-argument function from 20261012_analysis_cron_secret.sql.

drop function if exists private.run_analysis();

create or replace function private.run_analysis(p_group text default 'all')
returns void
language plpgsql
security definer
set search_path to 'public', 'extensions'
as $$
declare
  cron_secret text;
begin
  if p_group not in ('all', 'core', 'bands', 'interventions', 'fitness', 'health') then
    raise exception 'unknown analysis group %', p_group;
  end if;
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
    body := jsonb_build_object('action', 'run', 'group', p_group),
    timeout_milliseconds := 60000
  );
end;
$$;

-- core keeps the existing job name and time; the other groups follow it
select cron.schedule('analysis-run-nightly', '30 20 * * *', $$select private.run_analysis('core')$$);
select cron.schedule('analysis-run-bands', '33 20 * * *', $$select private.run_analysis('bands')$$);
select cron.schedule('analysis-run-interventions', '36 20 * * *', $$select private.run_analysis('interventions')$$);
select cron.schedule('analysis-run-fitness', '39 20 * * *', $$select private.run_analysis('fitness')$$);
select cron.schedule('analysis-run-health', '42 20 * * *', $$select private.run_analysis('health')$$);
