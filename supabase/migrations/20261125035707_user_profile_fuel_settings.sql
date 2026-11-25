-- The phone's fuel settings (meal times, train low, the carbohydrate rate the gut tolerates, caffeine, salty sweat), kept with the
-- account instead of only on the phone, so the web console's fuel plan, race plans and long-session schedules use the same ones.
-- The phone keeps its own copy (data/FuelSettingsStore.kt) and syncs it with this column on every open, as it does nutrition_goals.
-- Shape: {breakfast, lunch, snack, dinner} as "HH:mm" (snack null: no afternoon snack), trainLow, toleratedGPerHour, caffeineOk,
-- caffeineMgPerKg, saltySweater; a key absent takes the default (console/src/lib/fuelSettings.ts). user_profile's own-row
-- policies already cover it.
--
-- Rollback: alter table public.user_profile drop column fuel_settings;

alter table public.user_profile add column if not exists fuel_settings jsonb
  check (fuel_settings is null or jsonb_typeof(fuel_settings) = 'object');
