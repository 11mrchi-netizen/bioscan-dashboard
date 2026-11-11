# Stress days and shoes (2026-11-11)

## Stress

- zepp-extract asks for `all_day_stress` exactly as ZeppBridge does:
  `/users/{id}/events?eventType=all_day_stress&from=&to=&limit=&reverse=0&userId=`.
  There is one item per **UTC** day. Its samples (every 5 minutes, `data` is a JSON string) run 00:00–24:00 UTC, which
  is 08:00–08:00 local.
- An empty item list does not mean the watch skipped the day. It means the watch had not synced to the Zepp cloud when
  we asked. The next pull fills it in: 10–11 Nov were empty at first and arrived with the pull at 02:24 UTC on 11 Nov.
- The app regroups every sample by local day (`domain/stress/StressLocalDays.kt`). It also recomputes each day's
  average, range and band shares from that day's own samples, because Zepp's roll-up covers the UTC day. Bands:
  1–39 relaxed, 40–59 normal, 60–79 medium, 80–100 high.

## Shoes

- Each workout summary in `sport_history` carries `gearId`, the code of the shoes picked on the watch or in the app.
  It is empty when no shoes were picked. Zepp sends **no shoe names** and has no gear endpoint (ZeppBridge says the
  same). Bike rides (types 9, 10), walks and strength sessions carry no code. Runs (1), trail runs (7) and treadmill
  runs (8) do.
- `zepp_workout_detail.gear_id` stores the code. zepp-extract writes it, and a trigger fills it in from the stored
  summary when the extractor does not (migrations `20261111043312_shoes`, `20261111043402_shoes_gear_trigger`).
- `shoes`: name, use (road / trail / treadmill / any), the Zepp code, a small photo, km already on them when added,
  and the km to retire them at. `exercise_sessions.shoe_id` is the person's own choice for one session.
- A run's shoes (`domain/Shoes.kt`): the person's choice first, then the shoe holding its code. A code no shoe has is
  "unknown" and gets matched once, from Setup › TRAINING SETUP › SHOES or from the run's SUMMARY.
- The shoe page shows its km (the km when added plus its runs), wear against the retirement distance, its runs, and the
  records set in it. A record is a run that beat the best of every run before it: longest and fastest of 5 km or more
  on road; longest, biggest climb and fastest grade-adjusted of 10 km or more on trail.
