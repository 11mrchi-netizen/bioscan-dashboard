# 02 — ZeppBridge integration analysis

**Status:** research complete · **Branch:** `claude/zeppbridge-amazfit-integration-shkpwo`

---

## What ZeppBridge is

[ZeppBridge](https://github.com/lingcang728/ZeppBridge) is a local-first desktop application
(Tauri 2 + Vue 3 + Rust) that syncs data from Amazfit/Zepp wearables to a compressed local
SQLite database, then exposes that data through four thin adapters over the same core crate:

| Adapter | How it works |
|---------|-------------|
| Desktop app | GUI with charts, export, AI packaging |
| `zeppbridge-cli` | `status`, `sync`, `export` — stable exit codes, cron-friendly |
| `zeppbridge-mcp` | stdio MCP server — lets a local model query data without network exposure |
| Local REST (opt-in) | `127.0.0.1`-only, token-gated, off by default |

Authentication uses the **official Zepp login page** in an OS credential store (Keychain /
Credential Manager / Secret Service). Crucially, this is the official auth flow — not a
captured `apptoken`. ZeppBridge only ever issues read requests.

**Supported devices:** 52 Amazfit products (GTR, GTS, T-Rex, Balance, Active, Bip, Cheetah,
Falcon, Helio, Band families). Unrecognised devices still sync; model name is generic.

**Sync depth:** initial 30 days → background incremental → 180 days; long-archive mode
covers 1–3 years month by month.

**Data captured:**

| Category | Detail |
|----------|--------|
| Daily metrics | Steps, RHR, HRV, SpO₂, stress, respiratory rate, PAI, VO₂max |
| Sleep | Full stage breakdown (light / deep / REM / awake) with timeline |
| Workouts | Distance, pace, HR, splits, GPS, power, running form, per-second series |
| Body status | Recovery, training load |

Export formats: JSON, CSV, GPX, FIT (per-second series + laps).

---

## How it relates to the current Zepp integration plan

The project already has:

1. **`zepp-extract` Edge Function** — hits the unofficial mobile API (`api-mifit-us3.zepp.com`)
   using a captured `apptoken`, stores raw responses in `zepp_raw_extracts`.
2. **`docs/zepp-integration/01-auth-and-token-lifecycle.md`** — research doc choosing the
   unofficial mobile API over the official OAuth API because the official API lacks HRV,
   training load, VO₂max, SpO₂, stress, and sleep stages.
3. **P7 roadmap item** — plans a Zepp OS Mini Program (on-watch Device App + Side Service)
   POSTing directly to Supabase as the primary decoupling path.

ZeppBridge introduces a **third path** that sits between these two extremes.

---

## Three integration paths — comparison

| | Current: unofficial mobile API | P7 plan: Zepp Mini Program | ZeppBridge bridge |
|---|---|---|---|
| **Auth** | Captured `apptoken`, ~30-day manual re-capture | Official Zepp OS SDK + Side Service | Official Zepp login via OS credential store |
| **Data scope** | HRV, HR, sleep stages, SpO₂, stress, VO₂max, training load, sport history | Same (whatever the on-watch sensors expose) | Same + per-second FIT workout series |
| **Automation** | Server-side, fully automated once token set | Automated (watch-triggered) | Desktop-side; CLI is cron-schedulable |
| **Token maintenance** | Manual re-capture every ~30 days | None once installed | Managed by ZeppBridge / OS keychain |
| **Fragility** | Undocumented API, can break without notice | Official, but Mini Program tooling untested | Same undocumented cloud API under the hood |
| **Where compute lives** | Supabase Edge Function (server) | On-watch + companion app | User's desktop machine |
| **Field Terminal role** | None (server-to-server) | Passes through the companion app implicitly | Separate (ZeppBridge is not the Android app) |
| **Setup complexity** | Low (one env var after proxy capture) | High (Zepp OS Mini Program dev toolchain) | Medium (install desktop app, sign in, configure export) |
| **Cost** | Free | Free | Free (open source) |

---

## Recommended integration: ZeppBridge as a local sync agent

The most pragmatic use of ZeppBridge for this project is as a **scheduled local export agent**
running on the same machine as the Zepp companion phone (or any always-on desktop/laptop):

```
Amazfit watch
  → Zepp mobile app (BT/BLE sync)
  → Zepp cloud
  → ZeppBridge desktop app (background sync)
  → zeppbridge-cli export --format json
  → ingest script → Supabase (wearable_daily / sleep_daily)
```

This replaces the `apptoken` capture cycle with ZeppBridge's OS-keychain-backed auth while
keeping the existing Edge Function / Supabase pipeline intact.

### Why this is better than the current mobile API path

1. **No manual re-auth every 30 days.** ZeppBridge uses the official Zepp login and OS
   credential store — token refresh is handled by ZeppBridge itself.
2. **Richer workout data.** ZeppBridge exports `.FIT` files with per-second GPS, HR, power,
   and running form — data that the mobile API endpoints don't surface directly and that
   would need binary decoding if accessed raw.
3. **Official auth surface.** The sync chain goes through the official Zepp cloud; endpoints
   are more stable than the undocumented mobile API (`api-mifit-us3.zepp.com`).
4. **The `zeppbridge-core` crate handles decoding.** Sleep stage blobs, base64-encoded HRV
   arrays, and sport history compression are decoded before export — saving the normalization
   work planned under DAV-113+.

### Why the Mini Program (P7) is still worth keeping on the roadmap

ZeppBridge only syncs through the Zepp cloud path. It cannot replace the on-device / real-time
path that a Zepp OS Mini Program could provide (push during a workout, no cloud dependency,
works offline). P7 remains the right long-term answer for **live session data and independence
from the Zepp cloud**. ZeppBridge is a better interim step than the `apptoken` capture approach,
not a permanent replacement for P7.

---

## Implementation plan

### Phase A — Local export script (replaces apptoken capture)

**Estimated effort: 1 session (2–4 h)**

1. Install ZeppBridge on the machine that runs the Zepp companion app.
2. Sign in via official Zepp login (one-time, persists in OS keychain).
3. Let it perform its initial 30-day sync.
4. Write a small ingest script (`scripts/zepp-bridge-ingest/ingest.py` or `.ts`) that:
   - Calls `zeppbridge-cli export --format json --range <date-range>`
   - Parses the JSON output into the existing `wearable_daily` / `sleep_daily` column schema
   - Upserts into Supabase via the service-role key (same pattern as `scripts/nutrition/import_usda_fdc.py`)
5. Schedule it daily via cron (macOS `launchd`, Linux `systemd`, Windows Task Scheduler).

**No changes to the Android app. No new Edge Functions. No new Supabase tables.**
The `zepp_raw_extracts` table can be retired once the normalized path is proven.

### Phase B — FIT file ingest for runs (optional, high-value)

**Estimated effort: 0.5 session (1–2 h)**

ZeppBridge exports per-workout `.FIT` files. Parse them with the
[`fit-file-parser`](https://www.npmjs.com/package/fit-file-parser) npm package (or the Rust
`fitparser` crate via a small CLI) to populate `exercise_sessions` + GPS track data in the
`runs` table. This unlocks per-second HR, pace splits, and running power that the current
Wellness Project sync doesn't capture.

### Phase C — MCP server for Claude queries (bonus)

**Estimated effort: 0 additional setup (already built into ZeppBridge)**

`zeppbridge-mcp` operates over stdio. Add it as a local MCP server in Claude Code settings:

```json
{
  "mcpServers": {
    "zeppbridge": {
      "command": "zeppbridge-mcp",
      "transport": "stdio"
    }
  }
}
```

This allows asking Claude directly about the local Amazfit data during a session — useful for
debugging the ingest script, spot-checking data quality, or exploratory analysis before
deciding what to surface in the dashboard.

---

## Impact on existing work

| Existing item | Change |
|---|---|
| `zepp-extract` Edge Function | Superseded by Phase A; can be kept as a fallback or retired |
| `zepp_raw_extracts` table | Can be retired once normalized ingest is proven |
| `ZEPP_APP_TOKEN` / `ZEPP_USER_ID` secrets | No longer needed (ZeppBridge manages auth) |
| DAV-112 (extraction PoC) | Completed by Phase A — ZeppBridge _is_ the extraction PoC |
| DAV-113+ (normalization layer) | Scope reduced: ZeppBridge pre-decodes blobs; ingest script just maps fields |
| P7 (Zepp Mini Program) | Still on roadmap; ZeppBridge is a better interim, not a replacement |
| Health Connect (Phase G) | Unchanged — still the right path for on-device / real-time data |

---

## Open questions before starting Phase A

- [ ] Confirm ZeppBridge recognises the specific Amazfit model in use (52 supported — check list)
- [ ] Verify ZeppBridge's JSON export field names map cleanly to `wearable_daily` columns
      (HRV as RMSSD vs SDNN is the key unknown — same as DAV-1930 open item)
- [ ] Confirm the machine that runs ZeppBridge has persistent network access and stays on
- [ ] Decide whether the ingest script runs on the local machine or is packaged as a
      Supabase Edge Function triggered by a webhook from the local machine
- [ ] Check ZeppBridge license for any restrictions on automated / scripted use

---

## Sources

- [ZeppBridge GitHub](https://github.com/lingcang728/ZeppBridge)
- `docs/zepp-integration/01-auth-and-token-lifecycle.md` (this repo)
- `supabase/functions/zepp-extract/index.ts` (this repo)
- `ROADMAP.md` §P7 (this repo)
