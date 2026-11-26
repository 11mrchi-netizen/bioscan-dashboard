# Food trigger screen for the gut

Idea 5 of the nutrition proposal. Code: `supabase/functions/_shared/analysis-core/foodTriggers.ts` (pure, tested in
`foodTriggers.test.ts`); read by `runPeriodReport.ts` into the weekly and monthly reports' **Food triggers** section,
shown on the console's LAB › REPORTS.

## What is compared

- **Unit:** a day with a stool, over the 180 days to the period's end. Stools on one day share their meals, so each day
  counts once. A day is *loose* when any stool is Bristol 6-7. Days with no meal logged in their window are left out.
- **Window:** from 48 h to 6 h before the day's first stool, roughly when a meal reaches the bowel.
- **Foods:** each food group and gut tag of `meal_profiles` (gluten, lactose, high FODMAP, fried, spicy, sweetener,
  caffeine, ultra-processed, high fat). Water and "other" are skipped.
- **Two ways:** a food in 20-80% of windows is compared as *with it against without*. A food in more than 80% (refined
  grains, for example) is compared as *more meals of it than the median against the rest*.

## When a food speaks

All of these must hold:

1. 20 days or more on each side, and 12 loose days or more in all;
2. a loose share at least 15 points higher with the food;
3. a one-sided Fisher exact p under 0.05;
4. a Benjamini-Hochberg q of 0.10 or less across every food tested, so that testing 30 foods does not throw up false alarms.

A food that passes is *worth testing*, never a diagnosis. The report suggests leaving it out for two weeks with a Lab
experiment, then bringing it back. The others show as *no signal* (tested) or *not enough days*.

## As of 25 Nov 2026

63 stool days, 14 loose. 17 foods had enough days and none passed. The closest was eggs: 31% loose with them against 13%
without, p 0.07, q 0.68. Foods eaten almost every day, such as caffeine, coffee and lactose, have too few days without them.
More months of logging, or a deliberate break from one food, give the screen its contrast.
