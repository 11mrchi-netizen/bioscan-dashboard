# Gut and hydration tracking: Throne and competitors, and what to build

**Date:** 2026-10-31 · **Status:** research and recommendation, nothing built

## Summary

1. **Throne's strength is presentation, not analysis.** It takes four plain questions (what agrees with me, what's changing,
   what's normal for me, am I hydrated) and answers each with one card. Your engine (`analysis-run`: pre-registration,
   false-discovery control, held-out scorecards) is statistically much stricter than anything Throne publishes. What you lack is the
   *front end*: Throne's four screens are the part worth copying.
2. **The most valuable single copy is the "Hurts / Helps" evidence list**, with a visible evidence tier on every row. The
   tier should come from your existing confirmatory/discovery split, not from a log count.
3. **Hydration is your real gap, and it is a data gap, not a visual one.** `hydration_daily` and `HydrationIntelligence`
   model *intake* (plus a sweat heuristic). Throne measures *output* (urine colour and visits). Nothing in the repo logs urine.
   A one-tap urine colour log closes most of that gap without any camera.
4. **On the photo layer: do not start with stool or urine photos.** Meal photos already exist. Recommendation and the
   privacy and validity reasoning are in [section 5](#5-the-photo-layer).
5. **Don't copy the opaque 0-100 score as-is.** Show its components and publish the formula (section 4.5).

## 0. Method and limits

- The seven screenshots you sent (Throne's marketing site on mobile) are the primary source for what Throne shows.
- **I could not open thronescience.com, support.thronescience.com or the news and competitor pages.** The session's network
  allowlist blocked those hosts, so everything external below comes from search-result summaries, not the pages
  themselves. Where sources disagree or only one vendor claims something, it is marked.
- Not researched: Cara Care, Bearable, ZOE, Monash FODMAP, Withings U-Scan app UX. The app-store results returned smaller
  apps only (GutCheck, Tract, GutSync, mySymptoms, WiseGut, GutSignal).
- Repo claims below come from reading `BristolEvaluation.kt`, `HydrationIntelligence.kt`, `nutrition-estimate-image`,
  `redFlags.ts`, `TopLevelTab.kt`, the personal-health-agent design and the ROADMAP. I did not run the app.

## 1. Throne: what it is

| | |
|---|---|
| Hardware | Clip-on sensor under the bowl rim, facing down. Camera plus computer vision reads stool and urine. |
| ID | Recognises the paired phone over Bluetooth in about 3 s, or an assigned button; up to 6 users per unit. |
| Captures | Bristol type, frequency, time of day, urine colour and volume or flow. |
| Price | $399 hardware plus about $5.99-6/month (per search summaries and the Throne page snippet). |
| Gut Health Score | 0-100%. Stool form and frequency over about 30 days, weighted to recent sessions, stool data only. |
| Hydration Score | From urine colour and flow, **last session only** ("a nudge you can act on today"). |
| Coach insights | Over 60 days; each factor shows "average gut health score change when logged", a log count and an *Established* or *Emerging* label. Uses the Journal. |
| Status | Wellness product, no FDA clearance, "not a medical device". Raised $10M in July 2026 (MedCity News, Tech Times headlines). |

**Weaknesses worth knowing**

- **Validation is company-run.** The one figure I found: its AI and five gastroenterologists classified the same 278 stool
  photos on the Bristol scale (presented at ACG 2026). No independent or peer-reviewed validation was found (Gut Health
  Times).
- **The labels are undefined.** I found no definition of "Established" versus "Emerging". The log counts in your
  screenshots (11 and 14 versus 8 and 9) suggest a count threshold, but that is my inference.
- **The effect numbers are unstable.** Two Throne snapshots show spicy food at -19% (11 logs) and -14% (9 logs). That is
  the noise you would expect from a raw before/after average at n≈10 with no adjustment.
- Urine colour is an incomplete hydration measure and stool appearance can't diagnose inflammatory disease, per Throne's
  own science overview as quoted by Gut Health Times.
- Dark or patterned bowls degrade sensing; a bathroom camera raises privacy objections even when the optics face only the bowl.

## 2. Competitors

| Product | Type | What it does | Notes |
|---|---|---|---|
| **Kohler Dekoda** | Clip-on camera | Stool and urine: gut-health and hydration signals, **blood detection**. Kohler Health app, trend alerts. | $599. Fingerprint unlock, end-to-end encryption claimed. Weak on dark bowls. Subscription is unclear: one source says from $6.99/month, another found none. |
| **Withings U-Scan** | Urine cartridge | Urine analysis, not stool. | Thin coverage in my results. Different category. |
| **Toi Labs TrueLoo** | Seat sensor | Senior-care monitoring for staff. | Not consumer. |
| **TOTO Stool Scan** | Smart toilet | Japan only, about $3,280. | Out of reach. |
| **Duke smart toilet group (MAT)** | Research | Microbiome activity test. | Research, not product. |
| **GutCheck** (app) | Manual diary | Spearman correlation computed on-device, ingredient icons overlaid on a timeline. | Closest software idea to yours. |
| **Tract** (app) | Manual diary | Flags ingredients linked to bad BMs and tries to separate triggers from coincidence. | |
| **GutSync** (app) | Manual diary | Elimination phase and reintroduction schedule, widget, emoji categories. | The only one that confirms triggers one food at a time. |
| **mySymptoms** (app) | Manual diary | Bristol logging plus food and symptom correlation. | |
| **WiseGut** (app) | Manual diary | Meal by photo, barcode or text. | Only photo-logging result found. |
| **GutSignal** (app) | Manual diary | Targets under 10 s per entry. | Speed is the selling point. |

**Pattern across the field.** Hardware players differentiate on passive capture and sensing. Software players differentiate
on correlation. Nobody I found combines passive capture, honest statistics and a stated confidence tier. Yours is the only
stack with the third part already built.

## 3. What your app already has

| Throne feature | Equivalent today | Gap |
|---|---|---|
| Bristol logging | `stool_log` (type, discomfort), one-tap reminders, `StoolForm` | Manual, per-event |
| Gut pattern | `BristolEvaluation`: 14-day window, minimum 8 entries, % hard / normal / loose, descriptive labels only | No visual like Throne's calendar grid or clock |
| Food/lifestyle to gut effects | Relationship Lab, confirmatory catalogue, case-crossover, nightly `analysis-run` | Stool outcomes are not surfaced as a simple Hurts/Helps list |
| Evidence tiers | Confirmatory vs exploratory; `Confidence(n, min)` | Not mapped to a user-facing chip |
| "Your normal" | Analytical Presentation Contract has a "Personal range and baseline" section; `RangeBar`, `ZonedScale`, `BandedGauge` | Not applied to stool or hydration |
| Hydration | `hydration_daily`, intake and sweat-rate model, `HydrationSourceDonutChart` | **No urine or output signal at all** |
| Journal | `notes`, symptom ticks on fuel debriefs | No symptom tags tied to the gut view |
| Coach | `health-agent` (EXPLAIN, WHY? cards), `redFlags.ts` already catches blood in stool or urine | Not wired to a digestive view |
| Photo capture | Gemini meal-photo estimate, photos downscaled and never stored | No photo history, nothing for stool or urine |

## 4. What to learn, and how to implement it

Each item is: what Throne does, what to do differently, and where it would live. File names are proposals.

### 4.1 Hurts / Helps list (priority 1)
*Throne:* diverging bars, factor, effect on gut score, evidence label, log count.
*Do:* read from `analysis-run` published results with stool outcomes (Bristol share in 3-5, daily stool count, discomfort).
Map tiers explicitly and show the rule in an info popover:
- **Established** = passes the confirmatory test (weekday-adjusted, family-corrected).
- **Emerging** = appears in discovery only, labelled exploratory.
- Neither = "not distinguishable yet" with the minimum detectable effect, not a percentage.

Never print a signed percentage for a row that has not cleared a tier. Throne's -19% from 11 logs is exactly what your
engine is built to refuse to say. Add the elimination/reintroduction idea from GutSync as the "test this" action on an
Emerging row, using the existing routine experiments (R6) machinery.
*Where:* Android Health tile, new Digestion view; console Evidence workspace for the detail.

### 4.2 "What's changing" with a marked change point (priority 2)
*Throne:* 60-day regularity line, a dashed vertical marker at a routine change, a shaded after-period band, plus a
journal annotation ("bloating logged on 6 days since").
*Do:* your Interventions and routine experiments already hold start dates. Plot stool regularity (rolling SD of
time-of-day, or daily count) with the intervention date as the marker and a before/after band. Say "within error" when the
change doesn't clear your typical-error rule (the body check-in doc already defines a 2.77 x typical-error test; reuse the idea).
Show symptom counts since the marker as an annotation, not as a causal claim.

### 4.3 "Your normal" (priority 2)
*Throne:* per-metric track with the personal range shaded and a dot; "Within your normal" or "Mild deviation".
*Do:* reuse `RangeBar` or `ZonedScale` and the contract's baseline rules. Metrics: stool frequency, modal Bristol type,
pee visits per day (needs 4.6), hydration. Use a robust range (median and IQR over 60 days) and show the n behind it.

### 4.4 Digestive pattern grid and 24-hour clock (priority 3, mostly visual)
*Throne:* a day-by-day column grid where each stool is a block coloured by class, anchored on a "healthy" centre line with
loose above and hard below; plus a 24 h polar plot where each stool is a thin slice.
*Do:* build one `DigestivePatternGrid` component on `FTStackedWeeklyBars` or `ActivityHeatmap` primitives, and a polar
clock (ECharts polar in the console, Compose canvas on Android). Keep the "healthy in the middle" layout because it reads
at a glance. Add shape or position as well as colour so it survives colour-blind use (check against the contract's Accessibility section).

### 4.5 Scores (priority 3, be careful)
Throne's Gut Health Score is stool-only and weights recency; its Hydration Score is the last session only, so the two
measure different time windows under similar-looking numbers.
*Do:* if you add a headline number, define it in the open. Suggested: recency-weighted share of stools in Bristol 3-5 over
30 days, adjusted for frequency within *your* personal range. Show the components under it. Keep the repo's rule that
labels are descriptive, never diagnostic (`BristolEvaluation` already states this).

### 4.6 Hydration from output (priority 1, data first)
Add a **urine colour log**: one tap on an 8-step scale (the Armstrong-style urine colour chart; check the exact
scale and thresholds against the source before shipping, I'm citing it from memory), with time. Optionally a "visits"
count. Then the Hydration tab can show *intake vs signal*: when intake looks fine and urine is dark, or the reverse, that
is information your model currently can't see. Reuse the one-tap reminder pattern already used for stool and arousal.
Schema: a per-event `urine_log` table mirroring `stool_log` (local time with offset, owner-only RLS).

### 4.7 Journal and symptom tags (priority 2)
Add quick symptom chips to the stool form (bloating, cramping, urgency, **blood**). Feed them to the engine as outcomes
and to the 4.2 annotation. A "blood" chip must route to the existing red-flag text and be excluded from trend scoring.
Dekoda sells blood detection as a feature; you can match the *safety behaviour* without the sensor.

### 4.8 Logging friction
Throne removes it with hardware; you can't. GutSignal's under-10-second goal is the target. Your one-tap "Normal (Bristol
4)" notification action is already close. Keep it, and add a home-screen widget as GutSync does.

### 4.9 Trust and honesty (differentiator)
Throne's weakest point is unexplained labels and company-only validation. Your design already leans the other way
(confidence counts, "not distinguishable", append-only ledger). Make that visible: every digestive card gets an info
popover with the rule, the n and the window.

## 5. The photo layer

I read "photo layer" two ways. Tell me which you meant; it changes the recommendation.

### 5a. Visual: glass data cards over lifestyle photography
Your fourth screenshot overlays a Digestive pattern card on a lifestyle photo. That's a marketing treatment.
**Recommendation: don't use photo backdrops behind charts in the app or console.** They lower contrast and compete with
the data. It does suit onboarding, empty states ("start logging to see this"), and shareable summary cards. I did not check
this against your Analytical Presentation Contract; read its accessibility rules before committing.

### 5b. Functional: photos as an input
| Photo of | Value | Risk | Verdict |
|---|---|---|---|
| **Meals** | Already built (Gemini, downscaled, never stored). A saved thumbnail would let you review a bad day's meals and fix mis-estimates later. | Storage and privacy of images | **Do next.** Optional, opt-in, thumbnail only; local-only or a private bucket with owner-only RLS. |
| **Urine colour card** | Photo next to a printed reference swatch, colour matched on-device, gives an objective reading. | Lighting and phone colour variance | **Later**, after the one-tap scale (4.6) shows you actually use urine data. |
| **Stool** | Would let a model suggest a Bristol type. | See below | **Not now.** |

Why not stool photos yet:
- **Validity.** Throne's own evidence is a company study of 278 photos with five gastroenterologists, with no independent
  validation found. A general vision model on handheld photos is a lower bar still. A mis-typed stool pollutes the
  Bristol evaluation you've carefully built.
- **Privacy.** Your meal pipeline sends images to Gemini under a free-tier key. Stool imagery is far more sensitive. Check
  the provider's data-use terms for your tier before sending anything like it (I haven't verified them).
- **Friction.** Photographing stool is slower than tapping a Bristol card, and speed of capture is the thing GutSignal
  optimises for.

If you want it later: on-device only, a reference photo kept beside the entry for your own re-checking, never uploaded, and
the Bristol type stays a human choice.

## 6. Suggested sequence

| Phase | Scope | Size (directional) |
|---|---|---|
| G1 | Urine colour log and reminder; symptom chips including blood; migration with RLS | small |
| G2 | Digestion view with "Your normal" and Hurts/Helps wired to existing engine results, tier chips with rules | medium |
| G3 | Pattern grid, 24 h clock, change-point trend with intervention markers | medium |
| G4 | Optional meal thumbnails; decide on urine-card photo | small to medium |
| G5 | Open-formula headline score, if still wanted | small |

Gates, in your repo's own style: do not show a Hurts/Helps row below the Emerging bar; show "building" with n until the
minimum (as `BristolEvaluation` does at 8 entries).

## 7. Open questions for the owner

1. Which meaning of "photo layer" (5a, 5b, both)?
2. Do you want a single headline gut score, or components only?
3. Are you willing to log urine colour at roughly the same cadence as stool?
4. Should digestion live in the Health tile or under Nutrition alongside Hydration?

## Sources

- [Throne: How it works](https://pages.thronescience.com/how-it-works), [What does Throne do](https://support.thronescience.com/en/articles/12773398-what-exactly-does-throne-do), [Throne One product page](https://thronescience.com/products/throne-one), [Is Throne a medical device](https://support.thronescience.com/en/articles/12773338-is-throne-a-medical-device-or-a-wellness-device) (as summarised by search; not opened)
- [Gut Health Times: what nobody has verified](https://guthealthtimes.org/throne-smart-toilet/)
- [MedCity News: $10M funding](https://medcitynews.com/2026/07/toilet-wearable-throne-science-funding/), [Tech Times](https://www.techtimes.com/articles/321835/20260728/ai-toilet-camera-raises-10m-track-gut-health-where-wearables-cannot-reach.htm), [KXAN](https://www.kxan.com/news/local/austin/austin-startup-uses-ai-powered-toilet-camera-to-track-gut-health-hydration/)
- [ConsumerAffairs on Kohler Dekoda](https://www.consumeraffairs.com/news/this-smart-toilet-has-a-cameraheres-what-it-tells-you-about-your-health-011626.html), [Yanko Design](https://www.yankodesign.com/?p=586233)
- [Wellworthy: smart toilets](https://wellworthy.com/smart-toilets-are-tracking-your-health-now/), [LangLeven](https://langleven.com/blog/smart-toilet-whats-the-urge), [Duke smart toilet lab](https://smarttoilet.pratt.duke.edu/research)
- App Store listings: [GutCheck](https://apps.apple.com/app/id6758055843), [GutSync](https://apps.apple.com/app/id6760943720), [mySymptoms](https://apps.apple.com/app/id405231632), [Tract](https://apps.apple.com/app/id6761203554)
