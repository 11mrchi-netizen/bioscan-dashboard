# Routine intelligence: implementation status

Plan, research and the owner's decisions: [01-research-and-plan.md](01-research-and-plan.md). Phases R1 (phone use) and
R2 (location capture) were started together on 27 Oct 2026 at the owner's request.

| Phase | Status |
|---|---|
| R0 Decisions | Done (plan section 11). TDX registration still to do before R5. |
| R1 Phone use | **Built and unit-tested; migration applied (27 Oct 2026); not installed on the phone yet.** |
| R2 Location capture | **Built and unit-tested; migration applied (27 Oct 2026); not installed on the phone yet.** |
| R3 Places, trips, commute | **Built and unit-tested; migration `20261107_routine_places.sql` written, not applied; not installed.** |
| R4–R6 | Not started. |

## Verification so far

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes: 957 tests, 0 failures (4 skipped, unchanged), among
  them the 15 new ones in `PhoneUsageTest` and `StayPointsTest`. No new compiler warnings.
- `20261104_routine_capture.sql` applied to `ugfrglbcoivkprjqvjzz`: 7 tables, RLS on, 4 owner-only policies each; the
  security advisor reports nothing new.
- Not yet exercised: everything that needs the phone (grants, usage events, transitions, the trip service, uploads).
  Lint was not run.

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

## R3: places, trips and mobility

| Piece | Where | Notes |
|---|---|---|
| Tables | `migrations/20261107_routine_places.sql` | `places` (label, category, OSM suggestion), `trips` (legs, distance, active minutes, commute flag), `mobility_daily`; `place_visits.place_id` now references `places` (on delete set null). Owner-only RLS for all four commands. |
| Places (pure) | `domain/routine/Places.kt` | A visit joins the nearest place whose 150 m circle holds it, else starts a new one. Setup's home location becomes the Home place. Work is suggested from where at least 15 weekday hours between 09:00 and 18:00 were spent over two weeks. OSM class and type map to a category; unknown tags suggest nothing. |
| Trips (pure) | `domain/routine/Trips.kt` | Between consecutive visits, up to 6 h (longer gaps are not trips). Legs from activity transitions (the one entered up to 5 min before leaving opens the trip; repeats merge; still minutes stay separate), distance along the trip's fixes or the straight line without them, active minutes on foot or bicycle, commute = Home to Work either way. |
| Mobility (pure) | `domain/routine/Mobility.kt` | Local day 00:00-24:00: coverage, minutes by category (plus unlabelled, transit, unknown), places and new places, normalised entropy, radius of gyration, time away from home, estimated outdoor daylight minutes (active legs and Outdoor places between sunrise and sunset; NOAA solar formula), similarity to the same weekday over the last 4 weeks (hourly place vectors, at least 12 shared hours), day type (unknown under 50 % coverage; travel beyond 50 km from home; workday from 3 h at work; home day from 80 % at home; else mixed). |
| Repository and worker | `data/routine/PlacesRepository.kt`, `RoutineCaptureWorker.kt`, `data/GeocodingRepository.kt` (`reverse`) | Each hourly run gives new visits their place and rebuilds trips (72 h window, last 48 h replaced, as visits); every 6 h `mobility_daily` for the last two completed days, the day's peak temperature, mean humidity and day length into `daily_context` (where most of the day was spent), and up to 3 Nominatim lookups for places without one. DELETE ALL on LOCATIONS removes places, trips and mobility too. |
| PLACES card | `ui/screens/settings/PlacesCard.kt`, Setup › Profile & goals › Data capture | Latest mobility day, commute median, range and active minutes per direction over 14 days, places by hours over 14 days; tap a place to name it and pick a category, with USE SUGGESTION. |

Tests: `PlacesTripsMobilityTest` (8).

R3 gate (plan section 10), to run on the phone: ≥ 90 % of the week's time attributed to a labelled place or a trip; commute door-to-door within 3 min of the diary; `daily_context` weather filled on ≥ 90 % of days.

Not built in R3, deliberately: the visit and trip map in the console; calendar event locations as place suggestions; place centroids that move with later visits (a place stays where its first visit was); circadian movement (needs 14 days of history per point; routine similarity covers the same idea for now); splitting vehicle legs into rail and road (R5, with TDX).

## Deviations from the plan

- The phone buffer is a file outbox, not an encrypted Room database (plan section 9 said Room). Since fixes go to
  Supabase anyway (decision 2), the buffer only holds what has not synced yet; Room's build plugin was not worth the
  risk of an unverified build.
- `place_visits` carries its own centroid (`lat`, `lon`), `n_fixes` and `open`; places (R3) will link visits by
  `place_id`. `phone_usage_daily` also stores `night_screen_min`.
- Places are labelled on a Setup card, not the Map tab: the Map tab was retired in the UX review (it now serves only
  Session Detail's route preview).
- The trip service's ongoing notification is required by Android for location in the background at this rate; it is
  on a silent, minimum-importance channel. It is not a nudge (decision 4).

## Gates still to run (plan section 10)

- R1: daily rows on ≥ 95 % of 14 days; screen time within 5 % of Digital Wellbeing on 10 days, top-5 apps within 10 %;
  bed-window use against the evening check-in's `late_screen` on ≥ 80 % of answered evenings.
- R2: 7 days with battery under 3 %/day; ≥ 98 % of fixes in Supabase within 24 h; ≥ 90 % of 20-minute visits found
  against a hand-kept diary; no visit invented during collector gaps (check low-confidence visits).

## Deploy order

1. ~~Apply `20261104_routine_capture.sql`~~ (done).
2. Build and install the app from your own machine (the Google sign-in client is registered to your debug key); Setup › Profile & goals › Data capture: grant usage access, turn PHONE USE on.
3. Grant precise location, then "Allow all the time", physical activity and the battery exemption; turn LOCATIONS on.
4. Keep the diary for the R2 gate for a week.
