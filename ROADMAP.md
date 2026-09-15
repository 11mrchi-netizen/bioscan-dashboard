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
  only a fresh login does. This was a documented rough edge for a while: after roughly an
  hour, calendar calls started silently failing back to a placeholder state until the user
  signed out and back in. **Fully fixed 2026-09-13 (Claude Code) — see the "SHORT-LIVED
  GOOGLE TOKEN" section below for the real architecture** (a server-side refresh flow via a
  Supabase Edge Function, not a client-side workaround). The 2026-09-12 fix mentioned here
  previously only improved the *error message* shown when the token went stale — it didn't
  stop the token from going stale in the first place, which the Edge Function now does.
- `index.html` calls the Google Calendar API from the browser using a fresh access token
  minted on demand via `getFreshGoogleToken()` (calls the `refresh-google-token` Edge
  Function — see below) rather than the raw, short-lived `session.provider_token`. Session-type
  detection uses **colorId `'8'`** (confirmed reliable from real calendar data — cleaner than
  matching on emoji/title text, which varies).
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
  during this change, not after). **Superseded 2026-09-13** — see the Supplements
  Distribution section below; the dedicated marker/panel didn't last long before a better
  design replaced it.
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

## ✅ SUPPLEMENTS DISTRIBUTION + TRAINING MERGE — COMPLETE (2026-09-13, Claude Code)

Design finalized after further discussion: the dedicated Supplements marker/panel (added just
one round of changes earlier, see Layout Rework above) is gone — real churn, but the
anatomical-distribution approach is the better design. A `category` column was added to
`supplements` (`hormonal` / `sleep` / `anti-inflammatory` / `performance` / `systemic`) and
backfilled on all 15 existing rows — **this is now the source of truth for placement; any
supplement added later needs a category set for auto-sorting to work.**

**Placement**, via `supplementsForCategory()`:

| Category | Goes into |
|---|---|
| `hormonal` | Hormones panel |
| `sleep` | Readiness panel, sleep section |
| `anti-inflammatory` | Heart panel |
| `performance` | Training panel (new merge, see below) |
| `systemic` | Readiness panel, wellbeing section |

**One deliberate name-based exception**: Tadalafil is categorized `systemic` in the data, but
routed to the Loins panel specifically by name match (`supplementsByName(/tadalafil/i)`) — its
actual relevance is domain-specific in a way "systemic" doesn't capture. Every category query
excludes it by name so it doesn't also show up in the Readiness panel's systemic section.

Each destination panel gets a single compact line per supplement — dose + expected-outcome
text together (`supplementLinesHTML()`), not the old panel's two-pane outcome/dosage split.
Outcome text is interpretive research framing (not stored data), matched by name keyword via
`supplementOutcome()` — brand suffixes vary in the real data (e.g. "Zinc Picolinate (Swanson)")
so exact-name matching wouldn't hold up; falls back to an empty string for anything unmapped.

**Auto-hide retired supplements** uses the same 7-day-cutoff shape as the injuries/illnesses
Health Events work — written once as `isStatusCurrentlyRelevant()` and shared by both
`isHealthEventActive()` (active/monitoring, or resolved ≤7 days) and the new
`isSupplementActive()` (active, or ended ≤7 days), since the day-math is identical and only
the status enum differs. **New "SUPPLEMENTS →" character-sheet row** (repurposed
`PANELS.supplements`) shows the complete, unfiltered history regardless of the cutoff — active
first, then ended sorted by most-recent `end_date`.

Verified against the *real* live `supplements` table (pulled via the Supabase MCP connector,
not synthesized) — this caught two supplements (DIM Complex, Apigenin) that existed in the
real data but weren't in the old panel's hardcoded outcome text at all, and gave a real,
non-synthetic test of the 7-day cutoff: DIM Complex ended 2026-09-08 (5 days before the
2026-09-13 test date) correctly still shows in the Hormones panel; Apigenin ended 2026-08-30
(14 days before) correctly does not show in the Readiness panel's sleep section, while both
correctly appear in the full SUPPLEMENTS history.

**Strength + Endurance merged into one "Training" panel** as a prerequisite — needed so the
`performance`-category supplements (Creatine, Iron, L-Tyrosine) had one obvious home instead
of an arbitrary choice between two separate panels. Top bar goes from 3 cells to 2
(Training, Weather). Both panels' existing content is preserved, just combined into one
`{left, right}` panel — strength PRs + endurance this-week stats + intensity mix on the left,
all-time PRs + working-best + VO2max trend chart + performance supplements on the right.

Also removed the `spleen`/`supplements` `REGION_DEFS` entry entirely (no replacement marker —
same "remove, don't relocate the hitbox" pattern as the Knee marker removal).

---

## ✅ FIXED — short-lived Google Calendar/Drive token (fixed 2026-09-13, Claude Code)

**Root cause, confirmed via research (not a guess):** `session.provider_token` (Google's OAuth
access token via Supabase Auth) expires after ~1h, and Supabase deliberately does not store or
manage the Google-specific refresh token at all — confirmed across many independent Supabase
GitHub issues/discussions spanning years. Supabase's own JWT refresh keeps the *Supabase*
session alive indefinitely; it has nothing to do with the Google-specific token, which just
dies after an hour unless the app captures and manages Google's refresh token itself, entirely
outside Supabase's session system.

**Compounding constraint**: the Google Cloud OAuth consent screen is in **Testing** status
(deliberate, to skip Google's verification review). Per Google's own documentation, a refresh
token issued under Testing status with an external user type is only valid for **7 days**
(not the ~6-months-of-disuse expiry Google normally applies) unless the only scopes requested
are name/email/profile — not the case here, since `calendar.readonly` is requested. So even
with a proper refresh-token flow, tokens still need re-authorization every 7 days unless the
consent screen moves to Production.

**Decision made (per the handoff): build the refresh-token flow regardless** — a real
improvement even capped at 7 days — **and treat moving to Production as a separate, optional
follow-up** the user can decide on later (that step requires Google's app verification review
for `calendar.readonly`, a real process with unknown/variable turnaround, and is a manual
Google Cloud Console step Claude Code cannot perform). Not blocked on that decision.

**Architecture** — matches Supabase's own documented pattern:

1. **New table `user_google_tokens`** (`user_id` PK, `refresh_token`, `updated_at`), RLS
   restricted to each user's own row (select/insert/update). Holds *only* the long-lived
   refresh token — the short-lived access token is never persisted anywhere, requested fresh
   and discarded after each use.
2. **`index.html` captures `session.provider_refresh_token`** in `fetchDashboardData()`,
   immediately after `getSession()` — the one point where it's reliably present, right after
   a fresh sign-in with `prompt:'consent'` (already set in `login.html`). Upserts it into
   `user_google_tokens`. Safe to run on every load: on subsequent loads the field is simply
   absent and this is a no-op. `login.html` itself needed no changes.
3. **New Supabase Edge Function `refresh-google-token`** (deployed live, not just written) —
   reads the caller's own row via their JWT (never accepts a `user_id` param — a client could
   pass an arbitrary one), POSTs to `https://oauth2.googleapis.com/token` with the stored
   refresh token + `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` (Edge Function secrets — **not
   yet set, see "What's left" below**), and returns only the fresh access token + expiry.
   Distinguishes `invalid_grant` (refresh token itself is dead — the 7-day ceiling, 6-month
   disuse, or revocation) from other failures, so the client can prompt a real re-login
   specifically for that case rather than a generic error.
   - **Real bug found and fixed during this work, not just in isolated testing**: the
     function initially had no CORS headers. Worked fine in `curl` and non-browser testing,
     but failed immediately once actually loaded as a page in a browser (`Access to fetch...
     has been blocked by CORS policy`) — a genuine reminder that this class of bug only shows
     up when something actually calls the function the way it'll really be called. Fixed with
     an `OPTIONS` preflight handler + `Access-Control-Allow-Origin` on every response;
     confirmed via direct `curl -X OPTIONS` that the header is now present.
4. **`index.html`'s `getFreshGoogleToken()`** replaces every direct use of
   `window.googleProviderToken` (which no longer exists) — calls the Edge Function fresh on
   *every* calendar/drive fetch (`fetchNextSession()`, `fetchGpxRoute()`) rather than
   caching/tracking expiry client-side, deliberately avoiding a whole class of "is my cached
   token still valid" bugs. The session panel's placeholder message now branches on the Edge
   Function's own explicit error codes (`no_refresh_token` / `invalid_grant` / `not_configured`
   / `calendar_api_error`) instead of guessing from raw HTTP status — each needs a genuinely
   different fix, and the old 401-vs-403 guessing sent debugging in the wrong direction more
   than once already (see the Live Calendar section above).

**What's NOT changed**: Supabase's own session/JWT refresh — unrelated, already fine, this
only ever affected the Google-specific provider token. The Google *access* token is still
never stored anywhere persistent, only the refresh token, only in `user_google_tokens`.

**What's left — real manual steps, not something Claude Code can do:**
- **Set the Edge Function secrets** (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` — the same
  OAuth client already used for Supabase Auth's Google provider) via
  `supabase secrets set GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=...`, or the Dashboard's
  Edge Function secrets page. Deliberately not something Claude Code did or saw the values
  for — credentials like this shouldn't pass through an agent that doesn't need to hold them.
- **Sign out and back in** (existing sign-out button) once secrets are set — the current
  session predates this refresh-token capture logic, so `user_google_tokens` has no row for
  the real user yet. Same category of "needs a real re-login" issue as when the calendar
  scope itself was first added.
- Verified everything up to that point that's possible without a real Google login: the table
  + RLS (via `get_advisors` — clean), the deployed function's CORS headers and auth gate (via
  direct `curl`, both authenticated and not), and that the client-side error-handling path
  doesn't crash and shows a sensible message end-to-end against the real deployed function.
  The actual "mint a real access token from a real stored refresh token" path needs the two
  manual steps above before it can be exercised for real.

---

## ✅ PUSH NOTIFICATIONS — foundation + time-based reminders (done 2026-09-13, Claude Code)

Built per the handoff's own recommended order (steps 1–3 of 5); step 4 (encounter detection +
People/Encounters schema) and step 5 (spreadsheet migration) are explicitly deferred — see
below.

**Architecture:**
1. **`push_subscriptions` table** — per the handoff's schema, plus one addition:
   `quicklog_token` (a random per-subscription secret, `gen_random_bytes(24)` base64url,
   generated server-side on insert). RLS: standard 4-policy pattern.
2. **`sw.js`** (repo root) — `push` event shows the notification with its `actions` array;
   `notificationclick` branches on which action fired. A quick-log action (e.g.
   `morning_wood_yes`) POSTs straight to the `quick-log` Edge Function with no page open at
   all. No action (body tap) or the explicit `open_app` action focuses/opens the dashboard via
   `clients.openWindow()`.
3. **VAPID keypair** — generated locally (Node's built-in `crypto`, ECDSA P-256), per the
   user's choice to generate rather than have Claude Code create them via an Edge Function.
   Public key is embedded directly in `index.html` (safe, same trust level as the Supabase
   publishable key). **Important format note**: `npm:web-push`'s API wants the private key as
   the raw base64url `d` value, *not* a JWK blob — the JWK's `d` field is exactly that raw
   value, so no conversion was needed, but this would be an easy mistake to make by passing
   the whole JWK object where the library expects a bare string.
4. **`send-push` Edge Function** — looks up a user's `active` subscriptions, sends via
   `npm:web-push@3.6.7` (confirmed working in Supabase's Deno Edge Runtime via a throwaway
   test function before committing to this approach — hand-rolling RFC 8291 encryption myself
   was ruled out as too risky to get right without a way to test real delivery). On a 404/410
   send failure, marks that subscription `active = false` rather than retrying. **Gated to
   `service_role`-only callers**: `verify_jwt` is on (blocks unsigned requests) *and* the
   function additionally decodes the caller's JWT `role` claim and rejects anything that isn't
   `service_role` — otherwise the public anon key (embedded in every client, not a secret)
   would be enough for anyone to make this function spam push notifications to any `user_id`.
5. **`quick-log` Edge Function** — `verify_jwt` is *off* here deliberately: this is called
   from the service worker with no Supabase session available, so the opaque `quicklog_token`
   *is* the auth mechanism (per the user's chosen "small Edge Function, service-role insert"
   approach over storing a Supabase session in the service worker). It only accepts a small
   fixed enum of server-interpreted intents (`morning_wood_yes`, `arousal_low`,
   `stool_normal`, etc.) — the client sends an intent code, never a raw table/column/value, so
   a leaked token can only ever trigger one of those specific, harmless writes.
6. **`pg_cron` + `pg_net` + Vault** (both extensions newly enabled this session) — a
   `private.send_daily_reminder(reminder_type)` Postgres function checks, per user with an
   active subscription, whether today's row already has the relevant field filled in; if not,
   calls `send-push` via `net.http_post`, authenticated with the project's `service_role` key
   pulled from `vault.decrypted_secrets` (never hardcoded, never seen by Claude Code — see
   manual step below). Three cron jobs, all scheduled in UTC to land at sensible **Asia/Taipei
   (UTC+8, no DST)** local times: morning-wood reminder 09:00 local, arousal + stool reminders
   21:00 / 21:30 local.
7. **`index.html`** — new topbar button `ENABLE ALERTS` (hidden by default, hidden again once
   subscribed on that device). Deliberately *not* an auto-prompt on page load — browsers
   penalize unprompted permission requests and it's bad UX regardless. On click: requests
   Notification permission, registers `sw.js`, subscribes via `PushManager`, upserts the
   subscription row via the logged-in user's own session (normal RLS-respecting client call,
   no service-role needed here since the user is writing their own row).

**Design decision made without stopping to ask** (small enough to be an implementation detail
under the already-approved "small Edge Function, service-role insert" architecture, not a new
scope question): exact notification button semantics weren't specified in the handoff, since
Bristol stool type (1–7) and arousal level (0–10) don't reduce cleanly to one-tap buttons.
Chose one-tap-for-the-common-case: morning-wood is a real yes/no, arousal offers Low/High
(anything more granular opens the app), stool offers "Normal" (Bristol 4, the common case) vs.
"Details" (opens the app). All of `arousal_daily`'s relevant columns are nullable, so a partial
quick-log now doesn't block a fuller entry later via the dashboard. Easy to change the mapping
later — it's just the `INTENTS` table in `quick-log`'s `index.ts`.

**What's NOT built yet (deliberately, per the handoff's own scoping):**
- **Encounter-detection push** — the handoff flagged a real discrepancy that only the user can
  resolve: earlier planning assumed colorId `'4'` (Flamingo) for encounter events, but the
  user's actual, years-old production Google Apps Script detects by title starting with
  `"Meet "` (case-insensitive). Needs checking against how events are titled in practice
  *today* before picking one — Claude Code has no calendar access to verify this directly.
- **People/Encounters schema redesign** — the handoff described real spreadsheet data (168
  people, 225 encounters) with specific typed columns (Where Met, Relationship, Gender,
  Body Type, activity tag lists, per-encounter ratings, etc.) that the current loose `jsonb`
  `demographics`/`detail`/`evaluation` columns on `people`/`encounters` probably don't serve
  well. The handoff explicitly said to flag this back rather than silently redesign it —
  flagging it now: worth a real schema conversation before any encounter-tracking UI gets
  built on top of the current shape.
- **Spreadsheet migration** — explicitly out of scope for this handoff per the user's own
  planning doc ("needs further consideration").

**What's left — real manual steps, not something Claude Code can or should do:**
- **Set Edge Function secrets** `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` (e.g.
  `mailto:d.demarchi11@gmail.com`) via `supabase secrets set` or the Dashboard's Edge Function
  secrets page. The two key values are in this session's transcript from when they were
  generated — deliberately not re-shown or handled again here now that they're wired in.
- **Store the `service_role` key in Vault**, run directly in the Supabase SQL Editor (not
  through Claude Code, so the key never passes through an agent that doesn't need to hold it):
  ```sql
  select vault.create_secret('<paste service_role key from Settings → API>', 'service_role_key');
  ```
  Until this exists, `private.send_daily_reminder()` silently no-ops (logs a notice, sends
  nothing) — safe by default, but means no reminder will actually fire until this step is done.
- **Delete (or ignore) the `test-webpush-import` Edge Function** — a throwaway used to confirm
  `npm:web-push` works in Supabase's Deno runtime before committing to the approach. Harmless
  (no real data access, `verify_jwt` off), but not real project infrastructure. No MCP tool
  exists to delete an Edge Function, so this needs the Dashboard.
- **Subscribe from an actual Android Chrome device** (tap "ENABLE ALERTS" in the topbar,
  grant the permission prompt) — this is the only way to get a real subscription row into
  `push_subscriptions`, and the only way to actually verify a push arrives with working action
  buttons. Once secrets + Vault are set, trigger a real test on demand from the SQL Editor
  without waiting for the next scheduled cron time:
  ```sql
  select private.send_daily_reminder('morning_wood');
  ```

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

- [x] **Interactive push notifications — done 2026-09-13 (Claude Code), foundation +
  time-based reminders.** Full write-up below. The re-scope concern above (chat-triggered sync
  can't fire pushes) turned out to be moot — this doesn't route through the Wellness Project
  sync at all; `pg_cron` calls the `send-push` Edge Function directly on its own schedule,
  independent of any chat session.
- [ ] **Morning wake-time alert** — not yet built; same infrastructure (cron + `send-push`)
  would carry it once there's a concrete trigger condition (currently unspecified — needs
  scoping, e.g. tied to actual wake time from wearable sleep data vs. a fixed clock time).
- [ ] **Encounter-detection push (calendar-driven) + People/Encounters schema redesign** —
  deliberately deferred, see "Push Notifications — what's NOT built yet" below.

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
  Calendar-scan-for-encounter-events piece reuses the same browser-direct Calendar API pattern
  already proven for sessions (see above) — genuinely less new work now than when this was
  originally scoped, since the hard part (getting Calendar data into the browser at all) is
  done, and the push-notification *infrastructure* (`send-push`, `quick-log`, `sw.js`) is now
  also done and ready to reuse. Still blocked on the detection-signal discrepancy and the
  People/Encounters schema redesign — see the Push Notifications section above.
  **Explicitly not a body marker** — it's calendar-driven like the Session panel, with its own
  trigger UI (placement still an open design decision) — see the handoff notes from
  2026-09-13 for the full detection/insert logic.
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
**Superseded by P8's detailed build sequence below** (this stage used to gesture at "a small
Android app" abstractly; P8 is that same app, now scoped step-by-step and partially built) —
its own hour estimate has moved to P8's row in the summary table to avoid double-counting. The
constraints that motivated this stage still stand:
- Confirmed constraint: Health Connect is on-device only — no cloud API reads it directly. A
  real Android app is unavoidable; no config-only shortcut exists.
- Can plausibly stay a **personal, unpublished, sideloaded app** — Play Console health-data
  declaration is only a hard requirement for public Play Store distribution.

### Explicitly ruled out
- **A pure Google Cloud / server-side app reading Health Connect directly** — confirmed
  impossible; no cloud-reachable API exists.
- **Google Health API** — real, but explicitly scoped by Google to Fitbit/Pixel Watch data;
  doesn't apply to Amazfit/Zepp.

**Est total for P7 (Stage 1 only — Stage 2's estimate now lives in P8): 2–4 sessions (6–14h)**.

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

## P8 — Mobile App ("Field Terminal", native Android)

**Numbering note, flagged rather than silently resolved**: the planning docs driving this work
(`mobile-app-scoping.md`, `mobile-app-implementation-roadmap.md` — both live outside this repo,
on the user's own machine, not committed here) refer to "P3 (mobile UI)" and "P4 (Health
Connect)" as their own scope boundary, but this ROADMAP.md's actual P3 (Spotify correlation) and
P4 (partner/encounter tracking) are unrelated existing items. Filing this as a new **P8** instead
of overwriting either — flag back if a different numbering/merge was actually intended. Those
two docs also reference three further companion documents (`claude-code-setup-prompt.md`,
`feasibility-assessment.md`, `claude-code-new-domains-handoff.md`) — **found and read as of
2026-09-14** (user supplied them; they'd only ever existed outside this repo). All three turned
out to describe/confirm work already reflected elsewhere in this file rather than surfacing
anything that changes P8's own sequencing:
- `claude-code-setup-prompt.md` — general architecture/credential rules, already consistent
  with how this project has operated throughout.
- `claude-code-new-domains-handoff.md` — the stool/hydration/arousal/health-events/supplements
  -distribution work this file already documents as done (see the relevant ✅ sections above);
  directly useful once mobile Step 10 (Injuries & Illness sub-tab) is reached, since it confirms
  the web version's exact merge/7-day-hide logic to mirror.
- `feasibility-assessment.md` — relevant to Phase G's Step 16 (the rMSSD-vs-SDNN question is
  confirmed still genuinely open, not resolved by this doc — needs inspecting the raw Health
  Connect record once direct access exists). Also names three schema additions explicitly
  **independent of this mobile roadmap** (sleep bedtime/wake/stage columns for SRI, a computed
  `session_load` for real CTL/ATL/TSB, draw-condition + OSTRC-H2 columns) — not actioned here,
  flagged as a possible separate near-term task if wanted.

**Environment constraint, confirmed not assumed**: as of Steps 1–2, this machine had no Android
SDK, Gradle install, or emulator/device (checked directly: `java -version` succeeded via a
JRE-only Temurin install; `gradle`, `adb`, and Android Studio were all absent). **Android Studio
is now installed** (as of Step 3) but its first-run setup — downloading the SDK/platform-tools,
creating a virtual device — hadn't been run yet, so Claude Code still cannot compile or run this
project to verify any step's "done when" criterion the way it could for, say, a deployed Edge
Function. Every step below needs a real build-and-run check in Android Studio before being
trusted as actually working, not just as correctly written.

### ✅ Step 1 — Android project scaffold (done 2026-09-14, Claude Code)
Native Kotlin + Jetpack Compose project under `android-app/` (confirmed still the current
recommended standard over XML layouts at write time). Package `com.bioscan.fieldterminal`,
`minSdk 26` (chosen up front for Health Connect's Phase G requirement, not just Step 1's own
needs), Supabase Kotlin client (`io.github.jan-tennert.supabase`, confirmed current module
names — `auth-kt`/`postgrest-kt`, not the older `gotrue-kt`) + Navigation Compose wired into
Gradle. Ships to a blank launch screen only, per the step's own scope. The Gradle wrapper JAR
is deliberately not committed (`.gitignore`) — Android Studio generates it on first open, which
is normal for a hand-written scaffold, not a sign of something missing (see
`android-app/README.md`).

### ✅ Step 2 — Native Google auth (done 2026-09-14, Claude Code)
The roadmap doc's own flagged "real open question, don't guess" — whether the existing web
OAuth client works as-is for native Android, or a second Android-specific client is needed —
resolved via direct research against current (Sept 2026) Google and Supabase documentation,
not assumed:
- **Confirmed: a second, Android-specific OAuth client is required**, keyed to the app's
  package name + signing certificate SHA-1. The existing web client ID stays in use too (it's
  what Credential Manager's `GetGoogleIdOption` actually wants as its "server client ID" —
  counterintuitively, not the new Android client ID, which exists only so Google recognizes the
  calling app).
- Generated a real debug-keystore SHA-1 fingerprint directly (`keytool`, via the JRE already on
  this machine, into the standard `~/.android/debug.keystore` location so Android Studio
  reuses it rather than generating a conflicting second one) — a concrete value ready to paste
  into Google Cloud Console rather than a placeholder. See `android-app/README.md`'s "Manual
  setup required" section for the exact value and the three remaining manual steps (create the
  Android OAuth client, register both client IDs in Supabase's Google provider settings, add
  the web client ID to a local, gitignored `local.properties`) — none of them skippable by
  Claude Code, same category as this project's other Google Cloud Console / secret-setting
  steps.
- Implementation (`GoogleAuthManager.kt`) uses Android's Credential Manager API + a
  `supabase.auth.signInWith(IDToken)` exchange — every import path and the raw-vs-hashed-nonce
  handling (hashed nonce to Google, raw nonce to Supabase, which re-hashes to verify) was
  checked against Supabase's own current sample rather than pattern-matched from memory, since
  getting the nonce direction backwards would fail silently in a way that's easy to misdiagnose.
  Sign-out (`GoogleAuthManager.signOut()`) mirrors the web dashboard's own sign-out button and
  its documented reason for existing (Foundation section above): a valid session skips straight
  past any sign-in screen, so signing out is the only way to re-trigger consent, e.g. after a
  new OAuth scope is added later.
- **✅ Verified end-to-end on a real emulator (2026-09-15)**: real Google account added, tapped
  "SIGN IN WITH GOOGLE," got a real Supabase session back. Step 2's actual "done when" criterion
  — the whole reason this step existed — is now genuinely met, not just built.

### ✅ Step 3 — Navigation skeleton (done 2026-09-14, Claude Code)
Real 4-tab structure (`FieldTerminalNavHost.kt`, Navigation Compose) + Status's 5-sub-tab
segmented control (`StatusScreen.kt`), all placeholder content per the step's own scope —
mechanism only, no visual styling (that's Step 4).
- **Tab order discrepancy found and resolved by following the higher-fidelity source**:
  `mobile-app-scoping.md`'s prose lists tabs as "Status, Log, Map, Settings," but the actual
  pinned `/design/PipNavA.dc.html` mockup — the "high fidelity... final" committed design per
  `design/README.md` — renders them Status, Map, Log, Setup. Went with the mockup's order
  (reads as the deliberate decision; the scoping doc's order reads as incidental prose
  ordering) — flagged in code (`TopLevelTab.kt`) rather than silently picked, correct if the
  prose order was actually intended.
- Status's 5 sub-tabs are plain composable state under the Status screen, not a nested nav
  graph — matches the implementation roadmap's explicit instruction that these are "a
  segmented-control layer... not a separate nav level."
- Sign-out (Step 2) moved from a bare screen into the Setup tab — its real long-term home per
  `mobile-app-scoping.md`'s Settings scope, rather than a temporary standalone screen.
- Bottom-nav icons are generic Material placeholders (Home/LocationOn/List/Settings), not the
  mockups' exact inline-SVG shapes (pulse line, map pin, three lines, gear) — deliberately
  deferred to Step 4, which recreates those precisely; this step only needed *a* recognizable
  icon per tab.
- **Not yet verified against a real device** — same constraint as Steps 1–2.

### ✅ Step 4 — Visual design system applied (done 2026-09-14, Claude Code) — first real build-verified step
Extracted `design/README.md`'s full token table (colors, JetBrains Mono / Saira / Saira
Condensed typography, spacing, "radius 0 everywhere") into `ui/theme/` and applied it across
every screen Step 3 built — header, sync pill, sub-tab chips, bottom tab bar (including the
mockups' exact inline-SVG icon shapes, not generic placeholders anymore), sign-in/sign-out
buttons. Fonts bundled as real files (`res/font/`) fetched from Google's official open-source
fonts repo, per the user's explicit choice over the fragile Downloadable-Fonts-API alternative
(asked first since fetching files wasn't something to just do silently) — variable fonts for
JetBrains Mono/Saira (only format upstream ships), a static file for Saira Condensed Bold
(matches the design's "weight 700 only" use).

**This is the first step actually build-verified, not just written** — Android Studio was
installed by this point, and running a real `./gradlew :app:assembleDebug` (using Android
Studio's own bundled JBR 25 as the build JVM, no separate JDK existing on this machine) surfaced
a chain of five genuine toolchain issues, each fixed via the real error rather than guessed —
full account in `android-app/README.md`'s "Toolchain" section. Headline finding: **the Step 1–3
version pins (AGP 8.6.0, Kotlin 2.0.20, Gradle 8.9, compileSdk 35) didn't survive contact with a
real build** — this machine's very recent JBR 25 build JVM exposed a real Kotlin compiler bug
(fixed upstream in 2.1.20+), which cascaded into AGP 9.x's built-in-Kotlin restructuring and a
Compose-BOM-driven compileSdk bump, none of which were knowable without actually attempting the
build once a real environment existed. Bumped to AGP 9.4.0 / Kotlin 2.4.20 / Gradle 9.7.1 /
compileSdk 37 (targetSdk deliberately kept at 36 — no reason to opt into newer runtime behavior
yet). The Gradle wrapper jar/scripts are now committed for real (Step 1's "let Android Studio
generate it" plan reversed now that a verified one exists — committing it is the actual standard
Gradle convention, not something to keep regenerating blind).
- **Build succeeded**: `app-debug.apk` produced.

### ✅ Step 4 follow-up — real crash fixed, verified live on an emulator (2026-09-15, Claude Code)
The APK above installed but **crashed immediately on launch** once the user actually ran it —
`NoClassDefFoundError` on `io.ktor.client.plugins.HttpTimeout`, thrown inside Supabase's own
HTTP client init. Root cause (confirmed via `adb logcat`'s crash buffer, then reading
`supabase-kt-android`'s real Gradle module metadata, not guessed): the explicit
`ktor-client-android:2.3.12` pin was two major Ktor versions behind what `supabase-kt` 3.8.0
(the BOM version Step 4 had bumped to) actually requires internally (Ktor 3.5.1). Bumped the
Supabase BOM 3.0.0 → 3.8.0 and `ktor-client-android` to 3.5.1 to match.
- Separately, Android Studio itself ran `updateDaemonJvm` on the project (pinning the Gradle
  daemon's own JVM to JBR 21 via the `foojay-resolver-convention` plugin) — a cleaner, more
  permanent fix for the same "JDK 25 is too new for parts of this toolchain" problem Step 4's
  Kotlin-version bump patched at a different layer. Kept both; not in conflict.
- **Verified for real this time, not just compiled**: installed on a live emulator, launched
  without crashing, screenshotted — ground/amber/mono-type rendering matches `/design/`'s
  tokens. Tapped "SIGN IN WITH GOOGLE" for real: it correctly reaches Google Play services'
  Credential Manager and returns `NoCredentialException: No credentials available` — expected
  and correct at that point, since the emulator had no Google account yet, not a bug in this app.
- **Buttons reported as "unresponsive" — root cause was missing press feedback, not a broken
  hitbox.** Confirmed via logcat that taps were reaching `GoogleAuthManager` correctly even
  before this fix — `indication = null` (needed to kill Material's bouncy ripple, which the
  design's "no spring, no scale bounce" motion rule rules out) had removed all visual feedback,
  not just the bounce. Fixed with an instant, non-animated amber background tint on press
  instead — verified with a held-down screenshot showing the tint appear. Also brought
  `AmberButton` and the Status sub-tab chips up to Android's 48dp minimum touch target (both
  were 30–38dp, padding-only) as a real secondary fix, regardless of which one was the actual
  cause.
- **First "no account" diagnosis was a real finding, not a guess**: `adb shell dumpsys account`
  showed `Accounts: 0` on the running emulator despite the user having added one — the account
  had evidently landed on a different AVD, or the add-flow hadn't actually completed. Once
  re-added on the correct running emulator and confirmed present, the sign-in flow completed
  for real: a genuine Supabase session, not just a reached-Play-Services partial success.
  **Step 2's actual "done when" criterion is now genuinely met.**

### ✅ Step 5 — Status landing screen, real readiness data (done 2026-09-15, Claude Code)
**Launch-screen choice made by the user** (flagged as unresolved in `mobile-app-scoping.md`,
not silently picked): **3d — Body console**, over 3b (system grid) and 3c (day line).

Ported `index.html`'s `computeHRVReadinessSeries()`/`readinessBand()` and
`isStatusCurrentlyRelevant()`/`isHealthEventActive()` 1:1 into `domain/Readiness.kt` — same
trailing-baseline z-score math, same bands, same 7-day-after-resolution health-flag cutoff.
`data/StatusRepository.kt` queries the real `wearable_daily`/`sleep_daily`/`injuries`/
`illnesses` tables (no `user_id` filter needed — RLS already scopes to the signed-in user, same
as the web dashboard relies on) and `ui/screens/status/BodyConsole.kt` recreates the mockup's
inline-SVG figure via Canvas + overlaid real `Text` (own font/theme, not Canvas-drawn text).

**Two things deliberately don't match the mockup, flagged rather than silently faked:**
- **No numeric "82 READY" score.** The web dashboard never computes a 0-100 readiness number —
  only the categorical z-score band (LOW/REDUCED/NORMAL/PRIMED). The mockup's "82" has no
  defined formula anywhere in this project, and inventing one would be exactly the kind of
  composite/multi-stream score `mobile-app-implementation-roadmap.md`'s own non-goals section
  rules out. Shows the real band text instead (e.g. "NORMAL").
- **Fuel/Water/Supp dials and the Next-up bar are honest placeholders, not fake numbers.**
  Step 5's own roadmap text only scopes `wearable_daily`/`sleep_daily` — the dial row needs
  daily calorie/hydration *targets* (confirmed via grep: none exist anywhere in this project,
  web dashboard included) and a supplement taken/logged mechanism (doesn't exist until Log's
  add-entry flow, Step 12); Next-up needs Calendar integration (Step 14). Building these with
  invented targets would look real while being fabricated — left as "—" placeholders instead,
  matching Step 3's own precedent for out-of-scope content.

**Verified on the emulator with real Supabase data, not just compiled**: readiness band
"NORMAL", real `HRV 64`/`RHR 54` on the engine pin, real sleep `6:42`, and a real flagged health
-event pin (this account's one existing injury row is evidently still within its 7-day
resolved-cutoff window) — the pin correctly does not appear when nothing is active, per the same
display-filter logic as the web dashboard's Head-marker ring. Cross-checking against what the
web dashboard shows for the same day (Step 5's actual "done when" criterion) is still worth
doing explicitly, but every piece independently matches the ported logic's expected behavior.

### ✅ Step 6 — Nutrition/Hydration sub-tab (done 2026-09-15, Claude Code)
Real data from `meals`/`hydration_daily`. `domain/Nutrition.kt` ports `index.html`'s
meals-→daily-aggregation 1:1 (same UTC-calendar-date grouping via the timestamp string's first
10 characters, same per-day sums) — "today" means the most recent day with any logged data,
matching the web dashboard's own established convention (`nutrition.cal[length-1]`), not a
strict calendar-date filter that would show "0 kcal" before today's first meal is logged.

**Same honest-data pattern as Step 5, applied again**: the committed mockup
(`design/Field Terminal Mockups.dc.html`, "Status · Nutrition & hydration") shows every bar
against a personal target — 2750 kcal, 180g protein, 3.0L water — and none of these exist
anywhere in this project's real data (confirmed by grep; the web dashboard's own
Kidneys/Hydration panel explicitly discloses "No stored personal target — 2500–3000 ml/day is a
common general guideline, not a specific goal pulled from any table"). Built the same generic
sanity-range bars the web dashboard's Nutrition/Hydration panels actually use instead (0-3500
kcal watch<1800, 0-220g protein, 0-450g carbs, 0-180g fat, 0-4000ml water) with one honest
disclosure line, rather than inventing target numbers the mockup implies but nothing backs.
Also skipped the mockup's implied meal-by-meal list — neither the design's own prose scope nor
Step 6's roadmap text calls for one, and per-meal detail is really Log's job (Step 11's unified
feed already covers every meal chronologically).

Added a 7-day calorie trend (relative to the week's own max, not a fixed target) using the same
mini-bar-histogram motif already established by the launch-screen mockups' "TRAIN tile."

**Verified on the emulator with real Supabase data**: 1170 kcal (correctly shown in alert-red,
below the 1800 watch threshold), real macros (100g/130g/30g), real hydration (0.8L, 2/8 segments
filled), and a real 7-day trend with varying bar heights.

**Remaining**: Steps 8–15 per `mobile-app-implementation-roadmap.md` (Status's other 2 sub-tabs,
Log, Map, Settings), then Health Connect in Phase G. Not started.

### ✅ Step 7 — Training sub-tab (done 2026-09-15, Claude Code)
**Real finding, not an assumption**: Step 7's own text says to check whether the web dashboard's
Strength+Endurance merge has landed and mirror whatever's actually live. Reading `index.html`'s
`PANELS.training.render()` directly (not just its "SOURCE: LIVE" label) found that **almost none
of it is actually live** — every strength number (squat/deadlift/pull-up PRs, all with specific
dates like "Apr'25") and even the endurance figures ("17.9km this week," "+9.4% pace trend") are
hardcoded literals from a one-off narrative pass, not computed from anything. Confirmed further:
`fetchDashboardData()` already fetches real rows into `DASHBOARD_DATA.runs`, but the Training
panel's `render()` never reads from that variable at all — the one genuinely live number in the
whole panel is VO2max (`wearable.vo2`).

Built the mobile Training sub-tab against the *real* data instead of porting the web's stale
hardcoded numbers as "the current source of truth" (which Step 7's literal wording could have
been read as sanctioning) — `domain/Training.kt` computes this-week distance, all-time longest
run, and 4-week average directly from `runs` (the same table the web dashboard fetches but
ignores), plus VO2max via the same "latest non-null" logic index.html uses. **Strength shown as
an honest, explained empty state** — no `exercises`/lifts table exists in Supabase at all; the
per-lift history the web panel's label implies is only ever pulled manually in a Wellness
Project chat, never synced anywhere queryable. This is arguably a bug worth fixing in the web
dashboard itself at some point (wire its Training panel to the `runs` data it already fetches) —
flagged here, not fixed, since it's outside this mobile roadmap's scope.

Promoted the `Card`/`RangeBar` pieces built for Step 6 into `ui/components/` — this is the third
sub-tab wanting the identical shape, no longer worth keeping screen-local.

**Verified on the emulator with real Supabase data**: 15.4km this week, 6:27/km avg pace, 17.59km
longest run, 26.2 km/wk 4-week average, VO2max 55.4 — all independently computed from real rows,
matching neither the web dashboard's stale numbers nor any fabricated substitute.

### ✅ Step 8 — Supplements sub-tab (done 2026-09-15, Claude Code)
Real data from `supplements`. Ports `index.html`'s `isSupplementActive()`/`supplementOutcome()`
1:1 into `domain/Supplements.kt` — same active-or-ended-within-7-days display filter (reusing
Step 5's already-generic `isStatusCurrentlyRelevant()` rather than re-deriving it), same
outcome-by-name keyword map. Unlike the web dashboard, this screen does **not** exclude
Tadalafil or split supplements across body-region panels — that distribution was a web-specific
design decision from an earlier chapter, outside Step 8's own "active/ended list, condensed"
scope. Every supplement shows here in one list, same pattern as every other mobile sub-tab.

**Verified on the emulator against Step 8's own explicit test**: "confirm a supplement whose
end_date is >7 days old is excluded from the active view" — this account's real data already
had exactly that case (Apigenin, ended 2026-08-30, 16 days before today) and it correctly
appeared under ENDED, not ACTIVE, with no contrived test data needed. All 14 active
supplements' outcome text matched correctly by name regex against real product names
(e.g. "Activated B Complex (Swanson)" → "Methylation cofactors", "Iron (California Gold
Nutrition)" → "RBC production support").

### ✅ Step 9 — Labs sub-tab (done 2026-09-15, Claude Code)
**Same finding as Step 7, a third time**: `index.html`'s Labs panel is entirely hardcoded prose
(every marker, value, and range typed literally) with a completely empty `draw(){}` — despite
`DASHBOARD_DATA.labs` already holding the real structured `lab_draws`/`lab_results` data. Step
9's own scope explicitly wants the real tables with reference ranges shown (which the hardcoded
panel mostly doesn't display anyway), so built directly against Supabase instead of the web
panel's narrative.

`domain/Labs.kt` merges markers by name across the earliest and latest draw — a marker tested
in only one draw shows "—" for the other rather than being dropped or faked, which this
account's real 2-draw data genuinely needs (many markers are one-draw-only). Direction arrows
and value coloring come from real computable facts (numeric change, and the latest draw's own
stored `flag`), not the mockup's seemingly hand-picked highlighting choices.

**Verified on the emulator with real data — both of Step 9's explicit criteria met**: real draw
dates (14 JAN / 25 APR) with every marker and unit-specific reference range rendering
(`g/dL · 3.5–5`, `ng/mL · 0–7`, etc.), and markers correctly showing "—" for whichever draw
didn't test them. Cross-checked against the web dashboard's own hardcoded narrative text (e.g.
"DIGESTIVE/LIVER — JAN ONLY") — the independently computed merge landed on exactly the same
per-marker draw coverage the web version describes by hand, a good sign the logic is right.

### ✅ Step 10 — Injuries & Illness sub-tab (done 2026-09-15, Claude Code) — Status tab complete
Real data from `injuries`/`illnesses`, merged for display only (genuinely different fields
underneath — `domain/HealthEvents.kt`), same approach as `index.html`'s real (not hardcoded)
`PANELS.history`. Open = active/monitoring, shown first; resolved shows **unfiltered** below,
matching both `PANELS.history`'s "complete, unfiltered" framing and the committed mockup's own
closing line ("cleared events drop out of Status after 7 days and stay here").

**One deliberate deviation from the mockup's copy, checked against the real spec rather than
assumed**: the committed mockup shows an open injury with "DAY 4/7... AUTO-CLEARS THU 17 SEP
UNLESS RE-FLAGGED" — implying open events auto-resolve after 7 days. Checked
`claude-code-new-domains-handoff.md` section 4 (the actual governing spec) directly: no such
rule exists anywhere in this project. An active/monitoring event stays open indefinitely until
someone manually resolves it; the 7-day cutoff only ever applies to *already-resolved* events'
display window (Step 5's `isHealthEventActive`, reused here). Built the real rule, not the
mockup's flavor text — shows "DAY N" as an honest fact (days since reported), never a fabricated
countdown-to-auto-clear.

**Verified on the emulator, landing directly on Step 10's own explicitly-requested edge case**:
this account's real data is exactly the sparse case the roadmap flagged in advance (1 injury,
0 illnesses) — confirmed "0 OPEN" correctly hides the Open section entirely rather than
rendering awkwardly, and the one resolved injury (cleared 2026-09-08, exactly 7 days before
today) shows correctly in RESOLVED. Cross-checked against Step 5's Body Console: that resolved
injury *also* still shows as a "1 FLAG" ring there, since 7 days since resolution sits exactly
on `isHealthEventActive`'s inclusive cutoff (`daysSince <= 7`) — two different, both-correct
answers to two different questions ("is this literally open" vs. "is this still ring-worthy"),
not a bug.

**Status tab is now fully complete** — launch screen (Step 5) + all 5 sub-tabs (Steps 6–10), all
real Supabase data, no fabricated numbers anywhere. Phase C, done.

### ✅ Step 11 — Log tab, unified read-only feed (done 2026-09-15, Claude Code)
Real data merged from six tables (`meals`, `runs`, `sleep_daily`, `arousal_daily`, `stool_log`,
`encounters`) into one descending-time feed, day-grouped with TODAY/YESTERDAY headers —
`domain/Log.kt`'s `buildLogEntries()`. `runs`/`sleep_daily`/`arousal_daily` only store a `date`,
no time-of-day, so same-day entries get a fixed nominal time (e.g. runs at 07:00) purely for
stable sort order within the day — documented in code as not a factual time claim, since these
tables have no real time-of-day column to draw from.

**Step 11's own flagged open question — pagination — resolved concretely**: fetch a bounded
window (60 rows per source, well past this account's real volume) once, merge + sort
client-side, then reveal 20 entries at a time via a real "LOAD OLDER" button
(`data/LogRepository.kt`, `ui/screens/LogScreen.kt`). Not infinite-scroll, not a second network
round-trip per page — one fetch, windowed client-side.

**Two entry types deliberately omitted, not faked**: supplement-taken confirmations and
freeform notes appear in the mockup's Log examples but have no backing Supabase table yet: they
simply don't appear rather than being invented. `encounters` is queried but currently empty for
this account, contributing zero entries — expected, not a bug. *(Notes gained a real table in
Step 12 below — this line is left as the accurate record of Step 11's own scope at the time.)*

**Verified on the emulator**: "113 ENTRIES" total, correct day-grouping and descending
within-day order, real meal descriptions/macros, real run distance/duration/HR, real sleep
hours/scores. Tapped "LOAD OLDER" and confirmed it revealed entries beyond the initial 20 (the
"11 SEP" day-group grew to include a dinner and a sleep entry not shown before) while the process
stayed alive (`pidof` unchanged) — `visibleCount` incrementing correctly.

### ✅ Step 12 — Add-entry flow, plus edit/delete and running totals (done 2026-09-15, Claude Code)
**Scope grew beyond the roadmap's own Step 12 text during this step**, at the user's explicit
request mid-build: the roadmap only asked for "+" → type picker → minimal form → write. The user
also asked for (a) a way to undo/edit a logged entry, since add-only has no recovery from a
mistake, and (b) running totals (calories/macros, distance) over 1D/7D/30D/90D windows, visible
in their respective sub-tabs. Both are covered below alongside the original scope.

**Add flow** (`ui/screens/AddEntrySheet.kt`, `data/AddEntryRepository.kt`): "+" FAB on the Log tab
opens a type picker (Training/Food/Drink/Encounter/Stool/Arousal/Note), each with a minimal form
writing straight to the real table Step 11 already reads. One real gap surfaced immediately:
Notes has no backing table anywhere in this project (confirmed via `list_tables`) — asked the
user directly rather than guessing, and per their choice, added a small `public.notes` table
(`user_id`, `occurred_at`, `text`) via a real migration, mirroring `stool_log`'s existing RLS
policy shape exactly. Notes now also appear in the Log feed (Step 11's `buildLogEntries()`
extended to accept them).

**Two real schema findings shaped the writes**: `hydration_daily` and `arousal_daily` both carry
a genuine `unique(user_id, date)` index (confirmed directly against `pg_indexes`, and consistent
with the existing quick-log Edge Function's own upsert-on-conflict usage for both) — one row per
day, not a per-event log. `addTraining`/`addFood`/`addStool`/`addEncounter`/`addNote` are plain
inserts; `addDrink`/`addArousal` upsert on `(user_id, date)`. `addDrink` specifically accumulates
onto today's existing total (a human contribution — see below) rather than overwriting it, since
someone drinks water in several small amounts across a day, not one final number.

**Human contribution**: `addDrink`'s accumulate-vs-overwrite decision was posed as a Learn by
Doing exercise (the fetch-existing-row scaffolding was built, the combine-and-write step left as
`TODO(human)`). The user didn't write the snippet directly — instead they came back with two new
feature requests (edit/delete, running totals), which made the drink semantics question moot on
its own terms: with edit now available, a bad accumulate has a real fix path, so accumulate was
the safe default to implement directly rather than block on. Resolved that way, then built both
requested features.

**Edit/delete flow** (`EntryActionSheet` and `EditEntrySheet` in `AddEntrySheet.kt`): tapping any
Log entry opens EDIT/DELETE. DELETE asks for confirmation inline (no second popup) then removes
the row by id. EDIT re-fetches the row fresh from Supabase by id — deliberately not reconstructed
from the feed's already-formatted headline/detail strings — and opens the same form used for
adding, pre-filled, wired to a matching `update*` function instead of `add*`. Every `update*`
takes the entry's original date/timestamp explicitly so editing a past entry can't silently move
it to today. `updateDrink` overwrites the day's total outright (unlike `addDrink`'s accumulate) —
editing means "this number was wrong," not "another drink happened." Sleep has no add form (it
was never one of the picker's types), so it's delete-only, matching what actually exists.
Required adding real `id` columns to every `LogModels.kt` row type and selecting them in
`LogRepository.kt` — needed for `update`/`delete` to target the right row.

**Running totals** (`domain/Totals.kt`'s shared `TotalsPeriod` enum, `ui/components/PeriodToggle.kt`):
a DISTANCE TOTALS card on the Training sub-tab and a NUTRITION TOTALS card on Nutrition/Hydration,
each with a 1D/7D/30D/90D toggle. Computed entirely client-side from data the screens already
fetch (`sumDistanceKmSince()` already existed from Step 7; added `sumNutritionSince()` alongside
it using the identical windowing convention) — no new query fires on switching periods.
`TrainingRepository`/`NutritionRepository`'s fetch limits were widened (200/500 rows) as a
row-count safety margin for a 90-day window, not a real date filter — acceptable while this
account's real volume is in the dozens, same tradeoff Step 11's `FETCH_LIMIT_PER_SOURCE` already
made.

**Verified end-to-end on the emulator, including direct Supabase reads to catch UI/reload timing
gaps that a screenshot alone would have misread as bugs**: added a Drink entry (500ml) — confirmed
a new `hydration_daily` row (not an update) since no row existed yet today, appeared correctly in
the Log feed at its 12:00 nominal time with the right day-total headline. Edited it to 750ml —
confirmed via direct SQL the row was overwritten to exactly 750 (not accumulated to 1250),
matching `updateDrink`'s designed behavior, and the feed reflected it after reload. Deleted it —
confirmed the row was gone from Supabase and the Nutrition/Hydration screen's own hydration card
correctly fell back to the next-most-recent real row (2026-09-11, 0.8L) once the deleted one was
gone. Verified the type picker renders all 7 types and the action sheet's EDIT/DELETE (with
inline confirm) render correctly. Verified both totals cards: Training's 7D→30D toggle went from
54.2km to 153.1km (a real, larger number over the wider window); Nutrition's 7D total showed
17955 kcal / 1080g protein / 2018g carbs / 610g fat across "7 days with logged meals" — a
plausible real aggregate, not a placeholder.

### ✅ Step 13 — AI photo-based nutrition estimation (done 2026-09-15, Claude Code)
**Deviates from the roadmap's own Step 13 text by explicit user request**: the roadmap called for
Claude's vision API; the user asked for Google's Gemini API instead (larger free tier), plus two
things the roadmap didn't mention — a Setup-screen field to paste the API key, and a note that
barcode scanning could be useful "at times." Built the Gemini flow in full; barcode scanning is
flagged below as a real but separately-scoped follow-up, not built here.

**API key storage** (`data/GeminiApiKeyStore.kt`): local to the device only, never synced to
Supabase — unlike the Google Calendar refresh token (`user_google_tokens`), which has to live
server-side for an Edge Function to use, this key is only ever used for a direct client → Gemini
call, so there's no server-side reason to store it centrally. `androidx.security:security-crypto`
(EncryptedSharedPreferences) would be the obvious library, but its 1.1.0 stable release
(confirmed directly against the AndroidX release notes, not assumed) deprecated the whole API in
favor of using Android Keystore directly — so that's what this does: an AES-GCM key generated
inside the hardware-backed AndroidKeyStore encrypts the pasted key before it touches plain
SharedPreferences. A restored/reinstalled app can't decrypt an old ciphertext (the Keystore entry
doesn't survive a device change) — treated as "no key set" rather than a crash, since re-pasting
is the only real recovery anyway.

**Vision call** (`data/NutritionEstimationRepository.kt`): reuses the Ktor HTTP client already
pulled in by supabase-kt rather than adding a separate Google AI SDK dependency. Model id
(`gemini-3.8-flash`) and the REST request/response shape (`inline_data`/`mime_type` for the photo,
`response_mime_type`/`response_schema` for structured JSON output) were confirmed live against
Google's own API docs rather than assumed — a wrong model id 404s at runtime with no compile-time
warning, so this wasn't worth guessing. Structured output means the response is real JSON
(calories/protein_g/carbs_g/fat_g/food_description) rather than prose to parse. Photos are
downscaled to 1024px/JPEG-80 before upload (`util/ImageUtils.kt`) — bounds both the request size
and this app's mobile-data usage; food-estimation accuracy doesn't need full camera resolution.
The prompt explicitly tells the model not to read any visible nutrition-label text, matching the
roadmap's own note that this instruction improves reliability when no label is present.

**Food form integration** (`ui/screens/AddEntrySheet.kt`): an "AI PHOTO ESTIMATION" section
appears on the Food form only when a key is saved (otherwise a one-line hint points at Setup) —
this reads live from `GeminiApiKeyStore` each time the sheet opens, no separate settings sync
needed. TAKE PHOTO (camera, via a new `CAMERA` permission + a `FileProvider` declaration for the
capture Uri) and CHOOSE PHOTO (gallery, via the modern `PickVisualMedia` photo picker, which needs
no permission at all) both feed the same estimate pipeline. Matches the roadmap's explicit
"pre-fill, do not auto-save" requirement exactly as before: a successful estimate fills the
existing editable fields, and the same SAVE button is still the only thing that writes to
Supabase — a photo by itself never inserts anything.

**Verified on the emulator as far as possible without a real Gemini key** (none was available to
test with): Settings screen saves/persists/clears a key correctly (confirmed the Food form's AI
section appears and disappears with it). CHOOSE PHOTO opens the real Android Photo Picker and a
selected image correctly triggers the estimation call. TAKE PHOTO correctly requests the `CAMERA`
runtime permission (dialog shows "Field Terminal" by name, confirming the manifest declaration is
wired) and, once granted, launches the camera via the `FileProvider` Uri without a crash. Ran a
real photo through the full pipeline with a syntactically-valid but fake key end-to-end: the app
returned Gemini's own real error, "API key not valid. Please pass a valid API key." — this
confirms the request actually reached Gemini's API correctly formed (a wrong model id or
malformed request would have failed differently), and that the error surfaces in the UI instead
of crashing or failing silently. A genuine successful-estimation response is unverified pending a
real user-supplied key.

**Barcode scanning, flagged not built**: the user mentioned it could help "at times." Scoped
separately since it needs its own dependency (e.g. ML Kit Barcode Scanning), a live camera-preview
UI (not just a one-shot capture), and a nutrition lookup service (e.g. OpenFoodFacts) barcode
data feeds into — three new pieces this step didn't touch. Worth a dedicated step if wanted.

### ✅ Fix pass — typography, categorical color, Wellness, Fuel/Supplements, dates, Training (done 2026-09-15, Claude Code)
A user-requested revision pass over Steps 11–13, not a numbered roadmap step of its own.

**Typography**: every Saira/JetBrains Mono size app-wide bumped one step up its own established
scale (`design/README.md`'s Typography section updated to match — the single source of truth,
per this project's own convention). Saira Condensed (the big headline numbers — 54.2 KM, 611 KCAL,
etc.) deliberately untouched, per the user's own framing: "bigger font, only text — numbers are
big enough."

**Category colors**: `FieldColors` gained five new tokens (DeepBlue, Orange, Sand, Azure, Red) per
an explicit mapping the user gave — sleep/deep-blue, activity/orange, food-drink-supplements/
green, stool/sand, wellness/azure, encounter-and-arousal/red. Fixes the original complaint
("drink and run have the same colour") and extends past just the Log tab's chips to every other
place those same categories render: the Nutrition/Hydration screen's water segments (was Cyan,
now Green) and the Training screen's weekly-distance bar (was Cyan, now Orange). Cyan itself now
means labs (blood work) only. `design/README.md`'s color table updated to match.

**Wellness questionnaire, previously entirely missing from Log**: `wellbeing_daily`
(energy/mood/stress/soreness, all real columns that already existed) had no read presence in the
Log feed and no write path anywhere in the app — a real gap, not a deliberate omission. Added a
`Wellness` `LogEntryKind`/`LogSource`, a feed entry ("Energy 8 · Mood 9 · Stress 3 · Soreness 2"),
and a `WellnessForm` in the add-entry picker; upserts on `(user_id, date)` like the other daily-
questionnaire tables.

**FUEL — Food/Drink/Supplements combined into one add-entry type** with a three-way sub-picker
shown after selecting it (`AddEntryType.Fuel` + `FuelSubType`), reusing the existing FoodForm/
DrinkForm unchanged underneath. Supplements gained a real logging path for the first time (a new
`supplement_log` table, one row per supplement taken — see ROADMAP.md's earlier note that this
had no backing table): its form groups this account's real active supplements by `time_of_day`
into MORNING/AFTERNOON/NIGHT bundles (checking a bundle logs everything in it as one action, since
those are taken together) plus an AS-NEEDED section where each supplement gets its own checkbox
(these vary day to day, so they're individually selectable, exactly per the user's own framing).
Because each checked supplement becomes its own `supplement_log` row rather than one combined
"session" row, "add or remove single items" needed no new mechanism at all — it's just the
existing generic per-entry delete, reused. Supplement-taken entries are delete-only (no edit
form), matching Sleep's existing pattern, since editing which supplement a row represents doesn't
make sense.

**Date/time on every form**: added `ui/components/DatePickers.kt` (`DateField`/`DateTimeField`,
built on the platform's own `DatePickerDialog`/`TimePickerDialog` rather than a custom calendar
widget). Every add-entry form defaults to now but lets the user pick a different date (and time,
for timestamp-backed tables) — required threading an explicit date/timestamp parameter through
every `add*` function in `AddEntryRepository.kt` (previously they defaulted to "now"/"today"
internally; the UI layer now owns that decision entirely, matching how `update*` already worked).

**Training removed from the add-entry flow entirely**, per direct instruction: there is now no
way to manually log a run in this app. `TrainingForm`, `addTraining`/`updateTraining`, and
`NewRunRow` were deleted outright rather than left as dead code. Runs still display normally in
the Log feed and the Training sub-tab (both read-only) — `LogSource.Run` was added to the same
delete-only set as Sleep (no edit, since there's no form to edit it with), so a bad wearable-
synced run can still be removed, just not added or corrected in place.

**Verified end-to-end on the emulator with direct Supabase reads**: font size and all five new
category colors visually confirmed (Sleep deep-blue outline, Run filled orange, Food/Supplements
filled/outlined green, Wellness azure outline, all simultaneously visible and distinct in one
screenshot). Added a real Wellness entry's worth of data was already visible from existing rows;
opened the Fuel picker and confirmed all three sub-tabs render with a working date field. Checked
the real NIGHT supplement bundle (3 real supplements) and saved — confirmed via direct SQL that
all 3 landed as separate `supplement_log` rows with a correctly timezone-converted timestamp
(local 22:22 → stored 14:22 UTC, an 8-hour offset matching this account's real timezone). Deleted
one of the three individually via the Log tab and confirmed via SQL only that one row was gone —
the "add or remove single items" requirement holds. Confirmed a RUN entry's action sheet now
offers only DELETE, no EDIT. Test data (the supplement-log rows created during verification) was
deleted afterward so no synthetic entries were left in the real account's data.

### ✅ Step 14 — Map tab: real Calendar + Drive GPX route (done 2026-09-15, Claude Code)
**Scope decision, made by the user, not silently picked**: the web dashboard's "Map" isn't a map
at all — it's a stylized GPX route line for the next training-session calendar event, gated
behind Calendar+Drive OAuth scopes the native app's sign-in never requested. Full replication
needed a genuinely new native OAuth flow, so this was flagged explicitly rather than assumed;
the user chose full parity over a Calendar-only or deferred option.

**Real architectural finding, not a guess**: the web dashboard needs a whole extra layer — a
`user_google_tokens` table + `refresh-google-token` Edge Function holding the Google Client
Secret — specifically because a *browser* has no OS-level Google account layer to silently
remint an expired access token; it has to keep a refresh token server-side and mint fresh access
tokens through Supabase on every calendar/drive call. **Android doesn't have that problem.**
Play Services' Authorization API (`com.google.android.gms:play-services-auth`, confirmed current
dependency/API directly against Android's own developer docs, not assumed) mints a fresh
short-lived access token on-device, silently, whenever the requested scopes (`calendar.readonly`,
`drive.readonly`) are already granted for the signed-in Google account — no UI, no stored
refresh token, no new Edge Function, no new Supabase table or RLS policy needed at all. This is a
real simplification versus the web, not a shortcut: the whole reason the web's extra machinery
exists simply doesn't apply on-device. See `auth/GoogleAuthorizationManager.kt` for the full
reasoning. The one manual step this still needed was already done for the web (the OAuth consent
screen's scopes are project-level, not per-client) — no additional Google Cloud Console changes
were required.

Distinct from `GoogleAuthManager`'s existing Credential Manager sign-in, which only ever verifies
identity for Supabase auth and never requests OAuth scopes at all — this is a second, separate
authorization step (`GoogleAuthorizationManager`), called fresh every time the Map tab loads, same
no-client-caching principle as the web's `getFreshGoogleToken()`.

**Ported 1:1 from index.html**: `classifySessionType()` → `classifySessionKind()` (same
🏋️/⛰️/🏃-plus-trail-keyword detection), `parseGpxLink()`, `parseGpxPoints()` (same trkpt-then-rtept
XML fallback, no library), `extractDriveFileId()`, and the route-projection math in
`drawGpxRoute()` (same cos(latitude) longitude scaling so the route isn't stretched at this
latitude) → `RouteCanvas` in `ui/screens/MapScreen.kt`, drawn with Compose `Canvas` instead of raw
SVG. Same colorId `'8'` == training-session filter as `fetchNextSession()`, confirmed still
correct against this account's real calendar (see verification below) — meal-prep events use
colorId `'10'` and are correctly excluded.

**One deliberate deviation**: `daysUntilSession()` drops the web's hardcoded `Asia/Taipei` zone
in favor of `ZoneId.systemDefault()` — that hardcoding exists on web specifically to correct for
an arbitrary browser's timezone; on the user's own phone, the system zone is already correct,
and every other date computation in this app already uses `ZoneId.systemDefault()`.

**Real, computed, not fabricated**: route distance (haversine sum over the actual downloaded GPX
points) and elevation gain (sum of positive `<ele>` deltas, only shown when the file actually
carries elevation data). The mockup (`design/Field Terminal Mockups.dc.html`, "Map · next
session") also shows an invented pace target and a weather line ("11°C · LIGHT RAIN · WSW 18
KM/H") — neither has a real data source in this app (weather is a separate, unbuilt P2 scope on
Android), so both are omitted rather than invented, same principle as every other Status tab.

**New design token**: `FieldColors.Magenta` (`#ff4cd6`), same literal as the web's end-of-route
marker — used only there, documented in `design/README.md`'s Color Tokens table as GPX-only, not
a general log category.

**Verified live, cross-checked against the real Google Calendar API directly** (not just the
app's own render): the Map tab showed "⛰️ Hill Session", Wed 16 Sep 07:00, a real rendered GPX
route, and "12.8 KM · 1345 M GAIN" computed independently from the downloaded GPX file's own
points. Queried the same account's Google Calendar directly (via the Calendar connector) for that
day and got an exact match — same event, same `colorId: "8"`, same Drive link, and the event's
own description text ("Route: TGT Section 5 (12.8km, +1345m)") matches the app's independently
*computed* numbers rather than a copied string, confirming the distance/elevation math is correct,
not just the passthrough text. The two same-day meal-prep events (`colorId: "10"`) were correctly
excluded. No crashes or exceptions in logcat during the full authorize → fetch → render flow.

### ✅ Step 14 follow-up — real map background, directions, weather popup (done 2026-09-15, Claude Code)
User-requested revision, not a numbered step: a real map background (dark-styled, "manageable
overlays"), a way to preview the drive there with an "arrive by" setting, a weather-at-session-time
popup with a weather-symbol icon, full-bleed layout, and the activity details moved into a popup.

**Scope decisions, verified against Google's own docs before building, not assumed**:
- **Map SDK**: the Google Maps SDK for Android needs a *billing-enabled* Google Cloud project even
  for light personal use (confirmed directly against Google's Maps SDK for Android usage-and-billing
  docs) — a real, material cost/commitment difference from the free OAuth scopes Step 14 used. Asked
  the user whether that was acceptable; they asked to check for a free alternative first. Found one:
  **osmdroid** (`org.osmdroid:osmdroid-android:6.1.20`, MIT-licensed, no API key of its own) rendering
  **CARTO's free-tier Dark Matter basemap** (`basemaps.cartocdn.com/rastertiles/dark_all`, a free
  API key from carto.com/basemaps/apikey — no billing, no card, 5M tiles/month fair-use limit,
  confirmed directly against CARTO's own docs). Genuinely free end to end; no Google Maps SDK,
  no Cloud Console billing step needed.
- **"Arrive by" directions**: Google's consumer Maps deep link (`maps.google.com/maps/dir/?api=1&…`)
  does not accept a pre-filled arrival time — that parameter exists only in Google's separate,
  also-billed Directions API (confirmed via Google's own Directions API and Maps URLs docs). Asked
  the user to choose between building a real in-app ETA (another billed API) or deep-linking to the
  real Google Maps app and letting the person set "Arrive by" themselves once it opens; they chose
  the deep-link. `directionsIntent()` in `ui/screens/MapScreen.kt` builds a plain
  `https://www.google.com/maps/dir/?api=1&origin=…&destination=…&travelmode=driving` URL — no API
  key, no billing, opens the real Maps app.
- **Home location**: manual latitude/longitude entry in Settings (user's choice over device-GPS
  "use current location", to avoid a location permission for a one-time value) — `data/
  MapSettingsStore.kt`, plain (unencrypted) `SharedPreferences`, local-only, never synced to
  Supabase. Stored as strings, not `Float`, so a coordinate like `121.5654321` doesn't lose its
  last digit to `Float`'s ~7-significant-figure precision.

**New Settings card ("MAP")**: CARTO API key (plain SharedPreferences — unlike `GeminiApiKeyStore`'s
Keystore encryption, this key has no billing exposure if leaked, only a shared rate limit, so the
lighter storage is a deliberate, not accidental, difference) plus the home lat/lon fields. A shared
`ClearChip` composable replaced the one-off "CLEAR" button Step 13 wrote inline, now reused by all
three saved-value fields on the screen (Gemini key, CARTO key, home location).

**MapScreen.kt rewritten for full-bleed layout**: the osmdroid `MapView` (wrapped via `AndroidView`,
app-specific cache dirs so no `WRITE_EXTERNAL_STORAGE` permission is needed on any supported API
level) fills the entire screen below the header. The real GPX route and its start/end markers are
now drawn as real lat/lon overlays (`Polyline`, `Marker`) on the actual map and auto-fit to the
route's bounds, replacing the previous version's stylized Canvas-projected line. Two floating,
squared (no radius, per design/README.md) chips sit over the map: a `WEATHER` chip (idle → loading
→ `☀️ 25°`) and a bottom `DETAILS`/`DIRECTIONS` action row — `DIRECTIONS` only renders when a home
location is saved. Both `DETAILS` and `WEATHER` open a `ModalBottomSheet`, same pattern
`AddEntrySheet.kt` already established, rather than a new popup mechanism.

**New weather integration** (first use of Open-Meteo on Android — the web dashboard already uses
it for AQI/UV/sunrise-sunset): `data/WeatherRepository.kt` fetches Open-Meteo's keyless hourly
forecast (16-day window) for the route's start coordinate and picks the hour closest to the
session's own start time (not "now" — a session can be up to a week out). `domain/Weather.kt` ports
the web's WMO weather-code → label table 1:1 (`weatherCodeLabel()`) and adds a new
`weatherCodeSymbol()` (plain emoji, since this app has no illustrated weather-icon set the way the
web dashboard does).

**Verified live on the emulator, real network calls throughout, no logcat exceptions**:
- Settings round-trip: saved a placeholder CARTO key and a test coordinate near Taipei 101
  (25.0330, 121.5645 — not the user's real home), confirmed both persisted and displayed correctly
  ("Home set: 25.0330, 121.5645"), then cleared both afterward so no placeholder values were left
  on the device.
- Map tab rendered a real, full-bleed dark basemap of the actual Taipei area matching the real GPX
  route's location — CARTO serves tiles even against an invalid key, stamping an "API KEY REQUIRED"
  watermark rather than refusing outright, which itself confirmed the tile-fetch/render pipeline is
  wired correctly end to end (same "the error itself is proof of a real round-trip" logic as Step
  13's Gemini key test). The real polyline + green/magenta start/end markers rendered correctly
  fitted to the route bounds.
- **DIRECTIONS**, tapped for real, launched the actual Google Maps app with the test home
  coordinate correctly reverse-geocoded to "Taipei 101/World Trade Center Station" as origin and
  the GPX route's real start point as destination, and Maps computed a real driving route (16 min,
  9.3 km) — full proof the deep-link mechanism works with zero API key or billing involved.
- **DETAILS** popup showed the same session title/description/GPX link/countdown as before, plus
  the route's real computed distance and elevation gain (12.8 KM · 1345 M GAIN) inside the popup.
- **WEATHER** popup fetched a real live forecast for tomorrow 07:00 at the route's start
  coordinate: 25°C, Mainly clear, wind 7 km/h, 0.0 mm precipitation — plausible September Taipei
  weather, genuinely fetched, not fabricated.
- Test/placeholder values (CARTO key, home coordinate) cleared from the device after verification.

### ✅ Phase G1 — Health Connect foundation: permissions + plumbing (done 2026-09-16, Claude Code)
Start of Phase G (Health Connect), scoped via a full 7-phase plan (G1–G7) covering read-only
import of every requested metric, a generic multi-type exercise schema replacing `runs`, an
on-demand combined session-detail view, and bidirectional nutrition/hydration sync with a
one-time backfill. **Only G1 (foundation) is built now** — each later phase is its own future
step, verified the same way as every step before it, not built blind in one pass. The full phase
breakdown lives in this session's plan file; summarized here so the design intent isn't lost:

- **G2** — read-only daily-aggregate sync (steps, active/total calories, VO2max, BMR, body
  fat/height/weight, SpO2/respiratory rate/resting HR/HRV, sleep) into the *existing*
  `wearable_daily`/`body_metrics`/`sleep_daily` tables (extended with new columns), auto-synced on
  every app open via a new `sync_log`-backed watermark.
- **G3** — a new generic `exercise_sessions` table (any activity type, not just runs, with a
  flexible `details` jsonb for sets/reps/surface/etc.) replacing `runs` (19 rows migrated,
  `runs` renamed not dropped); also what finally gives the Training tab's "STRENGTH" card
  (an honest empty state since Step 7 — no data source existed) real data.
- **G4** — on-demand per-session time-series read (HR/speed/elevation/power) straight from Health
  Connect when a session's detail screen opens, never persisted to Supabase — same fetch-fresh
  principle Step 14's GPX route already established for Drive.
- **G5** — the actual "show it all together" combined chart UI (new reusable `LineChart`
  component), deliberately sequenced after G3/G4 since it's presentation work, not data plumbing.
- **G6** — bidirectional nutrition/hydration sync (this app's Fuel-form writes also land in Health
  Connect; external apps' entries get pulled into Supabase), de-duplicated via each record's own
  `metadata.dataOrigin.packageName` rather than an invented matching scheme.
- **G7** — one-time backfill of existing `meals` history into Health Connect, sequenced last
  (reuses G6's writer/de-dup), idempotent via a `health_connect_backfilled_at` flag.

**Real architectural decision, not silently picked**: unlike Calendar/Drive (Step 14), which
needed a whole server-side refresh-token apparatus specifically because a browser has no
OS-level account layer, Health Connect *is itself* the on-device consolidation layer everything
else was missing — the Android app becomes the thing that syncs it into Supabase automatically on
every open, finally solving the exact "manual, chat-triggered" pain point ROADMAP's own P7 section
flagged as the reason to move off Wellness Project. Per-session time-series data is deliberately
**not** persisted to Supabase (matches the existing GPX-route precedent) — those rich combined
charts are an Android-only feature, a real trade-off surfaced to and confirmed by the user rather
than assumed.

**What G1 actually built**: `androidx.health.connect:connect-client:1.1.0` (current stable — the
newer 1.2.0 exists but is alpha-only, confirmed via the Jetpack releases page). Manifest gained a
`<queries>` entry for the Health Connect package, one `READ_*`/`WRITE_*` permission per record
type actually used (23 total), and the required `PermissionsRationaleActivity` +
`ViewPermissionUsageActivity` activity-alias boilerplate. New `healthconnect/HealthConnectManager.kt`
(availability check, the permission set, a grant check) and `healthconnect/
PermissionsRationaleActivity.kt` (the static rationale screen the platform requires even for a
personal sideloaded app). Settings gained a "HEALTH CONNECT" card: status line + a connect button,
checked once per screen load rather than re-requested every time (unlike the Calendar/Drive
authorization flow) since Health Connect grants persist at the OS level.

**Real finding, not assumed**: `ActivityIntensityRecord` (needed for "activity intensity") does
**not** exist in `connect-client:1.1.0` stable — confirmed by extracting the real AAR from the
Gradle cache and listing its actual class files, not by trusting the (newer, alpha-reflecting)
public docs page. It only appears in the 1.2.0-alpha series. Deferred rather than pulling in an
alpha dependency for one field; every other requested metric's record class was confirmed present
in the 1.1.0 stable AAR the same way.

**Verified live on the emulator (Android 17/API 37, Health Connect framework-integrated — no
separate Play Store install needed)**: Settings showed the honest "Not connected yet" state before
granting; tapping CONNECT HEALTH CONNECT opened the real Health Connect consent screen (not a
mock), showing "Field Terminal" by name and "Activity — 0 of 9 selected" confirming the requested
permission set reached the OS correctly; granting flipped Settings to "Connected — all requested
permissions granted" in green. Force-stopping and relaunching the app confirmed the grant persists
and is detected on a fresh `LaunchedEffect` without re-prompting. Cross-checked directly against
Health Connect's own system settings (`android.health.connect.action.HEALTH_HOME_SETTINGS`) —
Field Terminal appears under "Your health apps" with its real launcher icon, proving the grant is
genuinely registered at the OS level, not just believed true by the app's own local state. No
exceptions in logcat through the full flow.

### ✅ Phase G2 — read-only daily-aggregate sync (done 2026-09-16, Claude Code)
Steps, active/total calories, VO2max, BMR, body fat/height/weight, resting HR/HRV/SpO2, and sleep
(with stage minutes), auto-synced into the existing `wearable_daily`/`body_metrics`/`sleep_daily`
tables every time the app opens.

**Real API facts, extracted from the actual library, not the (sometimes alpha-reflecting) public
docs**: this project has no JDK/`javap` available, so verified every record class's real field
names and unit-wrapper accessors (`Energy.inKilocalories`, `Mass.inKilograms`, `Length.inMeters`,
`Power.inKilocaloriesPerDay`, `Percentage.value`, plus each record's own getter like
`Vo2MaxRecord.vo2MillilitersPerMinuteKilogram`) by unzipping the real `connect-client-1.1.0.aar`
from the Gradle cache and scanning its class files' constant-pool strings for method names — a
`strings`-equivalent done in Python since neither `strings` nor `javap` exist in this environment.
This is the same "check the real thing, not the docs" discipline as G1's AAR-class-listing check,
applied one level deeper (field shapes, not just class presence) — and it paid off: the full sync
repository compiled clean on the very first real attempt (one unrelated nullability fix aside).

**Real architecture decision, not silently picked**: `HealthConnectSyncModels.kt` uses one minimal,
single-metric row class per upsert call (`StepsUpsertRow`, `Vo2MaxUpsertRow`, etc.) rather than one
shared row with every column as a nullable field. kotlinx.serialization encodes nulls explicitly by
default, and Postgrest's upsert applies every column present in the request body on conflict — a
shared row would silently null out a Wellness-Project-sourced value (e.g. `steps`) the moment any
*other* metric synced for that date and left it null. Each upsert here touches exactly the one
column it names. Verified directly: after syncing, all 34 existing `wearable_daily` rows still have
their original `steps`/`rhr` values intact.

**Real refinement to the original phase plan, made during implementation**: dropped the planned
`wearable_daily.respiratory_rate_avg` column entirely. `sleep_daily.respiratory_rate` already
existed pre-Health-Connect for exactly this purpose (nightly respiratory rate); adding a second,
all-day column would have meant two places meaning almost the same thing. `RespiratoryRateRecord`
readings are now matched to whichever `SleepSessionRecord` covers their timestamp and averaged into
that night's existing `sleep_daily.respiratory_rate` column instead.

**Sync mechanism**: `HealthConnectSyncCoordinator.syncAll()`, called from a `LaunchedEffect` in
`MainActivity`'s `AuthGate` on every `SessionStatus.Authenticated`, fire-and-forget (never blocks
the nav host). Watermark reuses the existing `sync_log` table (`sync_source='health_connect'`,
`changes_summary` holding per-metric row counts) with a 2-day overlap and a 30-day first-run
lookback (matching Health Connect's own no-extra-permission read window) — every write is an
`onConflict="user_id,date"` upsert, so reprocessing the overlap is naturally idempotent, not a bug.
Settings' HEALTH CONNECT card now shows "Last synced HH:MM — steps: N, ..." reflecting the real
result via a small `HealthConnectSyncStatus` Compose-state object (in-memory, per-session, not
persisted — a status line, not a setting).

**Verified live on the emulator, real end-to-end execution, honestly incomplete on real numbers**:
the sync ran with zero exceptions across two consecutive app opens, wrote two real, distinct
`sync_log` rows to Supabase (confirmed via direct SQL) with the watermark correctly advancing
between them, and the Settings card showed the real result ("Last synced 06:37 — steps: 0,
active_calories: 0, ... sleep: 0"). **All-zero is the honest, correct result here, not a bug** —
this emulator has no paired wearable and Health Connect's own consumer app has no manual data-entry
UI for these record types (confirmed by navigating it directly — it's purely a broker between
source apps, not a data-entry tool), so there was no real data anywhere on this device for the sync
to find. What's genuinely verified: the full permission-check → watermark-read → read-aggregate-
upsert (all 8 metric groups) → watermark-write pipeline executes correctly against live Health
Connect and Supabase APIs with zero crashes, and existing data is provably untouched. What's
**not** yet verified: correct handling of real non-zero values (unit conversions, per-day bucketing,
sleep stage summation) — that needs the user's real phone, which has real Zepp/wearable data
flowing into Health Connect. Flagged rather than silently assumed correct.

### ✅ Phase G3 — generic `exercise_sessions`, `runs` migration, editable Log entry (done 2026-09-16, Claude Code)
The run-only `runs` table is retired (renamed to `runs_retired`, not dropped) in favor of a new
generic `exercise_sessions` table covering any Health Connect activity type, with a `details`
jsonb column reserved (but not yet used by any UI) for future type-specific extras. The 19
existing rows migrated in with a nominal 07:00 start time (same convention `domain/Log.kt` already
used for date-only tables) and `route_type` moved into `details`.

**Real API facts, same AAR-extraction discipline as G1/G2**: confirmed `ExerciseSessionRecord`'s
50+ `EXERCISE_TYPE_*` constants, and the exact field shapes for `DistanceRecord`/
`ElevationGainedRecord` (interval, single value) vs. `SpeedRecord`/`PowerRecord` (series, sample
lists like `HeartRateRecord`) by extracting the real AAR again. One real correction mid-build: the
regex-based string scan initially missed `Velocity.inKilometersPerHour` (a length/pattern quirk in
the extraction script, not the library) and the compiler caught the resulting wrong guess
(`.kilometersPerHour`) immediately — re-scanning without the overly strict filter found the real
name. Kept as a reminder that this project's own bytecode-scanning technique still needs the
compiler as a second check, not just the scan.

**Real architecture decision**: `exercise_sessions` stays Health-Connect-sourced only, per the
2026-09-15 fix pass's own removal of manual exercise logging — no "add a workout from scratch"
form exists or was added. What Log tab entries for exercise sessions gained instead is a real
**EDIT** action (previously runs were delete-only) opening a new `ExerciseDetailsForm` that edits
only `rpe`/`notes` — every other field (time, distance, heart rate, ...) is Health-Connect-sourced
and stays read-only in this app. The user's own "keep it basic, see how to add later" framing for
structured sets/reps/surface extras is taken literally: `notes` is free text for now, not a
structured editor — the `details` jsonb column exists in the schema for when that's wanted, but
nothing writes to it yet.

**Also generalizes**: `domain/Log.kt`'s `LogEntryKind.Run`/`LogSource.Run` → `Exercise` (Orange
chip, label now shows the real type e.g. "Run · 13.0 km · 1h 22m" instead of a fixed "RUN"
label — headline text carries the specificity now that the chip itself covers any activity type).
`TrainingRepository`/`domain/Training.kt` filter to endurance types (run/walk/hike/ride) for the
existing ENDURANCE/pace/distance-totals math, unchanged in spirit; pace is now derived from
duration/distance rather than read from a stored `pace_min_per_km` column, since Health Connect
doesn't supply pace directly. **STRENGTH finally gets a real (if basic) data source** — Step 7's
own finding was that none existed anywhere in this project; the card shows session count + total
minutes this week once any strength-type session syncs, replacing the old permanent "No data
source yet" placeholder with an honest "no sessions synced yet, but the source now exists" message.

**Verified live on the emulator against real, pre-existing data (not fabricated)**: the Log feed
correctly shows a migrated run as "EXERCISE · Run · 13.0 km · 1h 22m, avg 147 bpm" at its original
07:00 nominal time; tapping it now offers EDIT (previously delete-only); the edit form correctly
pre-filled the real migrated `rpe` value (3) and the explanatory copy correctly named the real
session type ("this run session"); typing a note and tapping SAVE persisted it to Supabase
(confirmed via direct SQL), then reverted afterward since it was test content, not something the
user actually wrote. The Training tab's ENDURANCE card showed real recomputed numbers (46.3 km
this week, 8:28/km avg pace, 18.36 km longest run, 35.2 km/wk 4-week avg) matching the migrated
data, and the Distance Totals period toggle worked unchanged. The Health Connect sync's
`sync_log` entry correctly included a new `"exercise_sessions": 0` count alongside G2's metrics
(honestly zero — no real Health Connect exercise data exists on this emulator, same caveat as G2).
No exceptions in logcat through the full flow.

---

## Summary — rough remaining build time

Foundation, the full dashboard merge, live weather, live calendar/session integration, and
the layout rework are all done. P1 is fully complete. P5 is fully complete (illness tracking +
stool tracker, plus the new Kidneys/hydration marker as their natural companion). P4's arousal
item is done; partner/encounter tracking and masturbation logging remain. Remaining:

| Tier | Est. hours |
|---|---|
| P2 (push notification foundation done; morning wake-time alert unscoped; environmental panel done) | 1–2h |
| P3 | 2–3h |
| P4 (arousal done — partner/encounter tracking + masturbation logging remain) | 2–3h |
| P5 | **done** |
| P6 (Wardrobe) | 6–10h |
| P7 (Decouple from Wellness Project — Zepp Mini Program only; Health Connect app moved to P8) | 6–14h |
| P8 (Mobile app "Field Terminal" — Steps 1–2 of 18 done; Health Connect is this app's own Phase G) | 38–65h |
| **Total** | **~53–92h** |

The core "is this real" question was answered early — the pipeline works, proven with live
data, the full dashboard UI is live against it, and weather + calendar are now genuinely live
too. What's left is real product-building (P2–P4, P6, most schemas already in place) plus one
large, deliberately-last platform migration (P7).
