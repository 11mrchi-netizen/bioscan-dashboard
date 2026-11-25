# Weekly and monthly reports

After each Monday-to-Sunday week and each calendar month (the owner's local calendar, UTC+8), the nightly analysis run
writes one report: every key metric against the period before, the regimen's adherence, what the Lab's interventions and
experiments found, micronutrients from food and supplements with possible shortfalls and excess, a tentative read of food against stool, and a few suggestions.

Owner decisions (2026-11-25): suggestions come from fixed rules, not an LLM; weeks start on Monday; a phone notification
announces each report and the newest is reachable all week (web Today card, Lab › Reports, the phone's Lab › N=1 card);
micronutrients, carbs, sleep phases, water and food against stool are in the metric list.

## Where things are

| Part | File |
|---|---|
| The report, pure (periods, metric lines, adherence, Lab verdicts, micronutrients, food and stool, rules) | `supabase/functions/_shared/analysis-core/periodReport.ts` |
| Reading the database and writing the report | `supabase/functions/_shared/analysis-core/runPeriodReport.ts` |
| Nightly run: group `reports`, 20:48 UTC (04:48 local) | `supabase/functions/analysis-run/index.ts`, `runGroups.ts`, migration `20261125073854_period_reports.sql` |
| On demand: `{ action: "report", keys: ["week:2026-11-16"] }` or `{ action: "report", weeks: 8, months: 3 }` | `analysis-run` |
| Web page Lab › Reports, Today's card | `console/src/workspaces/Reports.tsx`, `console/src/lib/reports.ts`, `console/src/dashboard/Today.tsx` |
| Phone: notification and Lab › N=1 card | `domain/notifications/ReportPrompts.kt`, `data/ReportsRepository.kt`, `NotificationDispatchWorker.kt`, `ui/screens/status/ReportsCard.kt` |

## Storage

One `analysis_results` row per period: kind `period_report`, key `week:<monday>` or `month:<yyyy-mm>`, `as_of` the
period's last day, the whole report in `payload` (`PeriodReport`). A report is written the first night after its period
ends and rewritten for three more nights, so data that syncs late still lands in it; after that it stays as written.

## The rules

**A metric line.** A period's value follows the series contract (`metric-series.json`): the mean of the days with a value,
or a total for sessions (a month's total is given per week). A change counts when it is larger than the usual swing
between consecutive periods (the SD of the changes over the 8 weeks or 6 months before), or, with fewer than three
earlier changes, larger than a fixed smallest change per metric. Metrics with a better direction say *better* or
*worse*; the others (weight, energy intake, carbs, fat, training volume) only *up* or *down*. Too few days in either
period says *too few days*.

**Regimen.** Planned doses follow the regimen model (android `domain/Regimen.kt`): dosing days (every-N-days plans one
in N, weekdays and cycles count their days) times the times of day. Doses logged count at most the planned times on any
one day. 85% or more is followed, under 70% not followed. As-needed items are counted, not scored.

**Lab.** Every intervention and experiment that ran in the period, with its latest engine read (interrupted time series
first, then the baseline-against-active read; an experiment's primary outcomes). *Not followed* (adherence under 70%)
wins over any result; then *working* (a clear change the right way), *making it worse*, *no effect*, *too early*.

**Micronutrients (food and supplements).** `nutrient_intake` rows from meals and fueling are food: a daily mean over
the days with food logged. Rows with `source_type = supplement` are doses: their total over the period divided by every
day of it. The line's value is food + supplements, against adult-male daily needs (US RDA / AI) and safe upper limits
(US Tolerable Upper Intake Levels; magnesium's 350 mg counts supplements only). A nutrient no logged food carries has
food *unknown*, never 0, so it is never called short.

- *Possibly short*: food known on at least 3 days and the total under 70% of the need.
- *Over the upper limit*: the mean is above the limit.
- *Over on some days*: the mean is under it but one or more days went over (a dose taken every few days, for example).

The supplements responsible are named on the line and in the suggestion.

**Food and stool (tentative).** Over the 90 days to the period's end: the share of normal stools (Bristol 3-5) the day
after a day above the median of fibre, water, fat or energy, against the day after one below it. Shown only with 8 days
each side and a gap of 20 points or more. An association, never a cause.

**Suggestions** (at most five, most pressing first): sleep under 7 h; each metric that got worse (its own advice);
protein under 1.6 g per kg; fibre under 30 g; water under 2.5 L; steps under 7,000; each nutrient over its upper limit or over on some
days (naming the supplements); the three nutrients most likely short; each regimen item not followed; each Lab item with no effect, making it worse or not followed;
items taken for 90 days or more with nothing measuring them; wellbeing or food logged too rarely to compare.
