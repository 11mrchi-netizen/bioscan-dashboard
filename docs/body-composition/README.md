# Body composition from the scale

What the weigh-in (weight + one impedance value, `scale_readings`) and the profile (height, date of birth, sex) can say.
Code: `android-app/.../domain/BodyComposition.kt` (pure, tested in `BodyCompositionTest`), `data/ScaleReadingRepository.kt`,
cards in `ui/screens/status/BodyCompositionCards.kt` (Health › BODY), preview in `ScaleWeighInSheet`.

## Inputs
| Input | Where |
|---|---|
| weight, impedance | `scale_readings` (raw hex kept, so history can be recomputed) |
| date of birth, sex | `user_profile` |
| height | `user_profile.height_cm`, else the newest `body_metrics.height_cm` (migration `20261113_user_profile_height.sql`) |
| athlete mode | `user_profile.athlete_mode`: only adds a caveat to the cards |

Composition is computed from the raw rows with the current profile, never stored as the source of truth.

## Equations (S = height² / impedance, height in cm, impedance in ohm, weight in kg)
Sun SS et al., Am J Clin Nutr 2003;77:331-340, [doi 10.1093/ajcn/77.2.331](https://doi.org/10.1093/ajcn/77.2.331), Table 5
(final race-combined equations, adults 12-94 y):

| | Equation | R² | RMSE |
|---|---|---|---|
| Male TBW (L) | 1.20 + 0.45 S + 0.18 W | 0.84 | 3.8 L |
| Female TBW (L) | 3.75 + 0.45 S + 0.11 W | 0.79 | 2.6 L |
| Male FFM (kg) | -10.68 + 0.65 S + 0.26 W + 0.02 R | 0.90 | 3.9 kg |
| Female FFM (kg) | -9.53 + 0.69 S + 0.17 W + 0.02 R | 0.83 | 2.9 kg |

Fat mass = weight - FFM; body fat % = fat mass / weight. Skeletal muscle: Janssen I et al., J Appl Physiol 2000;89:465-471,
[doi 10.1152/jappl.2000.89.2.465](https://doi.org/10.1152/jappl.2000.89.2.465): SM = S x 0.401 + 3.825 (men) - 0.071 x age + 5.102
(SEE 2.7 kg; underestimates in Asians). Resting energy: Katch-McArdle, 370 + 21.6 x FFM. Also BMI, the 18.5-25 weight range,
FFMI/FMI (FFM or fat mass / height²) and the model-free impedance index S.

Not derivable from this data and not shown: visceral fat, bone mass, "metabolic age" (vendor-defined), phase angle and
extracellular/intracellular water (need reactance or several frequencies).

## Quality gate
A weigh-in is used only when fat-free mass is below body weight, body fat is within 1-70 % (same range as
`body_metrics.body_fat_pct`) and body water is 60-80 % of fat-free mass (physiological ~73 %). Failing readings are kept in
`scale_readings` and the weight is still saved, but no composition is written.

## What is written to `body_metrics`
On SAVE, a reading that passes the gate upserts the day's row with `body_fat_pct`, `lean_mass_kg` (= fat-free mass) and
`source = 'scale_bia'` (weight as before). Those columns feed the body-fat blend and `fatFreeMass()` in the fuel planner.
`source` is one value per row, so the rule is per day: the Health Connect body-fat sync skips any date whose row has
`source = 'scale_bia'`, otherwise it would overwrite the scale estimate with its own value for the same day.
`ScaleReadingRepository.backfillBodyMetrics()` recomputes past days (not wired to any button yet).

## Limits to keep in mind
- One impedance number from a foot-to-foot scale is not the whole-body resistance the equations were fitted on: expect a level
  bias of a few points. The trend (and the model-free impedance index) is more reliable than any single value.
- Impedance moves with hydration, meals, training, skin temperature and time of day. Weigh at the same time, ideally fasted.
- Equations are population-based and less accurate in athletes; Janssen was fitted on whole-body hand-to-foot resistance.
- The impedance scale (0.1 ohm) and bytes 5-7 of the broadcast are still unverified against the scale's own app
  (`domain/ScaleBroadcast.kt`). Compare a few weigh-ins before trusting absolute values.
