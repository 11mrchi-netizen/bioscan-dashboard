# Bioscan Dashboard — Project Roadmap

*Last updated: 2026-09-12. Supersedes all earlier versions of this file and the original
Sept 12 calendar event description (which hit Google Calendar's length limit).*

**Time estimates are directional, not committed.** They assume focused session-based work
(evenings/weekends style), not full-time effort, and don't account for debugging or platform
surprises. Treat them as "roughly this scale," not a deadline.

---

## ⚠ CORRECTED AUTOMATION MODEL (read this first)

The original plan assumed Wellness Project supported an unattended personal API key for
scheduled/headless access (GitHub Actions running a cron job with no human present). **This
turned out to be wrong** — Wellness Project's Claude connector (`wellnessproject.ai/settings/claude`)
is OAuth-only: "Sign-in happens through your Wellness Project account, no API key needed,"
confirmed directly from their own in-app guide. There is no unattended access path.

**What this means practically**: the daily sync cannot run on a schedule with nobody around.
Instead, **you ask Claude (in a chat like this one, where the Wellness Project MCP connector
is already authorized via your browser session) to run the sync**, and Claude pulls fresh data
and writes it directly to Supabase in that same conversation. This is a real, working, tested
mechanism — just manually-triggered rather than fully automatic.

The `daily-sync.yml` GitHub Actions workflow and `sync.js` script drafted early on are **not
in use** for this reason — kept only as a reference in case Wellness Project adds unattended
API access in the future, in which case they'd need re-validating against the real API
contract (they were written from general MCP-protocol assumptions, never verified against
Wellness Project's actual request/response shape).

---

## 🛠 DEVELOPMENT WORKFLOW SPLIT (new, 2026-09-12)

Starting this session, work on the project splits across two surfaces, deliberately:

- **Claude Code** (local, has real filesystem/git access and — critically — can actually
  render the page in a browser, which this chat has never been able to do) is now the primary
  driver for changes to `index.html` / `login.html` themselves. A full setup/briefing prompt
  was written for it (`claude-code-setup-prompt.md`, delivered separately) covering the real
  architecture, known bug classes already hit once, and credential-handling rules.
- **This chat** (claude.ai) stays the driver for anything using the MCP connectors already
  live here and not (yet) replicated in Claude Code: Supabase schema/data work, Wellness
  Project syncs, Google Calendar/roadmap discussions.
- **Why**: every bug this whole build has hit (zero-dimension canvas crashes, wrong gear
  asset picked, misplaced UI elements, the `login.html` auto-redirect trap) was only caught
  because the person manually tested in a real browser and reported back — there was no way
  for Claude (in this chat) to see a render or run the code. That's the concrete gap Claude
  Code closes.

---

## ✅ FOUNDATION — COMPLETE (2026-09-11)

Real, live, verified infrastructure:

- **Supabase project**: `bioscan-dashboard`, region `ap-southeast-1` (Singapore), project ref
  `ugfrglbcoivkprjqvjzz`, free tier ($0/month confirmed). Postgres 17.6.
- **22 tables**, full domain coverage across every roadmap tier (injuries, supplements,
  lab_draws/lab_results, wearable_daily, sleep_daily, wellbeing_daily, hydration_daily,
  body_metrics, meals, recovery_sessions, rest_days, runs, sync_log, illnesses, people,
  encounters, arousal_daily, masturbation_log, stool_log, clothing_items, laundry_loads).
  Row-level security enabled and policy-correct on all 22 (verified via Supabase's own
  security advisor — zero real warnings). RLS performance-optimized (`(select auth.uid())`
  pattern) and fully indexed on every foreign key.
- **Auth**: Google OAuth via Supabase Auth, confirmed working end-to-end. One real user:
  `d.demarchi11@gmail.com`, provider `google`, user id `9757c37c-28c2-4d5e-bb3d-8027c853f7d7`.
  Scope later extended to include `calendar.readonly` (see Calendar Integration below) —
  requires the user to have signed in *after* that scope was added; a sign-out button now
  exists in the dashboard's top bar specifically to make re-authorizing possible (the
  original `login.html` auto-redirects past the sign-in button whenever a valid session
  already exists, so signing out first is the only way to re-grant a new scope).
- **GitHub repo**: `11mrchi-netizen/bioscan-dashboard`, public, GitHub Pages live at
  `https://11mrchi-netizen.github.io/bioscan-dashboard/`.
- **First real manual sync completed**: pulled live from Wellness Project, written to
  Supabase, logged in `sync_log`. Proved the actual end-to-end mechanism (chat-triggered, not
  cron-triggered) — and has been re-run since with real data across all populated domains
  (30 days of wearable/sleep/wellbeing/hydration/meals, 16 real runs, both lab draws / 77
  markers, etc.).

**Secrets note**: `SUPABASE_SERVICE_ROLE_KEY` and `WELLNESS_API_KEY` were never actually
needed given the corrected automation model above — only the Supabase *publishable* key
lives in committed files, which is safe by design (RLS is the real gate, not key secrecy).

---

## ✅ FULL DASHBOARD MERGE — COMPLETE (2026-09-11)

The full 3D hologram dashboard (body model, readiness tab, character sheet, outrun
background, gear assets) — previously a separate standalone file built across earlier
sessions — is now `index.html` itself, live against real Supabase data. The old hardcoded
`DASHBOARD_DATA` object was replaced with `fetchDashboardData()`, an async function that
queries every relevant table in parallel and reshapes results into the exact structure every
panel's `render()` already expected — so panel code itself didn't need rewriting, only the
data layer underneath it.

- 13 original panels confirmed working against live data.
- Endurance and labs panels, which were still hardcoded static text even after the initial
  merge, have since been upgraded to render from the live `runs` and `lab_draws`/`lab_results`
  tables (see Layout Rework below — labs moved location in the same pass).

---

## ✅ LIVE WEATHER — COMPLETE (2026-09-12)

Open-Meteo (no key required) wired in two ways:
1. Drives the outrun background's variant (sunny/cloudy/rainy/typhoon) automatically on load,
   via a WMO-weather-code mapping (manual toggle still works as an override).
2. A dedicated **Weather** panel (see Layout Rework below) shows current conditions + a real
   3-day forecast.

---

## ✅ LIVE CALENDAR / SESSION INTEGRATION — COMPLETE (2026-09-12)

Genuinely browser-direct, no Supabase table involved — a deliberate architecture choice over
the alternative (a `sessions` table synced from this chat), since it was worth the extra
one-time OAuth setup to get truly live data:

- `login.html` requests the `calendar.readonly` scope at Google sign-in (added to the same
  OAuth consent screen used for Supabase Auth), plus `access_type:'offline'` +
  `prompt:'consent'` to force a refresh token — Google doesn't return one by default, and a
  normal Supabase session refresh does **not** refresh the Google-specific `provider_token`,
  only a fresh login does. This is a documented, still-open rough edge, not fully solved —
  after roughly an hour, calendar calls may start silently failing back to a placeholder
  state until the user signs out and back in (a plain page reload does **not** fix it, since
  it doesn't get a fresh Google token). **Fixed 2026-09-12 (Claude Code): the placeholder now
  correctly distinguishes this from "no session found."** Previously, a failed Calendar API
  call (e.g. a 401 from an expired token) was silently swallowed and showed the same "No
  upcoming training session (colorId 8) found" message as a genuine empty result — actively
  misleading, since it looked like a color-matching bug rather than an auth problem. Now a
  fetch failure sets `calendarFetchError` and the panel shows an accurate "Calendar fetch
  failed... sign out and back in" message instead. See `fetchNextSession()`'s catch block and
  the `session` panel's placeholder branch.
- `index.html` captures `session.provider_token` and calls the Google Calendar API directly
  from the browser. Session-type detection uses **colorId `'8'`** (confirmed reliable from
  real calendar data — cleaner than matching on emoji/title text, which varies).
- Session type classification maps to the same 3 gear kinds the 3D gear model already
  supports (barbell / shoes-run / shoes-trail-vest) and calls `setGearKind()` automatically.
- A gear checklist renders per session type, **now weather-aware (done 2026-09-12, Claude
  Code)**: for outdoor sessions (run/trail run — not barbell/strength, which is indoor), a
  "WEATHER-DRIVEN" sub-section adds rain jacket / cold layer / heat hydration / windbreaker
  items based on the forecast for the session's own day (falls back to current conditions if
  the session is outside the 4-day Open-Meteo window). See `getSessionWeather()` and
  `weatherGearItems()` near the live-weather code, and the `session` panel's `render()`.
- GPX links (when present in the event description) are parsed and surfaced as a real link.
- All calendar-sourced text is passed through an `escapeHtml()` helper before insertion into
  `innerHTML` — defensive, since this is now genuinely external API data flowing into the page.

---

## ✅ LAYOUT REWORK — COMPLETE (2026-09-12)

Real redesign, not incremental tweaks — moved several things to more sensible locations:

- **Top bar**: reduced from 4 cells to 3 — Strength, Endurance, **Weather** (new). Supplements
  and Labs removed from the top bar entirely.
- **Supplements**: now a body-region marker (torso, opposite side from the Heart marker —
  required narrowing Heart's own hitbox test band to prevent a real region-overlap bug caught
  during this change, not after).
- **Labs**: moved into the character-sheet stat card as a "LABS →" row at the bottom, using
  the same generic `data-open` click-wiring every other panel trigger already uses.
- **Session + gear**: a tappable "NEXT SESSION" label now sits directly above the 3D gear
  model, opening full session detail (see Calendar Integration above).
- **Sign-out button**: added to the top bar (see Foundation section — this was a genuine
  missing piece, not a nice-to-have, since there was previously no way to re-trigger the
  OAuth consent flow once signed in).

---

## ✅ FIXED — body-region hitbox overlap with arms (found + fixed 2026-09-12, Claude Code)

Confirmed by actually rendering the dashboard in a browser for the first time (via a local
mock-data harness — see below) and clicking around the 3D model: **clicking on the raised
arm/shoulder — visibly off the torso — incorrectly opened the Lungs panel (one side) or the
Supplements panel (other side)**, instead of doing nothing.

Root cause: `REGION_DEFS` (index.html, "THREE.JS — GLB BASE MESH WITH COORDINATE-BASED
REGIONS" section) resolves a click to a region using only normalized height (`nx`) and
left/right (`x`) bands, with no depth/z or arm-exclusion check.

**Fix — grounded in the actual mesh data, not guessed thresholds.** The raw vertex positions
(and normals) are embedded directly in `index.html` as JSON, so rather than trial-and-error
clicking, the fix came from directly analyzing that data offline: for each of the two buggy
height bands, checking whether torso and arm vertices are geometrically separable in x at all
(sorted-vertex gap detection), and — where they weren't — whether surface-normal orientation
could separate them instead.

- **Heart/Supplements band (`nx` 0.65–0.72):** the raw mesh genuinely has a gap here between
  torso (`|x|` up to ~1.0–1.3 depending on exact height) and the arm (reappearing past
  ~1.7–2.4). Both tests previously had **no outer x-bound at all** — heart was `x<=0.6` with no
  lower bound, spleen was `x>0.6` with no upper bound — so a click on *either* arm at this
  height, however far out, matched one of the two regions. Fixed by bounding both sides
  (`heart: -1.4<=x<=0.6`, `spleen: 0.6<x<=1.4`), with 1.4 chosen to sit inside the gap at its
  narrowest point across the band. Verified against real mesh vertices: 100% of torso-side
  points still match correctly, 0% of confirmed-arm points now false-match (was the bug).
- **Lungs band (`nx` 0.74–0.90):** unlike heart/supplements, direct analysis found **no
  geometric gap anywhere in this band** — the shoulder is anatomically continuous with the
  chest in this mesh, and even surface-normal orientation doesn't cleanly separate them
  (front-facing arm surface exists at the same x range as the chest). This band **cannot be
  made fully correct without a rigged/segmented mesh** — a coordinate-only heuristic has a
  genuine ceiling here. Applied the best available mitigation: tightened `|x|` from 1.6 to
  1.0 (justified by normal.z turning negative — surface facing sideways, not toward camera —
  past roughly x=1.2) and moved the band's lower bound from `nx>0.74` to `nx>0.72` to close a
  redundant seam with the heart/supplements fix above. Verified against real mesh vertices:
  false-positive rate on clearly-shoulder/arm points (`|x|>=1.2`) dropped from 35.5% to 0%. A
  narrow residual zone (roughly `x` 0.6–1.0, genuinely inner-shoulder/arm-root) can still
  occasionally false-positive — this is an honest, documented limitation, not an oversight.

**Also new (2026-09-12): a reusable local visual-testing setup.** Since the dashboard is
gated behind Supabase auth, real-browser testing without live credentials wasn't previously
possible. Claude Code now has a mock-data test harness (a scratch copy of `index.html` with
`fetchDashboardData()` swapped for synthetic data, served locally via `.claude/launch.json`)
that renders the full dashboard — 3D model, panels, gear, weather — without touching real
Supabase data or Google auth. Not part of the committed repo; regenerate by copying
`index.html` and stubbing `fetchDashboardData`/`nextSessionData` per the pattern used this
session, whenever visual verification is needed again.

---

## Recurring: manual sync cadence

Since Wellness Project sync is chat-triggered, decide a real cadence — e.g. "ask Claude to
sync every morning," or "sync before opening the dashboard." Not yet decided.

---

## P1 — remaining items

- [x] HRV readiness score + ACWR training load — done, live.
- [x] Auth/access control — done.
- [x] Next session + gear + weather panel — done (see sections above), including the
  weather-aware gear checklist (done 2026-09-12).

**P1 is now fully complete.**

---

## P2

- [x] **Air quality & UV — done 2026-09-12 (Claude Code).** Rolled into the existing top-bar
  "Weather" panel (not a separate panel — same decision-making context as weather itself)
  rather than a standalone slot. Pulls from Open-Meteo's separate Air Quality API
  (`air-quality-api.open-meteo.com`, also keyless): current US AQI + European AQI, PM2.5,
  current UV index, and today's peak UV (that API has no daily-aggregate endpoint, so peak UV
  is derived from its hourly array — "current" UV reads 0 outside daylight hours, which isn't
  useful for planning). AQI and UV each get a human-readable band (Good/Moderate/Unhealthy...;
  Low/Moderate/High/Very High/Extreme) with ok/watch/hot color coding matching the rest of the
  dashboard. See `fetchLiveAirQuality()`, `aqiBand()`, `uvBand()` near the live-weather code.
- [x] **GPX route rendering — done 2026-09-12 (Claude Code).** The Drive-hosted GPX files
  linked in calendar event descriptions are private (not "anyone with the link"), so this
  needed a new, broader OAuth scope — `drive.readonly` — added alongside `calendar.readonly`
  in `login.html`. **Requires one-time Google Cloud Console setup, same pattern as the
  Calendar API fix above: enable the Drive API for the project, add `drive.readonly` to the
  OAuth consent screen's scopes, then sign out and back in** to actually grant it. The route
  itself is fetched via `GET drive/v3/files/{id}?alt=media` (file ID pulled from either Drive
  share-link shape), parsed as plain XML (`trkpt`, falling back to `rtept`), and rendered as a
  small stylized route line (cyan path, green/magenta start/end dots) matching the dashboard's
  hologram look — deliberately not a real embedded map, which would need its own tile API/key
  and would clash with the aesthetic. See `extractDriveFileId()`, `fetchGpxRoute()`,
  `parseGpxPoints()`, `drawGpxRoute()` near the calendar code.
  - **Bug found, and fixed everywhere 2026-09-12**: the SVG route chart had no `viewBox`, so —
    like every other `chart-svg` in the file (`drawLineChart`/`drawLineChartOverlay`) — it drew
    in raw 500×150 coordinate units with no scaling to the actual rendered pixel width. This
    was a real, significant bug, not just a theoretical risk: measured render widths of
    ~324–330px against content drawn out to x=492 confirmed roughly the right **35% of every
    trend chart** (HRV readiness, ACWR, sleep hours/score, wellbeing energy/mood/stress/
    soreness) was being silently clipped off-canvas. Fixed by adding
    `viewBox="0 0 500 150"` + `preserveAspectRatio="xMidYMid meet"` to `drawLineChart` (and
    the GPX route chart); `drawLineChartOverlay` always draws onto an svg `drawLineChart`
    already ran on, so it inherits the fix without its own change. Verified by direct DOM
    measurement (chart path's rendered bounding box now sits fully inside the svg's own box).
- [x] **Sunrise/sunset → "headlamp needed" — done 2026-09-13 (Claude Code).** Added
  `sunrise`/`sunset` to the existing daily Open-Meteo fetch and a `needsHeadlamp()` check
  (strictly before sunrise or after sunset on the session's own day — a clean objective line,
  not a fuzzy "near dawn/dusk" buffer) feeding into `weatherGearItems()` alongside the
  rain/cold/heat/wind items, so it now applies to *any* outdoor session (road runs included,
  not just trail — a pre-dawn road run needs visibility too) rather than being trail-only.
  Removed the old static "Headlamp if pre-dawn/dusk start" line from the trail-vest checklist
  since it's now genuinely time-aware instead of always shown regardless of actual start time.
  One real gotcha handled: Open-Meteo returns sunrise/sunset as naive local-time strings with
  no UTC offset even with a `timezone` param set — appended Taipei's fixed `+08:00` (no DST in
  Taiwan) so parsing is correct regardless of the viewer's own browser timezone, matching
  `nextSessionData.start`, which always carries a real offset from the Calendar API. Verified
  live against real sunrise (~05:39) / sunset (~18:00) times for three cases: pre-dawn (04:30,
  headlamp shown), midday (12:00, not shown), post-sunset (19:30, shown) — plus the
  trail-vest + pre-dawn combination to confirm no duplicate/missing item after removing the
  static line.

**The original "Environmental panel" P2 item (air quality/UV, GPX route, headlamp) is now
fully complete.** Route-GPX-as-a-map (an actual embedded map rather than the stylized route
line already shipped) was never separately scoped and hasn't been revisited — flag if wanted.
Push notifications and the morning wake-time alert below are a distinct, larger scope (real
service-worker/PWA work, plus the chat-triggered-sync re-scope question) and remain open.

- [ ] **Interactive push notifications** — Web Push + Notifications API `actions` array for
  real quick-log buttons (not Google Home script notifications — confirmed insufficient, no
  button/action support). iPhone needs PWA home-screen install first (iOS 16.4+). Since sync
  is chat-triggered rather than cron-triggered, push notifications can't fire from an
  unattended sync job — they'd need to be sent as part of whatever triggers a manual sync, or
  reconsidered as a "reminder to come ask Claude to sync" mechanism.
  — **Est: 2–3 sessions (6–10h)**.
- [ ] **Morning wake-time alert** — same re-scope consideration as push notifications above.

### Researched & explicitly out of scope for now
**True auto-cast to the Google TV Streamer 4K** — confirmed no action in Google's Home
Automations API loads an arbitrary URL on a Cast/Google TV device. Would need the separate
Google Cast SDK — a genuine mini-project of its own, not included in any estimate above.

---

## P3

- [ ] **Spotify/music correlation** — novelty/texture feature, not core optimization —
  lowest priority.
  — **Est: 1 session (2–3h)**.

---

## P4

- [ ] **Partner/encounter tracking** — Supabase tables `people` + `encounters` already exist
  and are RLS-protected (see Foundation), just empty — no data-entry mechanism built yet.
  Calendar-scan-for-Flamingo-events piece reuses the same browser-direct Calendar API pattern
  already proven for sessions (see above) — genuinely less new work now than when this was
  originally scoped, since the hard part (getting Calendar data into the browser at all) is
  done. The "next-morning actionable push" piece inherits the same re-scope note as P2's push
  notifications. **Explicitly not a body marker** — it's calendar-driven like the Session
  panel, with its own trigger UI (placement still an open design decision) — see the
  handoff notes from 2026-09-13 for the full detection/insert logic.
- [x] **Daily arousal + morning-erection logging — done 2026-09-13 (Claude Code).** New
  "Loins" marker + `PANELS.arousal` (slot `arousal`), structurally copied from wellbeing's
  daily 0-10 self-report pattern (`arousal_daily` → `{dates, morningErection, arousalLevel}`
  parallel arrays, `drawLineChart` + `drawLineChartOverlay` for the solid/dashed pair).
  Anchor placed centered (`x≈0`) in the same `nx` height band as the existing Posture/pelvis
  marker (`0.40–0.50`) rather than hip-offset — pelvis's own test already starts at `x>0.6`,
  so neither marker needed to move to avoid collision. Table starts empty, so the empty-state
  path (matching the Session panel's tone) is what actually ships live: "No arousal/
  morning-wood data logged yet..."
- [ ] **Masturbation logging** — table exists (`masturbation_log`), empty.
  — **Est: 1 session (2–3h)** remaining for the P4 set (partner tracking + masturbation
  logging) — arousal is done.

---

## P5

- [x] **Illness tracking — done 2026-09-13 (Claude Code), merged with injuries as "Health
  Events."** Design finalized during handoff: `injuries` and `illnesses` stay separate tables
  (genuinely different fields — body-part/mechanism vs symptoms/diagnosis/medication — forcing
  a schema merge would lose real data) but are combined for display everywhere. Concretely:
  - **Knee marker removed entirely** (`kneeR` in `REGION_DEFS`, `PANELS.knee`) — it was
    hardcoded to one specific injury and never generalized to others, a real design flaw the
    merge fixes rather than papers over.
  - **Head marker now carries a dynamic ring** instead of every other marker's static
    `flagged` value — reuses the existing `.region-marker.flagged` CSS (same magenta
    pulse used elsewhere) but computed at build time via `hasActiveHealthEvents()`. An event
    counts as active if `status` is `active`/`monitoring`, or `resolved` with `end_date`
    within the last 7 days — a **display filter only**, nothing is ever deleted from the
    tables. Shared once as `isHealthEventActive()` rather than duplicated per table, since
    both use the same status enum. Also applied to the region-legend chip for consistency.
  - **New "HISTORY →" row** in the character sheet (second `.stat-card-labs-row`, below
    LABS) opens `PANELS.history`: the complete, unfiltered combined record — no 7-day
    cutoff — sorted by start date descending, each row prefixed `[Injury]`/`[Illness]`.
    Verified this surfaces an old (>7-day) resolved injury that correctly does *not* trigger
    the head ring, alongside a recent one that does.
  - Deliberately did **not** extend the existing RES (Resilience) score's active-injury
    penalty to include illnesses — that's a scoring-formula change, not a display merge, and
    wasn't asked for.
- [x] **Stool tracker — done 2026-09-13 (Claude Code).** No new marker — extends the existing
  Nutrition panel (Stomach marker) under a "RECENT STOOL LOG" section, same anatomical
  reasoning as the rest of that panel (digestive system). Most-recent-first list (date,
  Bristol type 1–7, discomfort 0–10 if present), read-only — no entry form yet, a real
  follow-up item, not an oversight.
  - **Also relocated hydration out of Nutrition while touching this panel**: `hydration_daily`
    was already being fetched but had no dedicated home yet (see Foundation notes), so it had
    landed inside Nutrition's "BODY" section as a stopgap. Since hydration now has its own
    Kidneys marker/panel (below), keeping it duplicated in both places would just be
    confusing — removed the Hydration range-bar/chart/gap-flag mention from Nutrition
    entirely rather than showing the same metric twice.
- [x] **Hydration display — done 2026-09-13 (Claude Code), new "Kidneys" marker + panel** (not
  originally scoped as its own P5 line item, but the same session's natural companion to
  moving hydration out of Nutrition above). Anchor placed on the left flank, same `nx` height
  band as Stomach (`0.50–0.65`) — a real front-facing mesh vertex confirmed to sit inside the
  same torso/arm gap already used for the heart/spleen hitbox fix, carved out of Stomach's own
  x-range and checked first in `REGION_DEFS` so the narrower zone takes priority. Left pane
  shows today's total against a stated (not stored — checked first, no target field exists
  anywhere) general guideline of 2500–3000ml/day; right pane's gap-flag is computed live from
  the actual logged-vs-total day ratio rather than a hardcoded snapshot that would drift stale.
  — **Est for original P5 scope: done.** Both items shipped same session as new markers,
  verified via the local mock-data harness (empty-state, active-ring, and full-history paths
  all exercised with synthetic data covering each branch).

---

## P6 — Wardrobe Manager

**Goal**: system picks tomorrow's clothing based on calendar events, planned workouts, and
weather — tracks what's been worn, what needs washing, and resets on laundry.

Tables exist (`clothing_items`, `laundry_loads`), empty, RLS-protected.

### Open design questions (still open)
1. **Rule-based vs. tag-based** outfit selection — recommend rule-based first (ships faster,
  tags can layer on later).
2. **Weather integration** — reuses the Open-Meteo pattern already live (see above).
3. **Wash-need detection** — wear count / elapsed-days threshold per category.
4. **Inventory entry burden** — real, one-time manual data-entry cost, separate from build time.

**Est: 2–3 sessions (6–10h) for the build** + separate, variable inventory-entry time.

---

## P7 — Decouple from Wellness Project (do this LAST, after everything above ships)

**Explicit sequencing decision**: finish P1–P6 first. This is a platform migration, not a
feature — deliberately kept separate so it doesn't block or get tangled with the rest of the
build. Goal: stop depending on Wellness Project's OAuth-only, chat-triggered sync and get
data flowing more directly and more automatically.

**Why this order**: Wellness Project was the right way to get started (working MCP connector,
rich already-modeled data, got the whole pipeline proven end-to-end). But it has two real,
permanent limits — no unattended API access (confirmed), and it doesn't write to Health
Connect (confirmed by the user), so it's a dead end for ever centralizing data on-device.

### Stage 1 — Zepp Mini Program → own server (stepping stone)
Real, officially-documented pattern from Zepp's own workshop content ("How To Extract Health
Data from Amazfit Smartwatches to a Web Server," zepp-health GitHub org): a Zepp OS Mini
Program (Device App + Settings App + Side Service, running on-watch/in-companion-app) reads
health data and POSTs it directly to a web server — no Wellness Project, no Google, no Health
Connect in the loop. Zepp's own sample code exists (`zeppos-samples/application/2.0/post-health-data`,
Node.js + MongoDB reference server) as a real starting point.
- **Alternative researched**: unofficial cloud-session-token access (`zepp-life-mcp` pattern
  — extract an `apptoken` from Zepp's web portal cookies). Faster to stand up, but
  unofficial/reverse-engineered and could break without warning. Mini Program path preferred
  — it's sanctioned, not scraped.
- **Est: 2–4 sessions (6–14h)** — real uncertainty, depends on Zepp OS Mini Program tooling
  quality, not yet evaluated hands-on.

### Stage 2 — Companion Android app + Health Connect (long-term hub)
A small Android app using the Health Connect SDK, reading locally-aggregated data (which —
per ROOK's documented Zepp integration — already includes Zepp data once linked once, plus
any other app already syncing to Health Connect) and forwarding it to Supabase.
- Confirmed constraint: Health Connect is on-device only — no cloud API reads it directly. A
  real Android app is unavoidable; no config-only shortcut exists.
- Can plausibly stay a **personal, unpublished, sideloaded app** — Play Console health-data
  declaration is only a hard requirement for public Play Store distribution.
- **Est: 4–8 sessions (12–25h)** — genuine Android app development, the largest single build
  on the entire roadmap.

### Explicitly ruled out
- **A pure Google Cloud / server-side app reading Health Connect directly** — confirmed
  impossible; no cloud-reachable API exists.
- **Google Health API** — real, but explicitly scoped by Google to Fitbit/Pixel Watch data;
  doesn't apply to Amazfit/Zepp.

**Est total for P7: 8–12 sessions (18–39h)** — the second-largest phase on the whole roadmap.

---

## Pn — Write-back to Wellness Project (indefinite timeline, deliberately unscheduled)

Deprioritized below P7. Originally planned as a server-side script call using a personal API
key — since that key doesn't exist, this needs real re-thinking before it's buildable at all:
either (a) writes go through the same chat-triggered pattern as reads, meaning a dashboard
button can't directly write to Wellness Project without a person approving it in a Claude
conversation, or (b) something else entirely once P7's decoupling lands and Wellness Project
may not even be the write target anymore. No time estimate given — genuinely blocked on a
design decision, not on effort.

---

## Summary — rough remaining build time

Foundation, the full dashboard merge, live weather, live calendar/session integration, and
the layout rework are all done. P1 is fully complete. P5 is fully complete (illness tracking +
stool tracker, plus the new Kidneys/hydration marker as their natural companion). P4's arousal
item is done; partner/encounter tracking and masturbation logging remain. Remaining:

| Tier | Est. hours |
|---|---|
| P2 (push notifications + morning wake-time alert need re-scope; environmental panel done) | 6–10h |
| P3 | 2–3h |
| P4 (arousal done — partner/encounter tracking + masturbation logging remain) | 2–3h |
| P5 | **done** |
| P6 (Wardrobe) | 6–10h |
| P7 (Decouple from Wellness Project — Zepp Mini Program, then Health Connect app) | 18–39h |
| **Total** | **~34–65h** |

The core "is this real" question was answered early — the pipeline works, proven with live
data, the full dashboard UI is live against it, and weather + calendar are now genuinely live
too. What's left is real product-building (P2–P4, P6, most schemas already in place) plus one
large, deliberately-last platform migration (P7).
