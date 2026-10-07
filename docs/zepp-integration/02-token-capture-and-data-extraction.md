# 02 — Token capture without a proxy, and the missing workout-detail endpoint

**Linear:** DAV-110, DAV-112, DAV-114, DAV-115, DAV-123 · **Status:** research complete, supersedes part of 01

## Why this doc exists

01 recommended capturing the `apptoken` via an HTTPS proxy (mitmproxy) on the phone
running the Zepp app. In practice this required patching the Zepp APK to disable
certificate pinning (confirmed via `apk-mitm`: Zepp pins certs in ~10 classes), which
is heavier than the token itself justifies. Two real open-source projects were
inspected directly (source, not just README) to find a lighter path, and one of them
also answers the "only one ingredient" -- wrong analogy, actually the pace/power gap:
neither of Health Connect's `SpeedRecord`/`PowerRecord` nor our own `zepp-extract`
today reach the endpoint that has real per-point workout data.

## 1. Token capture: reuse Zepp's own web login, skip the proxy entirely

[ZeppBridge](https://github.com/lingcang728/ZeppBridge) (Rust/Tauri desktop app) does
this:

1. Opens an embedded webview at `https://watchface.zepp.com/` (fallback
   `https://user.huami.com/privacy2/index.html`).
2. The user logs in on Zepp's **own real login page**, inside that webview -- the app
   never sees the password, same boundary this project already holds.
3. After login, it reads `apptoken` / `user_id` straight out of the page's own
   `localStorage`/`sessionStorage`/cookies via JS (`hm-user-login-info`, `userid`,
   `app_token` keys) -- **no network interception, no proxy, no cert-pinning bypass.**
   Falls back to manual HAR import only if that JS extraction fails.

This works because the Zepp *web* login surface (used for the watchface store) sets
the same `apptoken`/`user_id` client-side that the mobile app carries in headers --
it's the same account token, just exposed in a place a plain browser can read.

**Confirmed working end-to-end 2026-09-25** against the real account: logged into
`watchface.zepp.com`, read `apptoken`/`userid` straight out of `document.cookie`
(both cookies and `localStorage` carry a copy for this account; the cookie is what
was actually used) plus `wf_baseUrl` from `localStorage` for the regional host
(`api-mifit-us3.zepp.com`). All three saved as Supabase secrets and confirmed live
against Zepp's real API (§2) -- this account has two bound devices
(`MILI_STUTTGART_W`, `MILI_Y_GENEVA_W`, matching the project's Active Max + Helio
Strap setup), both visible in the same workout-history response.

**Implication:** re-auth (needed ~every 30 days per 01) becomes: open a browser, log
into Zepp's own site, read a couple of cookies. No phone, no mitmproxy, no APK patch.
This can be done ad hoc (a small bookmarklet/console snippet) rather than needing a
maintained desktop app.

## 2. Data extraction: the workout-detail endpoint we were missing

Both `zepp-health-cli` (github.com/m4ary/zepp-health-cli) and ZeppBridge's `zepp.rs`
connector confirm the same two-step shape for workout data:

| Call | Endpoint | Purpose |
|---|---|---|
| List | `/v1/sport/{sport}/history.json?userid=&startTrackId=&stopTrackId=&need_sub_data=1&type=` | Workout summaries, paginated by track-id cursor. Each entry carries a `trackid` + `source`. |
| **Detail** | `/v1/sport/run/detail.json?trackid=<id>&source=<source>` | **Per-point time series for one workout: GPS route, pace, HR, power.** Path is always `run/detail.json` regardless of sport. |

**Confirmed 2026-09-25 via a direct `curl` against the real account** (90-day window,
74 real workouts returned): the list endpoint's actual envelope is

```json
{ "code": 1, "message": "success", "data": { "next": -1, "summary": [ { "trackid": 1790208165, "source": "run.10289411.huami.com", "type": 52, "bind_device": "0:MILI_STUTTGART_W:10289411:0.132.24.1", "dis": "0.0", "end_time": "1790211346", "...": "150+ more fields" }, "..." ] } }
```

i.e. `data.summary`, not `data.items` or a bare array (both reasonable guesses that
turned out wrong) -- `zepp-extract`'s `extractWorkoutRefs()` now matches this exactly.
`trackid` is numeric, itself a Unix-timestamp-like value close to the workout's start
time; `type` is Zepp's internal sport-type code (52 seen for a run); `bind_device`
confirms which physical watch recorded it.

**Detail endpoint confirmed 2026-09-25** -- full pipeline (token → list → detail →
reconcile → `exercise_sessions`) run end to end from the real Android app against the
real account. Two real findings from the actual payload:

1. **The per-point track is not a JSON array.** `data.lap` is one long string, points
   separated by `;`, ~50 comma-separated fields per point in an undocumented fixed
   order (values like `-1`/`-20000`/`-1.0` are Zepp's "not applicable" sentinels).
   `data.heart_rate` is a *separate*, differently-shaped delimited string
   (`"<flag>,<value>;..."`) with small values that don't look like absolute BPM --
   likely delta-encoded from a baseline, not yet decoded. Decoding either needs
   reverse-engineering the exact field order (not yet done) -- this is real,
   meaningful work, not a quick follow-up.
2. **The two real workouts synced so far are strength-training sessions** (`type=52`,
   `strengthAssess`/`strengthSets`/`rope_skipping_*` fields populated, `dis="0.0"`,
   pace/speed/altitude all empty strings) -- not GPS runs. The original motivating
   problem (real pace/power for an outdoor run) still needs a `detail.json` payload
   from an actual GPS-tracked run to validate against; nothing here confirms or
   refutes that case yet.

A real bug surfaced during this run and is already fixed: `exercise_sessions`' CHECK
constraint on `source` only allowed `manual`/`health_connect` -- the first reconcile
attempt silently failed insert, masked by code that discarded the insert error.
Migration applied to allow `'zepp'`; the code now surfaces (rather than swallows)
that error. Confirmed fixed: both real workouts now have `zepp_workout_detail` rows
pointing at real `exercise_sessions` rows (`source='zepp'`).

Our existing `zepp-extract` (`supabase/functions/zepp-extract/index.ts`) only calls
the **list** endpoint (`sport_history`, no `need_sub_data`, no follow-up detail call)
-- it has never reached the endpoint that actually has real per-second pace/power/GPS
for a completed run. This is very plausibly the real fix for the pace/power problem
DAV-201 patched around: `retimedOffsets()` in `SessionDetailRepository.kt` is a
heuristic reconstruction of Health Connect's degenerate sample timing; the Zepp
detail endpoint would be *actual* correctly-timed data, obtained once and stored,
instead of a reconstruction done at read time.

Other endpoints confirmed real and not yet in `zepp-extract`'s `METRIC_DEFS`:
`vo2_max` (`WatchSportStatistics/VO2_MAX`, already present), `weight_records`,
`blood_pressure_me`, skin `temperature` (`readiness/watch_score` event), PAI
(`all_day_stress` / `PaiHealthInfo` presets), and finer HRV/stress event sub-types.
None of these block the reconciliation work below; they're additive coverage for
DAV-110/123.

## 3. The double-data problem (DAV-115) -- concrete rule

The watch already writes workouts to Health Connect today (Huami is the "chosen"
distance/speed/power source there, confirmed earlier this project). Pulling the same
workouts from the Zepp Cloud API directly, naively, creates a second row for the same
real run. `exercise_sessions` upserts on `(user_id, health_connect_record_id)` --
a Zepp-sourced sync has no such id, so it must never insert under a different key for
a session Health Connect already has.

**Rule:** match a Zepp `run/detail.json` workout to an existing `exercise_sessions`
row by `(user_id, start_time within ±2min, comparable duration)` -- the same tolerance
pattern this project already uses for GPX/route linkage.

- **Matched** (the common case): Health Connect's row stays the row of record --
  today's aggregates (`distance_km`, `avg_hr`, etc.) are untouched, no behavior change
  for existing consumers. The Zepp detail payload (real per-point pace/power/GPS,
  plus Active-Max-specific fields once DAV-123 confirms them) is stored as
  *enrichment* keyed by `exercise_session_id` -- a new `zepp_workout_detail` table,
  not a second `exercise_sessions` row. This mirrors how `checkRouteAvailability()`
  already treats the GPS route as separate per-session enrichment rather than a
  competing session source.
- **Unmatched** (Health Connect never got this workout, or a sync-timing gap): insert
  a new `exercise_sessions` row sourced directly from Zepp, tagged with a `source`
  column so downstream analysis can always tell which pipeline produced a given
  session (this is DAV-115's own acceptance criterion: "downstream analysis can
  identify the original source").
- **Read-time precedence:** `SessionDetailRepository.loadTimeSeries()` should prefer
  `zepp_workout_detail`'s real series over Health Connect's `SpeedRecord`/`PowerRecord`
  (retimed or not) when both exist for the same session -- real data over a
  reconstruction, same "prefer real over synthetic" bias this project already applies
  elsewhere.

## Sources

- [ZeppBridge](https://github.com/lingcang728/ZeppBridge) -- `src-tauri/src/commands/login.rs`, `src-tauri/crates/core/src/connectors/zepp.rs`, `src-tauri/crates/core/src/auth/`
- [zepp-health-cli](https://github.com/m4ary/zepp-health-cli) -- `zepp_health.py`
- `supabase/functions/zepp-extract/index.ts` (current state, this repo)
- `docs/zepp-integration/01-auth-and-token-lifecycle.md` (this repo, DAV-111)
