# Routine intelligence: implementation status

Plan, research and the owner's decisions: [01-research-and-plan.md](01-research-and-plan.md). Phases R1 (phone use) and
R2 (location capture) were started together on 27 Oct 2026 at the owner's request.

| Phase | Status |
|---|---|
| R0 Decisions | Done (plan section 11). TDX registration still to do before R5. |
| R1 Phone use | **Code written; not built, not installed, migration not applied.** |
| R2 Location capture | **Code written; not built, not installed, migration not applied.** |
| R3–R6 | Not started. |

## Verification so far

- The pure logic (`domain/routine/PhoneUsage.kt`, `domain/routine/StayPoints.kt`) has 15 unit tests
  (`PhoneUsageTest`, `StayPointsTest`), run and passing on the JVM.
- **Nothing Android-specific has been compiled.** The session that wrote it could not reach `dl.google.com` (the
  Android SDK and Google's Maven are served from there), so `./gradlew :app:assembleDebug` and the app's own unit test
  task were not run. First step on a machine with Android Studio: build, run `./gradlew :app:testDebugUnitTest`, and
  fix whatever the compiler finds. The `play-services-location` version (21.3.0) was also not checked against the
  repository.
- `supabase/migrations/20261104_routine_capture.sql` is written, not applied.

## What was built

| Piece | Where | Notes |
|---|---|---|
| Tables | `migrations/20261104_routine_capture.sql` | `phone_usage_daily`, `phone_app_usage_daily`, `phone_app_categories`, `location_fixes`, `activity_transitions`, `collector_heartbeats`, `place_visits` (with `place_id` left for R3). RLS owner-only for select, insert, update, delete. |
| Phone day summary (pure) | `domain/routine/PhoneUsage.kt` | Local day 04:00 to 04:00. Screen spans from screen on/off, unlocks from keyguard hidden, per-app foreground spans and launches (moving between one app's screens is not a launch), launchers excluded. Bed window = the hour before sleep onset from `sleep_daily` (the row for date + 1); night = onset to wake; first unlock = first after the day's wake; longest phone-free stretch 08:00–22:00. |
| Phone collector | `data/routine/PhoneUsageCollector.kt`, `PhoneUsageRepository.kt` | Usage access check and Settings link; `queryEvents` mapping; app label and declared category (`QUERY_ALL_PACKAGES`); category overrides from `phone_app_categories`. Recomputes the last three completed days, waits for the night's sleep record until 14:00 the day after, skips days with no events (unknown, not zero). |
| Stays (pure) | `domain/routine/StayPoints.kt` | Stay-point rule (150 m, 10 min, fixes over 200 m accuracy dropped); confidence = share of the stay the collector was provably alive (evidence no more than 90 min apart); `isSettled` ends a trip after 10 quiet minutes within 100 m. |
| Location collector | `data/routine/LocationCollector.kt`, `RoutineReceivers.kt`, `TripRecordingService.kt` | Activity transitions (still / walking / running / bicycle / vehicle) and a 150 m stay geofence drive a foreground service of type location: fixes every 30 s at high accuracy on foot or bicycle, every 60 s at balanced power otherwise; ends 5 min after STILL, on `isSettled`, or after 4 h, then arms the geofence where it ended. Boot and app-update receiver re-registers. |
| Outbox and sync | `data/routine/RoutineOutbox.kt`, `LocationRepository.kt`, `RoutineCaptureWorker.kt` | JSON-lines files in app storage (not Room: no new build plugin); drain renames before upload so nothing appended mid-upload is lost. Hourly worker: heartbeat, one balanced-power fix, re-register, upload, recompute visits over 72 h (rows starting in the last 48 h are replaced), phone use every 6 h. |
| Setup card | `ui/screens/settings/RoutineCaptureCard.kt` in Setup › Profile & goals › Data capture | Per stream: grant status and buttons (usage access; precise, all-the-time, physical activity, battery), TURN ON / OFF, PAUSE TODAY / RESUME, DELETE ALL with confirm; collector heartbeat, last upload, outbox size, trip state; latest phone day with top apps; last 24 h of visits with confidence. Both streams start off. |

## Deviations from the plan

- The phone buffer is a file outbox, not an encrypted Room database (plan section 9 said Room). Since fixes go to
  Supabase anyway (decision 2), the buffer only holds what has not synced yet; Room's build plugin was not worth the
  risk of an unverified build.
- `place_visits` carries its own centroid (`lat`, `lon`), `n_fixes` and `open`; places (R3) will link visits by
  `place_id`. `phone_usage_daily` also stores `night_screen_min`.
- The trip service's ongoing notification is required by Android for location in the background at this rate; it is
  on a silent, minimum-importance channel. It is not a nudge (decision 4).

## Gates still to run (plan section 10)

- R1: daily rows on ≥ 95 % of 14 days; screen time within 5 % of Digital Wellbeing on 10 days, top-5 apps within 10 %;
  bed-window use against the evening check-in's `late_screen` on ≥ 80 % of answered evenings.
- R2: 7 days with battery under 3 %/day; ≥ 98 % of fixes in Supabase within 24 h; ≥ 90 % of 20-minute visits found
  against a hand-kept diary; no visit invented during collector gaps (check low-confidence visits).

## Deploy order

1. Apply `20261104_routine_capture.sql`; run the Supabase security advisor.
2. Build and install the app; Setup › Profile & goals › Data capture: grant usage access, turn PHONE USE on.
3. Grant precise location, then "Allow all the time", physical activity and the battery exemption; turn LOCATIONS on.
4. Keep the diary for the R2 gate for a week.
