# Meal profile

Every meal gets a set of separate indexes. There is deliberately no combined "meal score": the owner asked for the indexes
to stay apart, because one number hides why a meal is the way it is.

## Where it lives

- `public.meal_profiles` (view, `security_invoker`): one row per meal, computed on read from `meals` and `meal_items`, so
  an edit shows at once and nothing needs a backfill. Migration `*_meal_profiles.sql`; tests in
  `supabase/tests/meal_profiles.pglite.mjs`.
- `public.food_group_rules`: the shared keyword rules that give each ingredient a food group (first group rule by
  priority) and gut-relevant tags (every matching rule). `unless_pattern` stops a rule (soy milk is not dairy).
- Phone: the Log's entry sheet for a meal shows MEAL PROFILE (`ui/screens/MealProfileBlock.kt`, words and bands in
  `domain/fuel/MealProfile.kt`).
- Weekly and monthly reports: MEAL QUALITY (`mealQuality` in `analysis-core/periodReport.ts`).

## Ingredients

A meal's ingredients are its items' database matches (`meal_items.cronometer_items`: food, grams, kcal). An item
without that breakdown counts as one ingredient (its description and quantity in grams), and a meal without items as one
(its description, no grams).

## Indexes

| Index | Definition | Bands (phone) |
| --- | --- | --- |
| Energy density | food kcal / food grams, beverages left out; only when every food ingredient has grams (>= 30 g) | < 1.5 light, 1.5-4 medium, > 4 dense (Rolls) |
| Protein | grams, and g per 100 kcal; the dose is 0.3 g/kg (25 g without a weight) | full / partial (>= half) / little |
| Carb density | carbs g per 100 kcal | < 26% of kcal low, <= 55% moderate, more high |
| Fibre | g per 1,000 kcal | >= 14 high, >= 7 some, less low |
| Glycemic load (est.) | (carbs - fibre) x GI / 100; GI is the kcal-weighted mean of the ingredients' typical GI (International Tables of GI, Atkinson 2021) | <= 10 low, < 20 medium, more high |
| Fullness (est.) | Fullness Factor per 100 g (0.5-5) from kcal, protein, fibre and fat; meals without drinks and with grams only | < 2 less filling, <= 3 average, more filling |
| Ultra-processed | share of the ingredients' kcal in sweets, salty snacks, sugary drinks and processed meat | shown when above 0 |

`macro_mismatch` flags a meal whose kcal is more than 35% away from 4 x protein + 4 x carbs + 9 x fat + 7 x alcohol (or
under 20 kcal with over 100 kcal of macros): the phone asks for a check.

Any index whose inputs are missing is null, never guessed.

## Tags

gluten, lactose, dairy, high_fodmap, fried, spicy, sweetener, caffeine, alcohol, ultra_processed, high_fat (30 g of fat or
more). They are keyword estimates for later gut analysis (food groups against stool), not a diagnosis.

The profile also shows on the console's Log, under each meal: each index in its own hue, then the food groups and gut tags.
