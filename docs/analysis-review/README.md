# Analysis review: implementation status

The review (Field Terminal Analysis Review, October 2026) criticised how the app analyses, correlates and presents data,
and proposed a plan in seven phases. This file tracks what was built, where it lives, and the gate each phase had to pass.
Decisions taken by the owner: self-experiments are randomised; an evening check-in runs at 22:25; the console's
uncorrected correlation scan is retired, with a note to revisit it (section "Revisit" below).

| Phase | What | Gate | Status |
|---|---|---|---|
| A | Fix what is wrong on screen | A unit test per bug; the baseline state's false-shift rate on stationary data at or below 10% | Done |
| B | Capture for cause-and-effect questions | Exposure status known on most days once the check-in runs | Pending |
| C | Expected bands and flags | Synthetic 80% bands hold 78-82% of days; flags without a real change under one a month | Pending |
| D | Association engines and the evidence board | Null data reads "clear" on at most 5% of questions; interval coverage 93-97% | Pending |
| E | Ledger and confirmation | Nightly re-runs with no true effect ever read "clear" at most 5% of the time | Pending |
| F | Randomised experiments and interrupted time series | Backtests with drift, carry-over and missing days keep false positives at or below 5% | Pending |
| G | Composite scorecard and the console | A published scorecard per composite | Pending |

## Phase A: what changed

| Finding | Fix | Where | Test |
|---|---|---|---|
| A1 State pills ignored direction; stable read OPTIMAL | One mapping per kind of evaluation, with a word on the pill (STEADY, ABOVE / BELOW BASELINE, MIXED SIGNALS); Optimal only for a favorable shift; mixed recovery no longer blanks READY FOR | domain/StateWords.kt, TrainingReadiness.kt, every pill call site | StateWordsTest, TrainingReadinessTest |
| A2 HRV band drawn about 4x too narrow | SwcEvaluation carries the band that decided the state; HRV's is exponentiated end by end | HrvRhrEvaluation.kt, HeartTileScreen.kt EvalCard | SwcCalibrationTest |
| B7 Baseline state warned on ordinary noise | A shift needs the weekly mean outside both the smallest worthwhile change and 1.96 standard errors of a weekly mean (widened for carry-over estimated around a running median). Stationary false-shift rate fell from 16-37% to 4-8% in simulation; a 2-SD drop is still caught | HrvRhrEvaluation.kt | SwcCalibrationTest |
| A3 Percentiles ignored direction and their window | Rank within the 28 days the label names; bands from the favorable position; no band for metrics with no better end; correct ordinals | PersonalComparison.kt, ComparisonBands.kt, ComparisonStrip.kt | ComparisonDirectionTest |
| A4 Correlation words from 7 pairs | 20 pairs minimum, Fisher 95% interval shown, strength word only when the interval excludes zero | domain/analysis/Correlation.kt, HeartTileScreen.kt | CorrelationTest |
| A5 Console VO2max and load in the wrong units | Daniels & Gilbert VO2max in metres per minute with the duration term; session load = sRPE, else TRIMP (as on Android); EWMA span alpha = 2/(N+1) as on Android | console/src/lib/training.ts, Training.tsx | training.test.ts |
| A6 Console dual axes, category time axes, no paging, aggregation ignored | Aligned panels instead of a second y-axis, real time axis with gaps, paged reads, daily aggregation applied | Comparison.tsx, metricAdapter.ts, metricTypes.ts | metricTypes.test.ts |
| A7 Confidence chips showed data counts; population confidence fixed at 2/2 | DATA chip for counts; population fit counts confirmed strata; wrong-stratum tables never used | AnalyticalPresentation.kt (FTDataChip), PopulationComparison.kt | PopulationFitTest |
| A8 Readers took any row | Newest as_of, then newest run, per key (Android and console) | AnalysisRepository.kt, Confirmatory.tsx | - |
| B4 Permutation p biased down on sparse data | Only shuffles that produced a statistic count | analysis-core/stats.ts | stats tests |
| B9 Typicality thresholds calibrated on a different distance | Reference distances computed over the same signals and the same way as the judged day | analysis-core/typicality.ts | typicality.test.ts (day without steps) |
| B10 Every metric auto-confirmed on any source flip; unpaged provenance | Only device-sensitive metrics; a switch must hold for more than two thirds of the next six days; paged reads | runSeriesBreaks.ts, seriesBreakResult.ts, paging.ts | runSeriesBreaks.test.ts |
| Decision: retire the exploratory scan | The console's EXPLORATORY tab now shows why it was retired and what to build instead | console/src/workspaces/RelationshipLab.tsx, docs/analysis-console/IA.md | - |

## Revisit

**Exploratory correlation scan (console Relationship Lab), retired 2026-10.** It ran raw Pearson correlations over ±14
lags with no adjustment (seven metrics give 609 correlations, about 30 significant by chance per view). Revisit it as the
discovery screen the review proposed: prewhitened cross-correlations with effective sample sizes, a false-discovery
correction, chance bands on a lag heatmap, run on the older two-thirds of the history, and a "register this question"
action that sends a promising pair into the confirmation ledger.
