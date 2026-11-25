-- Audit, 25 Nov 2026 (docs: the performance and bug audit).
--
-- 1. v_conditioning_drills and v_training_block_metrics ran as their owner (SECURITY DEFINER views) with SELECT granted
--    to anon, so the public key alone read every user's rows past row level security. They now run as the caller
--    (security_invoker), so the RLS of exercise_sessions, training_cycles and wearable_daily applies, and anon loses
--    SELECT. The phone and the agent read them signed in, and still see their own rows.
-- 2. Four SECURITY DEFINER functions were callable through /rest/v1/rpc by anon and authenticated. Three are trigger
--    functions and compute_run_best_efforts is only called from one of them (which runs as the owner), so nobody needs
--    EXECUTE on them directly.
-- 3. 31 RLS policies called auth.uid() once per row; wrapped as (select auth.uid()) it is evaluated once per statement.
--    Same rule, same result.
-- 4. arousal_daily had two identical unique indexes on (user_id, date); the constraint's own index stays.
--
-- Rollback: alter view ... reset (security_invoker); grant select on both views to anon; grant execute on the four
--           functions to anon, authenticated; the policies keep their meaning either way; recreate
--           arousal_daily_user_date_unique if wanted.

alter view public.v_conditioning_drills set (security_invoker = true);
alter view public.v_training_block_metrics set (security_invoker = true);
revoke all on public.v_conditioning_drills from anon;
revoke all on public.v_training_block_metrics from anon;

revoke execute on function public.compute_run_best_efforts(bigint) from public, anon, authenticated;
revoke execute on function public.record_supplement_change() from public, anon, authenticated;
revoke execute on function public.zepp_workout_best_efforts_trigger() from public, anon, authenticated;
revoke execute on function public.zepp_workout_gear_fill() from public, anon, authenticated;

do $$
declare
  p record;
  q text;
  c text;
begin
  for p in
    select schemaname, tablename, policyname, qual, with_check
    from pg_policies
    where schemaname = 'public'
      and ((qual ~ 'auth\.uid\(\)' and qual !~* 'select auth\.uid\(\)')
        or (with_check ~ 'auth\.uid\(\)' and with_check !~* 'select auth\.uid\(\)'))
  loop
    q := case when p.qual is null then null else replace(p.qual, 'auth.uid()', '(select auth.uid())') end;
    c := case when p.with_check is null then null else replace(p.with_check, 'auth.uid()', '(select auth.uid())') end;
    execute format('alter policy %I on %I.%I', p.policyname, p.schemaname, p.tablename)
      || coalesce(' using (' || q || ')', '')
      || coalesce(' with check (' || c || ')', '');
  end loop;
end $$;

do $$
begin
  if exists (select 1 from pg_indexes where schemaname = 'public' and indexname = 'arousal_daily_user_date_unique')
     and exists (select 1 from pg_indexes where schemaname = 'public' and indexname = 'arousal_daily_user_id_date_key') then
    if exists (select 1 from pg_constraint where conname = 'arousal_daily_user_date_unique') then
      alter table public.arousal_daily drop constraint arousal_daily_user_date_unique;
    else
      drop index public.arousal_daily_user_date_unique;
    end if;
  end if;
end $$;
