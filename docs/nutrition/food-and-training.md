# Food against training

Step 3 of the nutrition proposal. Code: `supabase/functions/_shared/analysis-core/trainingFuel.ts` (pure, tested in
`trainingFuel.test.ts`); read by `runPeriodReport.ts` into the weekly and monthly reports' **Training fuel** section,
shown on the console's LAB › REPORTS.

## Per session

Sessions of 20 minutes or more from `exercise_sessions`; walks and `other` are left out.

| Field | Meaning |
|---|---|
| hours since meal | from the last meal of 50 kcal or more (within 18 h) to the start |
| fasted | no such meal in the 4 h before, and the last one at least 8 h before (or none in 18 h) |
| carbs before | carbs in the 4 h before (Thomas 2016: 1-4 g/kg in the 1-4 h before a key session) |
| carbs during | from `fueling_intake_log`, consumed or partial entries tied to the session; unknown without any |
| protein / carbs after | in the 2 h after the end (about 0.3 g/kg protein) |
| long | 90 minutes or more |

## Per day: energy availability

(intake − exercise energy) / fat-free mass, in kcal per kg FFM (Loucks). Exercise energy is the watch's active kcal of the
day's training sessions (walks are everyday movement, not exercise energy), which watches tend to overstate, so the value
is an estimate. Fat-free mass is the latest `body_metrics` weight with body fat on or before the day, within 60 days.
Only days with food logged count.

Bands: under 30 low (the RED-S line), 30-45 reduced, 45 or more adequate.

## Suggestions (fixed rules)

- **energy_low**: low on 2 or more days of a week (6 of a month): add a carb-rich meal or snack on long or double days.
- **long_fasted**: a long session started fasted with no fuel logged during: carbs before, or 30-60 g an hour during.
- **protein_after**: 3 or more sessions and fewer than half followed by the protein dose.

## Times

`meals.logged_at` and `exercise_sessions.start_time` are logged times (wall clock labelled +00), compared as they are.
