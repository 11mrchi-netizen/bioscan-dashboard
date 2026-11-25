-- Weekly and monthly reports (docs/reports/README.md): the nightly analysis run gains the group `reports`
-- (supabase/functions/_shared/analysis-core/runPeriodReport.ts), last at 20:48 UTC (04:48 in UTC+8), after the Lab engines
-- whose results it reads. Each night it writes the report of the last completed Monday-to-Sunday week and calendar month
-- when none is stored, and rewrites it for three days after the period ends. Reports are analysis_results rows of kind
-- `period_report`, so no table changes; the existing read policy of analysis_results covers them.
-- Rollback: select cron.unschedule('analysis-run-reports'); and restore the group list without 'reports'.

create or replace function private.run_analysis(p_group text default 'all')
returns void
language plpgsql
security definer
set search_path to 'public', 'extensions'
as $$
declare
  cron_secret text;
begin
  if p_group not in ('all', 'core', 'bands', 'interventions', 'fitness', 'health', 'exposures', 'reports') then
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

select cron.schedule('analysis-run-reports', '48 20 * * *', $$select private.run_analysis('reports')$$);
