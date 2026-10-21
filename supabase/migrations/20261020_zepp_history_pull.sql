-- The whole Zepp history, pulled on the server (supabase/functions/zepp-extract/historyPull.ts). Stored Zepp data
-- started on 26 Aug 2026; Zepp holds more than a year before that, and Health Connect's copy of the older runs is about
-- twice their real distance. No phone is needed: the Zepp token, user id and host are already Edge Function secrets.
--
-- 1. zepp_history_pull: one row per person while a pull is under way or done: the next day to pull back from, what
--    has been found, an error that pauses it. Inserting a row (or clearing its error) starts or resumes the pull; the
--    app shows the row in Settings and can start or resume it. Own rows only.
-- 2. The scheduler authenticates like the nightly analysis (20261012_analysis_cron_secret.sql): a random secret that
--    lives only in Vault, checked by a service role-only function, with the public anon key only to pass the gateway.
-- 3. public.claim_zepp_history_pull(): marks the pending pulls as running (a four-minute lease, longer than one
--    function run) and returns them, so overlapping calls never work on the same pull.
-- 4. pg_cron calls private.run_zepp_history() every minute; it posts to zepp-extract only while a pull is pending,
--    and is a no-op otherwise.
--
-- Rollback: select cron.unschedule('zepp-history-pull'); drop function private.run_zepp_history();
--           drop function public.claim_zepp_history_pull(); drop function public.zepp_history_secret_ok(text);
--           delete from vault.secrets where name = 'zepp_history_cron_secret'; drop table public.zepp_history_pull;

create table public.zepp_history_pull (
  user_id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  -- the newest day of the next week to pull
  next_end date not null,
  empty_streak integer not null default 0 check (empty_streak >= 0),
  workouts integer not null default 0 check (workouts >= 0),
  band_days integer not null default 0 check (band_days >= 0),
  weeks_done integer not null default 0 check (weeks_done >= 0),
  -- first day of the oldest week that held anything
  oldest_with_data date,
  done boolean not null default false,
  -- set when a week failed (an expired Zepp token); the pull waits until it is cleared
  error text check (error is null or length(error) <= 500),
  running_since timestamptz,
  started_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.zepp_history_pull enable row level security;

create policy zepp_history_pull_select_own on public.zepp_history_pull for select to authenticated
  using (user_id = (select auth.uid()));
create policy zepp_history_pull_insert_own on public.zepp_history_pull for insert to authenticated
  with check (user_id = (select auth.uid()));
create policy zepp_history_pull_update_own on public.zepp_history_pull for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy zepp_history_pull_delete_own on public.zepp_history_pull for delete to authenticated
  using (user_id = (select auth.uid()));

select vault.create_secret(
  replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''),
  'zepp_history_cron_secret',
  'Authenticates the pg_cron call that drives the Zepp history pull in zepp-extract'
);

create or replace function public.zepp_history_secret_ok(candidate text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select candidate is not null and exists (
    select 1 from vault.decrypted_secrets where name = 'zepp_history_cron_secret' and decrypted_secret = candidate
  );
$$;

revoke all on function public.zepp_history_secret_ok(text) from public, anon, authenticated;
grant execute on function public.zepp_history_secret_ok(text) to service_role;

create or replace function public.claim_zepp_history_pull()
returns setof public.zepp_history_pull
language sql
volatile
security definer
set search_path = ''
as $$
  update public.zepp_history_pull
  set running_since = now()
  where not done
    and error is null
    and (running_since is null or running_since < now() - interval '4 minutes')
  returning *;
$$;

revoke all on function public.claim_zepp_history_pull() from public, anon, authenticated;
grant execute on function public.claim_zepp_history_pull() to service_role;

create or replace function private.run_zepp_history()
returns void
language plpgsql
security definer
set search_path to 'public', 'extensions'
as $$
declare
  cron_secret text;
begin
  if not exists (
    select 1 from public.zepp_history_pull
    where not done and error is null and (running_since is null or running_since < now() - interval '4 minutes')
  ) then
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
      'x-zepp-history', cron_secret
    ),
    body := '{}'::jsonb
  );
end;
$$;

select cron.schedule('zepp-history-pull', '* * * * *', 'select private.run_zepp_history()');
