-- Endurance Fueling & Personal Experiments, Phase 06.3.3: widen nutrient_intake's source_type CHECK to allow
-- 'fueling_intake' alongside the existing 'meal_item'/'supplement' -- additive only, existing rows are unaffected.
-- Discovered live (not in any committed migration) while verifying fueling_intake_log_sync_nutrient_intake: the
-- trigger's insert was rejected by this constraint before this fix.
-- Rollback: alter table public.nutrient_intake drop constraint nutrient_intake_source_type_check;
--           alter table public.nutrient_intake add constraint nutrient_intake_source_type_check
--             check (source_type = any (array['meal_item', 'supplement']));

alter table public.nutrient_intake drop constraint nutrient_intake_source_type_check;
alter table public.nutrient_intake add constraint nutrient_intake_source_type_check
  check (source_type = any (array['meal_item', 'supplement', 'fueling_intake']));
