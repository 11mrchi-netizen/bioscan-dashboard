# Monthly body check-in

Grip strength, tape girths and self-measured skinfolds in one sitting, once a month, so one month can be compared with
the next. Built in the app (Fuel › Body › BODY MEASUREMENTS, or + › BODY › Body check-in); a reminder on Saturday at
09:30 fires once four weeks have passed since the last check-in.

## Why these choices

The aim is change over time in one person, measured by that person. What limits that is not the tape or the caliper but
**repeatability**: finding the same landmark, at the same tension, in the same state, every month. Every rule below
serves that.

### Conditions (recorded with each check-in)

Morning, after the toilet, before eating, drinking or training, in underwear. Fasted and before-training are asked and
stored, so a month taken under other conditions can be read with that in mind. Food and fluid move the waist by about a
centimetre; that is the size of a real month-to-month change.

### Tape girths

Non-stretch tape (a spring-loaded tape such as a MyoTape keeps the tension the same); level all round; snug without
compressing the skin; trunk sites at the end of a normal breath out. Landmarks follow ISAK, adapted to measuring
oneself:

| Site | Landmark | Sides |
|---|---|---|
| Neck | just below the Adam's apple, tape sloping slightly down to the front | – |
| Shoulders | widest point over the deltoids, arms relaxed; mirror or helper to keep it level | – |
| Chest | under the armpits, across the nipples | – |
| Waist at the navel | level with the navel, belly relaxed | – |
| Waist, mid (WHO) | halfway between the lowest rib and the top of the hip bone; the waist the ratios use | – |
| Hips | widest part of the buttocks, feet together | – |
| Upper arm, relaxed | halfway between shoulder tip and elbow, arm hanging | right, left |
| Upper arm, flexed (optional) | around the peak, arm raised to the side, biceps tensed | right, left |
| Forearm | widest part below the elbow, palm up | right, left |
| Thigh | standing, halfway between hip crease and top of kneecap | right, left |
| Calf | standing, widest part | right, left |

Two readings per site; a third when they differ by more than 0.5 cm. The value is the median.

### Skinfolds, by oneself

Of the usual sites only some can be pinched reliably on oneself, with one hand holding the fold and the other the
caliper. The check-in uses three, on the **right** side (the side the published equations use; one side every month
is what matters):

- **Abdominal**: vertical fold 2 cm right of the navel.
- **Suprailiac**: diagonal fold just above the top of the hip bone, in line with the front of the armpit.
- **Front thigh**: vertical fold halfway between the hip crease and the top of the kneecap, **seated** with the knee at
  a right angle (standing on one leg while pinching is not repeatable alone).
- **Chest** (optional): diagonal fold halfway between the front of the armpit and the nipple; with it, men get the
  Jackson-Pollock three-site estimate.

The **triceps** is left out on purpose: it cannot be pinched on one's own arm with the other hand holding a caliper.
That is why the women's three-site equation (which needs it) is not offered.

Technique: pinch 1 cm above the mark, jaws on the mark, read about 2 seconds after letting go of the trigger while still
holding the fold. Readings are taken in **rounds** (one reading at each site, then the next round), so a fold is never
pinched twice in a row while still compressed; three rounds, and a fourth reading at a site whose three span more than
2 mm. The value is the median. The **caliper** is recorded: a sum taken with a different caliper is not compared.

The **sum of the three** is the skinfold number to follow. Body-fat percentages from skinfolds or the tape are
population equations, typically a few points off for one person; they are shown as labelled estimates next to the
scale's impedance reading, never as a better truth, and the advice is to follow one method's trend.

## Is a change real?

Each site's **typical error** (Hopkins 2000) comes from the repeats: the pooled within-sitting standard deviation of
single readings across every check-in, divided by the root of the readings behind the value. Within-sitting repeats miss
the error of finding the landmark again on another day, so the error is never taken as smaller than a floor (0.5 cm for
the tape, 1 mm for a skinfold). A change between two check-ins, and a left-right difference, is called UP or DOWN only
when larger than **2.77 typical errors** (the smallest real difference at 95% for two values each with that error);
otherwise it is WITHIN ERROR. The skinfold sum's error is the three sites' errors added in quadrature.

## Derived numbers

- **Waist-to-height** (waist mid, else navel, over height): under 0.5 is the boundary the evidence supports (Ashwell
  2012). Height comes from Health Connect, or is asked once at the check-in. It also feeds the Healthspan body
  composition factor, in place of a lab waist when the tape is newer.
- **Waist-to-hip**.
- **US Navy body fat** (Hodgdon and Beckett 1984): men from waist at the navel, neck and height; women from waist, hips,
  neck and height. Needs sex in the profile.
- **Jackson-Pollock three-site body fat** (1978, men, chest + abdominal + thigh + age), through Siri's equation.

## Data

`supabase/migrations/20261019_body_checkins.sql`: `body_checkins` (one row per sitting: time, fasted, before training,
caliper, notes) and `body_measurements` (one row per site and side, every reading kept, the median as `value`; tape in
cm, skinfolds in mm; plausibility and consistency checks in the table). Grip readings of the sitting go to
`grip_strength_log` with its `checkin_id`. Own rows only; a measurement can only point at its owner's check-in. Code:
`domain/BodyCheckin.kt` (rules, error model, equations; `BodyCheckinTest`), `data/BodyCheckinRepository.kt`,
`ui/screens/status/BodyCheckInSheet.kt`, `BodyMeasurementsCard.kt`. Registry entries: `grip_strength`, `body_girths`,
`skinfold_sum`, `waist_to_height`, `body_fat_estimates`.
