-- The daily calorie, macro and fluid targets, kept with the account instead of only on the phone. FUEL scores intake
-- against them (energy, protein, carbs and water ratios), so a reinstall or a second phone without them scored FUEL on
-- water alone and showed "No data" for the rest. The phone keeps its own copy (data/NutritionGoalsStore.kt) and syncs it
-- with this column on every open. Shape: the app's NutritionGoals (caloriesKcal, proteinG, carbsG, fatG, hydrationMinMl,
-- hydrationMaxMl, proteinPerKg, fatPct, activityLevel), any field absent when not set.
--
-- Rollback: alter table public.user_profile drop column nutrition_goals;

alter table public.user_profile add column if not exists nutrition_goals jsonb
  check (nutrition_goals is null or jsonb_typeof(nutrition_goals) = 'object');
