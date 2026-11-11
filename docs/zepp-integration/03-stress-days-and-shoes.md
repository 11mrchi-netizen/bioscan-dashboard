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

- Each workout summary in `sport_history` carries `gearId`, the ID of the shoes picked on the watch or in the app.
  It is empty when no shoes were picked. Zepp sends **no shoe names** and has no gear endpoint (ZeppBridge says the
  same). It keeps a local shoe list and matches shoes to Zepp gear IDs.
- In our data: `3ff0ae27-…` on road runs (type 1) and `aafdf107-…` on trail runs (type 7). Walks have none.
- To build: store `gear_id` on the workout, keep a shoe table (name, use, retire km, `zepp_gear_id`), and show km and
  wear per shoe.
