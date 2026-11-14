-- A logged meal's label: which planned meal it was (breakfast, lunch, snack, dinner, pre-session, recovery). The logging
-- sheets pre-select it (domain/fuel/MealSuggestions.kt suggestMealSlot: a late breakfast stays breakfast) and the person
-- can change it. The fuel plan, its adherence history and the food habits read it first; meals without one (logged
-- before this) keep being placed by time.
--
-- Rollback: alter table public.meals drop column meal_slot;

alter table public.meals
  add column if not exists meal_slot text
    check (meal_slot is null or meal_slot in ('breakfast', 'lunch', 'snack', 'dinner', 'pre_session', 'recovery'));
