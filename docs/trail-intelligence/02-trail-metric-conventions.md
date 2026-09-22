# 02 — Comparing trail-metric conventions and choosing canonical variants

**Linear:** [DAV-129](https://linear.app/biodashboard/issue/DAV-129) · **Status:** researched, canonical choices made

## Method

For each metric: what real platforms/coaches/federations actually publish, where formulas genuinely
diverge, and which variant this app implements — favoring a published, transparent formula over a
proprietary black box wherever one exists, per this ticket's own "no proprietary score is copied
without a transparent rationale" rule.

## Mountain Index

**Formula, one real convention**: `elevation gain (m) / distance (km)`, in m/km — "elevation density."
A 50K with 3,000m of gain reads 60 m/km; a 100K with 4,000m reads 40 m/km — the same total climb
spread over more distance is a genuinely easier course. No material variant found; this matches the
Linear registry text's own "elevation gain / distance" exactly.
Source: [vert.run/mountain-index](https://vert.run/mountain-index/).

## KM-effort ("kilomètre-effort")

**Two real variants exist.** The hiking/tourism convention adds both directions:
`distance(km) + D+/100 + D-/400` (100m climbed ≈ 1 extra flat km; 400m descended ≈ 1 extra flat km).
The **official FFA/ITRA classification formula** — the one actually used to grade races and the one
this milestone's own registry text already specifies — uses only the climb:
`km-effort = distance(km) + elevation_gain(m)/100`. **Canonical choice: the FFA/ITRA formula** (no D-
term) — this is a running-race milestone, not a hiking-time estimator, and matches the ticket text
verbatim.
Source: [Passy Mont-Blanc: the effort kilometer](https://www.passy-mont-blanc.com/en/activites/balades-et-randonnees/tour-des-fiz/le-kilometre-effort/).

## VAM (vertical ascent meters per hour)

**One real convention**, borrowed from cycling (*velocità ascensionale media*, coined by Michele
Ferrari): `VAM = elevation_gain(m) / time(hours)`. No material platform divergence — every source
agrees on this formula; only the athletic context (comparing cycling-climb benchmarks) differs.
**Canonical choice: `elevation_gain(m) / time(hours)`**, computed per climb segment (DAV-138), not
just once per whole activity.
Source: [Strava: Vertical Ascent in Meters](https://support.strava.com/hc/en-us/articles/216917117-Vertical-Ascent-in-Meters-VAM).

## GAP / NGP (grade-adjusted / normalized graded pace)

**Genuinely proprietary and non-interchangeable**: Strava's GAP and TrainingPeaks' NGP are separate,
undisclosed calculations that don't produce matching numbers for the same activity. Both are built on
the same real, published physiological foundation, though: Minetti et al. (2002, *J Appl Physiol*)
measured oxygen cost across −45% to +45% grade and fit a 5th-order polynomial for the metabolic cost
of running per kilogram per meter:

```
EC(i) = 155.4·i⁵ − 30.4·i⁴ − 43.3·i³ + 46.3·i² + 19.5·i + 3.6   (J·kg⁻¹·m⁻¹, i = grade as a fraction)
```

**Canonical choice: implement the Minetti polynomial directly** — grade-adjusted pace = actual pace ÷
(EC(grade) / EC(0)) — rather than reverse-engineering either proprietary platform. This is the exact
"transparent rationale" DAV-129's acceptance criteria asks for: a real, citable, 20-plus-year-old peer
reviewed source, not a guess at Strava's internals.
Sources: [Minetti et al. 2002 (PDF)](http://runscribe.com/wp-content/uploads/power/Minetti2002.pdf) ·
[TrainingPeaks: What is NGP?](https://www.trainingpeaks.com/learn/articles/what-is-normalized-graded-pace/) ·
[Strava: Grade Adjusted Pace](https://support.strava.com/en-us/articles/15402117-grade-adjusted-pace-gap).

## Uphill/downhill efficiency

No single named industry-standard metric exists here the way VAM or KM-effort are standardized —
coaches variously define "efficiency" as vertical speed per heart rate, per %HR-reserve, or per power.
DAV-129's job is to document that this genuinely diverges, not to prematurely pick a formula that
belongs to DAV-141's own implementation scope; DAV-141 picks the specific denominator (HR first, power
once available) when it's built, with the choice and its threshold/profile dependency versioned there.

## Durability / degradation

Recent (2025) exercise-physiology literature gives this milestone's "durability" ticket (DAV-142) a
real, current vocabulary rather than an invented one: **durability** is the deterioration of
physiological markers (critical power, VO₂max, movement economy) over prolonged steady exercise,
distinct from **fatigability** (how fast an athlete "cracks" as workload accumulates). This app's
within-session early-vs-late segment comparison (pace/VAM/HR-drift degradation, matched terrain) is a
practical single-session proxy for durability in this sense, not a full physiological-marker battery —
DAV-142's own scope, unchanged by this doc, already asks for exactly that comparison.
Sources: [Durability, fatigability, repeatability, and resilience — J Appl Physiol 2025](https://journals.physiology.org/doi/full/10.1152/japplphysiol.00343.2025) ·
[Durability as an index of endurance exercise performance — Exp Physiol 2025](https://physoc.onlinelibrary.wiley.com/doi/full/10.1113/EP092120).

## Acceptance criteria, addressed

- **Sources and formulas recorded**: above, each with a citation.
- **Material differences identified**: KM-effort's two real variants (hiking vs. FFA/ITRA), GAP/NGP's
  proprietary divergence, uphill/downhill efficiency's lack of a standard.
- **One canonical implementation per metric, aliases preserved where useful**: chosen above for
  Mountain Index/KM-effort/VAM/GAP; uphill/downhill efficiency's choice is deferred to DAV-141 by name,
  not silently skipped.
- **No proprietary score copied without rationale**: GAP/NGP are not reverse-engineered; the published
  Minetti polynomial is implemented instead, with its own citation.
