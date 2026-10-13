# Performance pass: what changed and what was measured

Scope: speed, efficiency, error recovery and daily use across the Android app, the React console and the original web dashboard, for one user at personal scale. No database changes, no health formula changed. Work is staged as separate commits on `feat/analysis-layer-3` (PR #19).

"Before" is the code at the start of the pass; "after" is the branch head. Where a number could only be measured here (JVM, built bundles, a stubbed web page, SQL), it says so. Numbers that need your phone are listed at the end with the recipe.

## Results

| Path | Before | After | How measured |
| -- | -- | -- | -- |
| Android: bytes of one `wearable_daily` analysis load (200 rows) | 1,862,066 B | 20,044 B (-98.9 %) | SQL over the live table, JSON text of the selected columns |
| Android: wearable bytes on the home screen load (60-row query plus 200-row query) | 3,704,304 B | 20,044 B (shared with the other screens) | same |
| Android: requests, home screen first load | 9, one after another | 7, all at once | code |
| Android: requests, home screen when the lists are cached (< 90 s old) | 9 | 3 | code; `ReadCache` unit tests |
| Android: requests, heart tile (after opening home) | 9 sequential | 6 in parallel (the shared lists are cached) | code |
| Android: heart / readiness / training tiles | loads one after another | loads in parallel off the main thread | code |
| Android: suspected-trail clustering, 300 runs (JVM) | 94.3 ms | 0.8 ms; a randomized test proves identical output | `PerfGoldenTest`, best of 3 |
| Android: `evaluateSri`, 60 nights (JVM) | 1.9 ms | 1.9 ms, not a hotspot, left alone but now computed once per load | `PerfGoldenTest` |
| Android: heavy evaluations (sleep index, dynamic recovery, training load) | recomputed on every recomposition | once per load (`remember`) | code |
| Android: screen loads that can crash or hang on a failed request | 23 | 0 (each shows an error card with RETRY; click handlers clear their busy flag) | detector script over `LaunchedEffect` / `scope.launch` |
| Android: release APK | not minified | 5.3 MB (debug: 39.5 MB); R8 + resource shrink + baseline profile | `assembleRelease` output |
| Android: unit tests | 498 | 526 (one known failure in the untracked WIP `WarmUpTest`) | `testDebugUnitTest` |
| Console: requests, Overview | 6 | 4 | code; same-table metrics share one query |
| Console: requests, Recovery | 5 + injuries | 4 + injuries | code |
| Console: range change 90D to 28D to 7D | 3 requests, workspace blanked each time | 1 request, old charts stay on screen while refreshing | `metricCache` tests; code |
| Console: Nutrition meals | 2 queries (one capped at 1,000 rows) | 1 paged query | code |
| Console: JS bundle | echarts 598.91 kB, supabase 214.17 kB, react 259.42 kB, app 14.95 kB | unchanged except app 15.90 kB; echarts prefetched when the browser is idle | `vite build` |
| Console: node tests | 5 | 17 | `node --test` |
| Console: lint errors in `src` | 1 (Recovery crashed when data arrived) | 0 | `oxlint src` |
| Web dashboard: startup queries | 16 | 13 | stubbed page, `window.__stubLog` |
| Web dashboard: startup payload (synthetic rows) | 494,738 B | 56,661 B (-88.5 %) | stubbed page |
| Web dashboard: persistent animation loops | 3 | 2 (the Gear3D scene stops after ~1.5 s idle; the background draws at ~30 fps) | stubbed page |
| Web dashboard: panels rendered | 16 panels, stat bars, top bar | byte-identical fingerprints | stubbed page, FNV hash of each panel's HTML |
| Web dashboard: failed load | console warning only | banner with RETRY (sign-in prompt if the session expired); 30 s request timeout | stubbed page with `?fail=` |

Notes on the numbers:
- The live `wearable_daily` table has intraday heart-rate data on only 36 rows (about 273 kB compressed in storage); as JSON text the 200 most recent rows were 1.8 MB. The synthetic web page has it on every second day, which exaggerates the web figure; the Android figure is from real rows.
- The JVM timings are not phone timings (a phone is several times slower), but the ratio holds because the change is algorithmic.
- The browser pane throttles animation frames, so frame rates were not measured; the loop counts and the draw cost per frame (2.1 ms of script per background draw, unchanged) were.

## What changed, by stage

1. **Measurement and safety net.** Debug-only logcat tag `perf` (a marker per screen, one line per Supabase request with method, path, ms, bytes), `Perf.timed`, Compose compiler reports behind `-PcomposeReports`, `PerfGoldenTest` pinning the health functions, a web harness in `scripts/perf`.
2. **Recoverable errors.** Android `LoadGuard` (error card with RETRY, `launchSafe`, `softLoad`); Health Connect sync isolates each record type and still only advances its watermark when all succeeded; the timeout went from 10 s to 45 s; offline with an expired token shows "Reconnecting" instead of "sign in again"; console `useLoadEffect` and `LoadBanner`; web banner, timeout, sign-out wired first.
3. **Data layer.** Android `ReadCache` (90 s, cleared by any write, by sign-out, never caches a failure); the home overview in parallel; intraday heart-rate fetched only for the Heart tab; console metric cache with slicing, de-duplication and batching; web narrower queries.
4. **Rendering, startup and build.** Android `FieldTerminalApp` warms the client, registry and channels off the main thread; the Health Connect worker skips a run within 10 minutes of the last success; the notification worker needs a connection; release variant with R8 and a baseline profile; Gradle caching. Console: charting chunk fetched when idle. Web: animation changes.
5. **Daily use.** Android: the last-used log type is one tap away, rotation no longer recreates the activity. Console: the chosen date range is remembered and uses the local calendar date (it was a day behind in the early morning because it used UTC). Web: data reloads after 30 minutes away.

## Left as it was, on purpose

- **Heart tab shows the oldest day with intraday heart rate in its window**, not the latest. That looks unintended (`firstOrNull` on an ascending list) but changing it changes what the card shows, so the new loader returns exactly the same day. Say if you want "most recent".
- **Web dashboard date filters.** It filters `meals.logged_at` and `stool_log.occurred_at` with a bare date (`lte(col, 'YYYY-MM-DD')`) and groups meals by the UTC date. If those columns are timestamps, today's meals are probably left out of the nutrition totals. Not changed: it would change displayed numbers. Needs a look at the live column types.
- **Startup order on the web dashboard** (3D, weather, air quality and calendar still start after the data fetch): the code after the fetch uses `const`s declared later in the same function, so starting it earlier is not a safe mechanical change.
- **Stale-while-revalidate from storage** (painting the last data before the network answers) on the web dashboard and the console. The cache in memory removes repeat requests; persisting it across reloads was not done.
- **Hand-rolled windowed statistics** and the web ACWR versus Android load formulas: unchanged (correctness work, not performance).

## Numbers that need your phone

Install a debug build and read logcat:

```
adb logcat -s perf
```

`nav <route>` lines mark a screen opening; the `http` lines after it are that screen's requests (ms and bytes each); `timed` lines are wrapped computations. Cold start: `adb shell am start -W -n com.bioscan.fieldterminal/.MainActivity`. For the release build, install `app-release.apk` (signed with the debug key, so Google sign-in works) and click through every tab before relying on it: R8 is the one change here that can only fail at runtime.
