# Routine intelligence: implementation status

Plan, research and the owner's decisions: [01-research-and-plan.md](01-research-and-plan.md). Phases R1 (phone use) and
R2 (location capture) were started together on 27 Oct 2026 at the owner's request.

| Phase | Status |
|---|---|
| R0 Decisions | Done (plan section 11). TDX registration still to do before R5. |
| R1 Phone use | **Built and unit-tested; migration applied (27 Oct 2026); not installed on the phone yet.** |
| R2 Location capture | **Built and unit-tested; migration applied (27 Oct 2026); not installed on the phone yet.** |
| R3 Places, trips, commute | **Built and unit-tested; migration `20261107_routine_places.sql` applied (27 Oct 2026); not installed.** |
| R4 Analysis integration | **Built and tested (core and Android); `analysis-run` not redeployed yet, so the nightly run still uses catalogue 2.0.0** (see "Deploying analysis-run" below). Day-type covariate and health-episode context deferred (below). |
| R5 UX and alternatives | **Built and unit-tested; `transit-alternatives` written, not deployed, TDX calls not verified (no TDX account, host unreachable from the build session).** |
| R6 Routine experiments | **Built and tested (core 306, Android 1,036); migration `20261108_routine_experiments.sql` applied (27 Oct 2026); needs the `analysis-run` redeploy above.** |

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

## R4: routine data in the analysis engine

| Piece | Where | Notes |
|---|---|---|
| Routine series | `analysis-core/routineExposures.ts`, `runRelationships.ts` (`buildFrame`, `loadFrame`) | `screen_bed_min`, `night_phone`, `screen_pm_social` from phone use (a value only on a measured day); `outdoor_daylight_min`, `home_day`, `travel_day` from `mobility_daily` (only days covered 80 % or more; an unknown day type is unknown); `active_commute` (10 min or more on foot or bicycle) and `commute_long` (at least 1.25 times the median of the last 28 days' commute days, from 5 of them) on commute days only. The four tables are read softly, like the phase B tables. |
| Catalogue 2.1.0 | `analysis-core/hypotheses.ts` | Ten questions: phone in the hour before sleep (per 15 min) and that night's sleep and next-morning HRV; the phone unlocked at night and next-day energy; social and video apps after 21:00 (per 30 min) and sleep; outdoor daylight (per 30 min) and sleep; a home day and next-day energy; a travel day and next-morning HRV and resting heart rate; an active commute and next-morning HRV; a longer commute and that night's sleep. No-effect zones as the existing outcomes'. `CORE_VERSION` 3.0.0-alpha.10. |
| Engine fix | `analysis-core/relationship.ts` `buildDesign` | An exposure that exists only on some weekdays (commutes) left weekday columns empty or the weekday set without its reference level, so the fit could not be inverted and every commute question read "too few distinct days". Empty weekday columns are dropped and the first weekday present becomes the reference. A design with every weekday present is unchanged (all 289 core tests pass, the existing gates included). |
| Gates | `analysis-core/routineExposures.test.ts` | Null data: 0 of 401 routine questions read clear over 60 simulated histories (gate 5 %). A home pattern locked to weekends reads not testable ("follows a fixed weekly schedule"), not an effect. |
| Evidence board | Android `domain/EvidenceBoard.kt`, console `Confirmatory.tsx` | The new questions in catalogue order, in the existing HRV / resting heart rate / sleep / energy groups. |
| Active travel | Android `ui/screens/status/ActiveTravelCard.kt` on Training › Load, `domain/routine/Trips.kt` `activeTravel` | Minutes on foot and on a bicycle in trips over the last 7 days and the 7 before, beside training load, never added to it (the session-load model needs a session's effort). Shown once Locations is on. |
| Metric registry | `metric-registry.json` 1.1.0, doc 02 | Phone use before sleep, phone use at night, late social and video use, outdoor daylight time (estimated, product-defined), day type (product-defined), commute time, active travel minutes. |

Deviation: the plan's "commute over its usual time and evening stress" is asked of that night's sleep, because the evidence board's outcome groups are HRV, resting heart rate, sleep and energy, and stress is already an exposure.

Deferred from R4, with the reason:
- **Day type as a covariate in the expected bands and the typicality scorecard.** Both are calibrated models with published gates (band coverage 78-82 %, flag rates); a new covariate needs those gates re-run on real day-type data, and there is none yet. Revisit after 8 weeks of `mobility_daily`.
- **Mobility context on health-concern episodes.** Same reason: no mobility history exists to show against an episode yet.

To go live: redeploy the `analysis-run` Edge Function (it bundles `_shared/analysis-core`). Until then the nightly run keeps publishing catalogue 2.0.0 under engine 3.0.0-alpha.9.

## R5: screens and commute alternatives

| Piece | Where | Notes |
|---|---|---|
| Lab › ROUTINE | Android `ui/screens/status/RoutineTab.kt`, `nav/TopLevelTab.kt` (`LabTab.Routine`) | THE WEEK BY PLACE: the last 7 days as bars by Home, Work, Transit, Elsewhere and Unknown (from `mobility_daily`). COMMUTE: median, range and active minutes per direction over 4 weeks, and LOOK UP OTHER WAYS. PHONE · LAST 7 DAYS: daily screen time, the hour before sleep, nights unlocked, the five most used apps. |
| Today | `RoutineCards.kt` `RoutineTodayCard`, at the end of Today | YESTERDAY'S ROUTINE in up to three sentences (time by place, commute against its 4-week median from 3 commutes, phone in the hour before sleep); hidden until a routine stream is on and yesterday has something to say. |
| Log | `RoutineCards.kt` `UnloggedVisitsCard`, first item of the Log | Visits of 30 min or more to a place labelled Exercise with no session starting within 30 min of them, last 7 days. A line, not a notification (owner decision 4). |
| Summaries (pure) | `domain/routine/RoutineSummary.kt` | Week strip, commute by direction, phone week, unlogged visits, Today's lines. |
| Alternatives (pure) | `domain/routine/CommuteAlternatives.kt` | MRT (nearest 3 stations at each end, Dijkstra over hops and 250 m interchange walks, a 3 min platform wait), MRT from a farther station (most walking for at most 8 min more), MRT then YouBike (a station 1-4 km from work with a dock that has bikes, a dock near work with room), YouBike all the way (up to 8 km), walking (up to 3 km). Street distance = 1.3 × straight line; walking 80 m/min, YouBike 220 m/min. Computed on the phone. |
| transit-alternatives | `supabase/functions/transit-alternatives/index.ts` | Returns TRTC stations, adjacent-station travel times and Taipei / New Taipei YouBike docks with live availability, from TDX with a client-credentials token (`TDX_CLIENT_ID`, `TDX_CLIENT_SECRET`); 503 `tdx_not_configured` without them. Receives no location and reads no user data. **Paths and field names follow TDX's documented v2 layout but were not called**: check one response of each before trusting the numbers. |

Tests: `RoutineSummaryTest` (8), on a synthetic two-line network.

Not in R5: shifting the departure time (needs travel times by time of day, which the API above does not give); splitting vehicle legs into rail and road (left for when TDX responses are verified); the plan's ≤ 1 notification a day (no notifications, owner decision 4).

To switch the alternatives on: register a TDX account (free), set `TDX_CLIENT_ID` and `TDX_CLIENT_SECRET` as Edge Function secrets, deploy `transit-alternatives` (verify JWT on), and compare one response of each endpoint with the parser.

## R6: experiments on routines

The evidence board's TEST button now covers six routine questions, as daily randomised experiments (the micro-randomised design the other same-day choices use). Adherence is passive: each with-day is checked against what the phone measured, so nothing has to be logged.

| Question | With-day rule | Adherence series | Outcome |
|---|---|---|---|
| `screen_bed_sleep`, `screen_bed_hrv` | no phone in the hour before sleep | `screen_bed_any` = 0 (at most 5 min) | sleep, HRV |
| `night_phone_energy` | phone left outside the bedroom overnight | `night_phone` = 0 (no unlock after sleep onset) | energy |
| `screen_pm_social_sleep` | no social or video apps after 21:00 | `screen_pm_social_any` = 0 (at most 5 min) | sleep |
| `outdoor_daylight_sleep` | 30 minutes outdoors in daylight | `daylight_day` = 1 (estimated, ≥ 30 min, mobility coverage ≥ 80 %) | sleep |
| `active_commute_hrv` | walk or cycle part of the commute | `active_commute` = 1 (≥ 10 min on foot or bicycle) | HRV |

A habit can also be the outcome: `screen_bed_min`, `screen_pm_social` and `outdoor_daylight_min` are accepted as primary or secondary experiment metrics (unit minutes, default zone 10 min; less phone and more daylight are the favourable sides). `20261108_routine_experiments.sql` (applied 27 Oct 2026) widens the two `experiments` checks; the six daily choices above do not need it.

A day the phone did not measure (Usage access off, mobility coverage under 80 %, no commute that day) counts as unknown adherence, not as a missed day.

Tests: `routineExperiments.test.ts` (3): the plan's gate (one experiment end to end, adherence from `phone_usage_daily` only, planted effect found), a habit as the outcome, and the adherence series. `RandomisedExperimentTest` covers the new choices.

## Deploying analysis-run

The function bundles 41 files (about 420 kB) from `_shared/analysis-core`. The deploy tool available to Claude sessions takes the whole source inline, which is beyond what one call can carry, and the build session had no Supabase CLI login. From a machine with the CLI:

```
supabase functions deploy analysis-run --project-ref ugfrglbcoivkprjqvjzz
```

Then call it once (or wait for the 04:30 nightly run) and check that `analysis_results` gains `relationship` rows with engine `3.0.0-alpha.10` and the ten routine keys.

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
