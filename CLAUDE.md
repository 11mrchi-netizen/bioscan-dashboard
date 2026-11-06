# Working on this repository

## The app map: the owner's way of directing UI work

The owner keeps an editable map of the Android app ("Field Terminal") as a claude.ai artifact:

**Field Terminal Map** — https://claude.ai/artifact/27iuoPccEPA7ixdeoHZpLf

Every screen, overlay and sheet is a box; every tap between them is an arrow. The owner marks what they want changed
directly on the map, and it should always match the app as built.

### How it stores its data

The page keeps everything in its artifact database (read and write it with the `ArtifactData` tool and the URL above):

- `nodes/<id>`: one screen. Fields: `label`, `kind` (tab, subtab, page, overlay, sheet, legacy, entry), `area`
  (entry, today, detail, plan, log, lab, me, setup), `x`, `y` (canvas position), `summary`, `contains` (what is on the
  screen, top to bottom, one line each), `file` (the main source file), `status`, `note`.
- `edges/<source>__<target>`: one tap. Fields: `source`, `target`, `label` (what you tap), `how` (tap, dock, subtab,
  notification, auto), `status`, `note`.
- `status` is the request: `keep`, `change`, `remove` or `new`. `note` is what the owner wants changed, in their words.

### When asked to "read the map" or "build the map's changes"

1. List both collections and collect every node and edge whose `status` is not `keep` or whose `note` is not empty.
   That is the change list. Ask the owner only where a request is genuinely ambiguous.
2. Build the changes in the app. Run the tests and `assembleDebug` before pushing.
3. Update the map to match what was built:
   - add new screens and arrows with `status: "new"`, in free space near the screens they connect to;
   - delete screens and arrows that no longer exist;
   - rewrite `summary` and `contains` of the screens that changed;
   - set the requests that were done back to `status: "keep"` with an empty `note`.

   Read the documents first and pin every write to the version you read (`if_version`), so edits the owner made in
   the meantime are never overwritten. Keep the owner's box positions: only place new boxes.
4. Report what was built, what was not, and how the map changed.

The map's own page (HTML) does not need republishing to change its content: only the database changes.

## Dates and times

The owner lives in UTC+8, so a date taken from UTC is a day behind every morning until 08:00 local. Use the person's
local calendar, never `toISOString().slice(0, 10)` / `current_date`. Two kinds of timestamp exist; read each as what it is:

- **Logged times** (`meals.logged_at`, `exercise_sessions.start_time`, `supplement_log.taken_at`, `nutrient_intake.logged_at`,
  `occurred_at` on stool / grip / notes / body check-ins / scale readings, `athlete_events.start_time`) are the phone's
  wall clock labelled `+00`: their digits are local. Read them with `WallClock` (Android) or `wallClock` (analysis-core);
  never add an offset to them.
- **Real instants** (`created_at`, `fired_at`, `trips.start_at`, `sleep_daily` bedtime / wake, calendar events, stress
  samples) are converted with the zone, or with `daily_context.utc_offset_min` on the server (`localClock`, `localDateAt`).

## Visual design

The visual authority is `design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md` (v2.0, the v1 "new look"); its token source is
`design/tokens/` (`tokens.css` / `tokens.json`), mirrored one to one in `ui/theme/FuturisticMaterialTokens.kt`. The rules:

- **Background = domain** (`ftDomainWash(FT.Domain.*)` on the page), **surface = neutral glass** (`ftGlass`, never tinted),
  **ink = metric** (`FT.Metric.*`, one fixed hue per metric app-wide). Never colour a metric with a domain hue or tint a card.
- Selection and the primary action are platinum (`FT.Platinum`); status colours (`Ok` / `Warn` / `Bad`) say how something is,
  never which series or section it is. The holographic spectrum is CHARGE's only.
- Numbers are Nunito, interface text Inter, micro-labels IBM Plex Mono, all through `FTType` roles.
- No raw hex in screens: a new metric hue is added to `design/tokens/` and `FT.Metric` together.
- Bars and diverging scales follow `design/tokens/charts-bars-and-scales.md` through `ui/components/FTBars.kt`; lists of
  metrics use each metric's hue (`metricInk`) and show direction only by the number, arrow and words.
- Anything that shows a metric and looks tappable opens its history (`MetricHistoryOverlay`); nothing has a › that leads nowhere.
