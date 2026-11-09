-- Fix: RLS policies re-evaluating auth.uid() per row instead of once per
-- query. Drop and recreate every policy across all 22 tables using
-- (select auth.uid()) instead of bare auth.uid() — Postgres can cache the
-- subquery result once per statement this way. Also add covering indexes
-- on every user_id foreign key (and the other real FK on encounters/lab_results)
-- flagged by the performance advisor.

do $$
declare
  t text;
  tables text[] := array[
    'injuries','supplements','lab_draws','lab_results','sync_log',
    'wearable_daily','sleep_daily','wellbeing_daily','hydration_daily','body_metrics',
    'meals','recovery_sessions','rest_days','runs','arousal_daily','masturbation_log',
    'illnesses','people','encounters',
    'stool_log','clothing_items','laundry_loads'
  ];
begin
  foreach t in array tables loop
    execute format('drop policy if exists "select own %1$s" on %1$I', t);
    execute format('drop policy if exists "insert own %1$s" on %1$I', t);
    execute format('drop policy if exists "update own %1$s" on %1$I', t);
    execute format('drop policy if exists "delete own %1$s" on %1$I', t);

    execute format('create policy "select own %1$s" on %1$I for select using ((select auth.uid()) = user_id)', t);
    execute format('create policy "insert own %1$s" on %1$I for insert with check ((select auth.uid()) = user_id)', t);
    execute format('create policy "update own %1$s" on %1$I for update using ((select auth.uid()) = user_id)', t);
    execute format('create policy "delete own %1$s" on %1$I for delete using ((select auth.uid()) = user_id)', t);

    execute format('create index if not exists %1$I on %2$I (user_id)', t || '_user_id_idx', t);
  end loop;
end $$;

-- the two non-user_id foreign keys flagged separately
create index if not exists encounters_person_id_idx on encounters (person_id);
create index if not exists lab_results_draw_id_idx on lab_results (draw_id);
