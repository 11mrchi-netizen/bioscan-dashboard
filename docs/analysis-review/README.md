# Analysis review: implementation status

The review (Field Terminal Analysis Review, October 2026) criticised how the app analyses, correlates and presents data,
and proposed a plan in seven phases. This file tracks what was built, where it lives, and the gate each phase had to pass.
Decisions taken by the owner: self-experiments are randomised; an evening check-in runs at 22:25; the console's
uncorrected correlation scan is retired, with a note to revisit it (section "Revisit" below).

| Phase | What | Gate | Status |
|---|---|---|---|
| A | Fix what is wrong on screen | A unit test per bug; the baseline state's false-shift rate on stationary data at or below 10% | Done |
| B | Capture for cause-and-effect questions | Exposure status known on most days once the check-in runs (reported nightly as `capture.knownShare28`) | Done |
| C | Expected bands and flags | Synthetic 80% bands hold 78-82% of days; flags without a real change under one a month | Done |
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

## Phase B: what changed

| Plan item | Built | Where | Test |
|---|---|---|---|
| Evening check-in with an explicit none | `evening_checkins` (one row per local evening; null = not answered, 0 / false = none). A sheet asks drinks, caffeine after 14:00, a late meal after 21:00 and stress 1-5; NOTHING ELSE turns every open exposure into a none. Today shows LAST EVENING until it is answered | migrations/20261015_evening_checkins.sql, domain/EveningCheckin.kt, EveningCheckInSheet.kt, TodayScreen.kt | EveningCheckinTest |
| The 22:25 reminder | New `evening_checkin` notification rule (seeded for existing accounts by the migration, by `ensureDefaults` for new ones), skipped once tonight is answered; a tap opens Today with the sheet up. The older 21:00 generic reminder is renamed EVENING NOTE and can be switched off in Setup › Notifications | NotificationModels.kt, NotificationRulesRepository.kt, NotificationDispatchWorker.kt, NotificationRouting.kt, FieldTerminalNavHost.kt | EveningCheckinTest |
| Explicit zeros in the analysis | Alcohol, afternoon caffeine and late meals are 1 on positive evidence (a log or the check-in), 0 only when the check-in says none, or before the first check-in ever on a day with a food log (the legacy rule), otherwise unknown. New series: `late_meal`, `stress_high`, `evening_training` | analysis-core/runRelationships.ts | runRelationships.test.ts |
| Local time | The phone records its UTC offset and time zone per day (`daily_context`); the engine turns timestamps into local dates and hours with it. It used UTC: in UTC+8 a 15:00 coffee counted as morning caffeine and a 07:30 breakfast landed on the day before | EveningCheckinRepository.recordDailyContext, runRelationships.ts `localClock` | runRelationships.test.ts |
| The small hours belong to the evening before | Food logged before 04:00 counts toward the previous evening; check-ins answered after midnight are filed under that evening | runRelationships.ts, domain/EveningCheckin.kt | both |
| Overnight readings keyed to their night | HRV and resting heart rate from Health Connect are averaged per night (readings from 18:00 count toward the next morning), like sleep_daily, instead of per calendar day, which split one night across two dates. Applies to rows written from the next sync on | domain/DailyAggregation.kt, HealthConnectDailySyncRepository.kt | NightDateTest |
| Passive weather and daylight | Not built: the app has no location source, and adding one needs a permission decision. `daily_context` has nullable columns ready for it | - | - |
| Readiness meter per question | Moved to phase D, where each question gets its no-effect zone (the meter is computed from it) | - | - |

## Phase C: what changed

| Plan item | Built | Where | Test |
|---|---|---|---|
| Expected band engine | Per metric (HRV in logs, resting heart rate, sleep duration) a slowly moving level plus a weekly pattern (exponential smoothing, ETS(A,N,A)); the two smoothing weights are fitted per person by a grid search on one-step errors. The band's width is the spread of the last 120 days of one-step errors, widened after missing days (the h-step forecast variance) and while errors are few (Student t). Published nightly as `expected_band`: the 80% and 99% bands for each of the last 28 mornings and for the morning after the run, in natural units, with the model-space centre and width | analysis-core/expectedBand.ts, runExpectedBands.ts, analysis-run/index.ts | expectedBand.test.ts, runExpectedBands.test.ts |
| Flags | One morning beyond the 99% band, or a run of mornings on one side that trips an EWMA chart on the standardised errors (lambda 0.3, limit 2.4; its input clipped at the 99% point so one faulty reading cannot fake a run). Runs are flagged only once the band rests on 21 errors. Model updates use errors clipped at three band widths. Illness days are drawn against the band but neither update it nor count | expectedBand.ts | expectedBand.test.ts |
| Regimes | A confirmed series break of the metric starts a new regime: the level relearns as a running mean of the new readings, the weekly pattern carries over, and the band is built from new-regime errors only (none for the first seven, then wide). Candidate breaks and breaks of other series do nothing | expectedBand.ts, runExpectedBands.ts | both |
| Gate | Over 60 synthetic series for each of three noise structures (lag-one autocorrelation 0, 0.3, 0.5; 20% of days missing) the 80% band held 81.2%, 80.2% and 80.9% of mornings and the 99% band 99.1 to 99.2%; low-side flags came 0.2 to 0.4 days a month and flag episodes on either side 0.4 to 0.7 a month. Over 1,000 series a 2.5-SD drop lasting five mornings was flagged 88% of the time, a 1.5-SD drop lasting four 44% | expectedBand.test.ts | (the gate tests) |
| Today answers first | THIS MORNING leads Today: one sentence per metric (the value, its size against what was expected for that weekday, the 80% band labelled "8 mornings in 10", how often a morning that far out comes as a natural frequency, and the flag in words), the lead metric's band chart with flagged mornings ringed and illness and source changes marked, and under it how often the band actually held | domain/ExpectedBand.kt, ExpectedBandCard.kt, TodayScreen.kt | ExpectedBandTest |
| HRV and resting HR today | Health › Cardio leads with THIS MORNING for both, each with its band chart; the 60-day baseline cards follow as the longer view | HeartTileScreen.kt | - |
| Status hero | THIS MORNING · HRV VS EXPECTED when the band exists and today has a reading; otherwise the HRV-against-baseline readiness band, as before | StatusRepository.kt, BodyConsole.kt | - |
| A reading after the run | The nightly run sees only readings synced before it. A later reading is placed with the published centre and width, and steps the run chart once, as the server would have | domain/ExpectedBand.kt | ExpectedBandTest |
| Chart rules | Every trend has a y-axis with at least three labelled values. HRV, resting HR, steps and wellbeing charts use a fixed personal scale (2nd to 98th percentile of the person's readings); vitals, steps, wellbeing and protein break at missing days, with the gap marked on the axis; the band chart uses per-day bands, ringed flags and annotations | DateTrendLine.kt, ChartRules.kt, HeartTileScreen.kt, FuelTileScreen.kt | ChartRulesTest |
| Contract | R3 (each morning against what was expected) and R4 (chart rules) | design/ANALYTICAL_PRESENTATION_CONTRACT.md | - |
| Engine version | `CORE_VERSION` 3.0.0-alpha.2: phases A to C changed published algorithms; old rows stay readable | version.ts, docs/analysis-layer-3/contract-v3.md | - |
| Replay on the real history | Runs every night once deployed: each metric's `calibration.inside80` (how often the published band held over the last 120 mornings) is in the payload and shown under the chart. Not run from here, as the repository holds no personal data | - | - |
| Not built | The optional inputs the plan mentioned (yesterday's load, last night's sleep as regressors) and online changepoint detection: the series-break engine supplies regimes instead. SpO2 has no band (it is not in the analysis frame) | - | - |

## Revisit

**Exploratory correlation scan (console Relationship Lab), retired 2026-10.** It ran raw Pearson correlations over ±14
lags with no adjustment (seven metrics give 609 correlations, about 30 significant by chance per view). Revisit it as the
discovery screen the review proposed: prewhitened cross-correlations with effective sample sizes, a false-discovery
correction, chance bands on a lag heatmap, run on the older two-thirds of the history, and a "register this question"
action that sends a promising pair into the confirmation ledger.
