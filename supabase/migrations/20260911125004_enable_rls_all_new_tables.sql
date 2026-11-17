do $$
declare
  t text;
  tables text[] := array[
    'wearable_daily','sleep_daily','wellbeing_daily','hydration_daily','body_metrics',
    'meals','recovery_sessions','rest_days','runs','arousal_daily','masturbation_log',
    'illnesses','people','encounters',
    'stool_log','clothing_items','laundry_loads'
  ];
begin
  foreach t in array tables loop
    execute format('alter table %I enable row level security', t);
    execute format('create policy "select own %1$s" on %1$I for select using (auth.uid() = user_id)', t);
    execute format('create policy "insert own %1$s" on %1$I for insert with check (auth.uid() = user_id)', t);
    execute format('create policy "update own %1$s" on %1$I for update using (auth.uid() = user_id)', t);
    execute format('create policy "delete own %1$s" on %1$I for delete using (auth.uid() = user_id)', t);
  end loop;
end $$;
