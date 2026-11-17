# Personal records

What counts as a record:
- **Fastest** 1K, 5K, 10K, half marathon (21.0975 km), marathon (42.195 km), 50K, 60K, 75K and 100K, inside any run.
- **Longest run** and **longest trail run**.
- **Biggest climb.**

## How it works

- **`run_best_efforts`** stores each run's fastest stretch of every record distance it covered.
  - It is read from the run's per-second distance (`zepp_workout_detail.decoded.distanceKm`).
  - The time is elapsed time, as race times are: a stop inside the stretch counts.
  - The start of the stretch is interpolated between samples.
  - A stretch faster than 2:30 /km is taken as a GPS jump and left out.
  - Treadmill runs have no track, so they give no efforts.
  - A trigger fills it when a run's detail is decoded or re-decoded. The migration read every existing run once.
- **`v_personal_records`** (a security-invoker view) lists every time a record was set.
  - A record is set when a run beats the best of every run before it. The first run of a kind starts the line.
  - It is worked out from the stored rows on each read, so editing or deleting a run never leaves a stale record.
  - The longest runs come from `exercise_sessions` and keep only plausible paces.
  - The climb uses Zepp's smoothed ascent when the run has one. Health Connect's elevation adds up barometer noise
    (1,932 m on a 13 km run that climbed 655 m). Climbs under 5 m are ignored.
- **The app** names, formats and reads the records' progress in `domain/PersonalRecords.kt`.
  - **ME › PERFORMANCE › RECORDS:** each record opens a step line of the record over time and every improvement, with
    what it gained and after how many days.
  - **Performance hub:** shows the headline records.
  - **Shoe page:** lists the records set in each pair.

Migrations: `20261111075131_personal_records`, `20261111075230_personal_records_climb`.
