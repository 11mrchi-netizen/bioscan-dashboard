# Meal timing, composition and the gut (catalogue 2.3.0, gut rhythm and questions 2.4.0)

Step 2 of the nutrition proposal. The relationship engine (`analysis-core/relationship.ts`, nightly in analysis-run,
group `core`) gains meal-timing and food exposures and a new outcome group, DIGESTION. Pure code in
`analysis-core/mealExposures.ts`, wired in `buildFrame` (`runRelationships.ts`); questions in `hypotheses.ts`.

## Why

The only meal exposure was "a meal after 21:00". On the owner's data it was on 44 of 70 days, so it could hardly tell
days apart. Continuous timing (how long before sleep, how much) separates days much better.

## Exposures (per eating day; a meal before 04:00 belongs to the evening before)

| Series | Meaning |
| --- | --- |
| `meal_gap_h` | hours from the last meal of 50 kcal or more to sleep onset that night (sleep row of the next date; bedtime is a real instant, read with the day's UTC offset) |
| `kcal_before_bed` | kcal in the 3 h before sleep onset |
| `eating_window_h` | first to last meal of 50 kcal or more (two meals at least) |
| `protein_day`, `carbs_day`, `fat_day` | daily sums |
| `fiber_day` | daily fibre, only when 60% or more of the day's kcal carries a fibre value |

Days without meals are unknown, never zero.

## The daily digestion score (`gut_day_score`, 0-100)

The mean of what is known that day: each stool's form (Bristol 4 = 100, 3/5 = 90, 2 = 70, 6 = 60, 1 = 40, 7 = 30) less
3 points per point of discomfort and 10 / 20 for a partly / not complete evacuation, averaged over the day; and the evening
check-in's gut comfort x 25 less 10 per symptom. Days with neither are unknown.

Since 2.4.0 the stool part also counts the rhythm against your usual, once the 28 days before hold 7 days with stools:
10 points off per stool beyond one more than your usual daily count (the median of those days), and 5 per hour that the
day's first stool is more than 2 h from your usual first time (at most 15). The weekly and monthly reports show the score
with the share of normal and of loose (Bristol 6-7) stools.

## Questions

Sleep, HRV and scores: `meal_gap_sleep`, `meal_gap_hrv`, `meal_gap_recovery_score`, `kcal_before_bed_sleep_score`,
`kcal_before_bed_hrv`, `carbs_day_sleep`, `protein_day_recovery_score`.

Digestion (no-effect zone 5 points): next day's score against `fiber_gut` (per 10 g), `fat_gut` (per 30 g),
`kcal_before_bed_gut` (per 250 kcal), `alcohol_gut`; the same day's against `stress_gut`, `sleep_gut`, `training_gut`
(per 30 min), `travel_gut`, and since 2.4.0 `caffeine_gut` (a day with caffeine: an item with caffeine or a meal tagged
caffeine by its food groups), `long_session_gut` and `long_session_gut_next` (a session of 90 minutes or more, that day and
the next).

Food-group triggers (gluten, lactose, high FODMAP and so on from the meal profile) are not asked yet: there are too few
days with ingredients and a stool the next day (idea 5 of the proposal, from about February 2027).
