// A Zepp workout's distance, and what it may correct in exercise_sessions. Pure functions, no imports, so the same
// file runs in the edge function and under `node --test`.
//
// Why this exists (Oct 2026): Health Connect stored most runs from January to mid-August 2026 at about twice their
// real distance (the source app wrote those workouts, and their distance records, twice); the Running page then showed
// a 37 km "longest run" that Zepp has as 18 km and a 2:45/km "fastest run" that was a treadmill session. The Zepp sync
// already overwrote distance with Zepp's GPS distance, but:
//   - the summary distance was read from a field named `distance`, which Zepp's sport_history entries do not have
//     (they carry `dis` and `highPrecisionDistance`, in metres), so a workout without a GPS track (treadmill) was
//     never corrected;
//   - only one row of a duplicated session was corrected, and the run de-duplication then kept the larger, wrong one.

/** Zepp's own distance for a workout in km: the GPS track's last point, else the summary's distance (treadmill). */
export function zeppDistanceKm(gpsLastKm: number | null | undefined, summaryDistanceM: number | null | undefined): number | null {
  if (typeof gpsLastKm === "number" && Number.isFinite(gpsLastKm) && gpsLastKm > 0) return gpsLastKm;
  if (typeof summaryDistanceM === "number" && Number.isFinite(summaryDistanceM) && summaryDistanceM > 0) return summaryDistanceM / 1000;
  return null;
}

/** The summary entry's distance in metres: `highPrecisionDistance`, else `dis` (both strings or numbers). */
export function summaryDistanceM(entry: Record<string, unknown>): number | null {
  for (const key of ["highPrecisionDistance", "dis"]) {
    const v = Number(entry[key]);
    if (entry[key] !== undefined && entry[key] !== null && entry[key] !== "" && Number.isFinite(v) && v > 0) return v;
  }
  return null;
}

/**
 * The exercise_sessions type for an unmatched Zepp workout. Codes seen on this account: 1 outdoor run, 7 trail run,
 * 9 outdoor ride, 52 strength. 8 is the treadmill in Huami's sport list (Gadgetbridge); not yet seen here.
 */
export function exerciseTypeForZeppSport(sportType: string): string {
  switch (sportType) {
    case "1":
    case "7":
    case "8":
      return "run";
    case "9":
      return "ride";
    case "52":
      return "strength";
    default:
      return "other";
  }
}

/** Fastest believable average speed per session type, km/h; above it the value is a unit error, not a workout. */
const MAX_AVG_SPEED_KMH: Record<string, number> = { run: 30, walk: 12, hike: 15, ride: 90 };

/**
 * A workout's average speed in km/h from the summary's `avg_pace`, which is seconds per METRE (0.362 is 6:02/km;
 * checked against run_time / dis on stored workouts). Reading it as seconds per km stored speeds of thousands of km/h
 * (Oct 2026). Null when there is no pace or the speed is not believable for the session type.
 */
export function avgSpeedKmhFromPace(paceSecPerM: number | null | undefined, type: string | null | undefined): number | null {
  if (typeof paceSecPerM !== "number" || !Number.isFinite(paceSecPerM) || paceSecPerM <= 0) return null;
  const kmh = 3.6 / paceSecPerM;
  const max = MAX_AVG_SPEED_KMH[type ?? ""] ?? 120;
  return kmh <= max ? kmh : null;
}

/**
 * A workout's average speed in km/h: its distance over moving time (the list entry's run_time), as the watch shows it.
 * Zepp's avg_pace is only the fallback without both: on interval and hill sessions it ran up to a third slower than
 * distance over time (Oct 2026). Null when neither gives a speed believable for the session type.
 */
export function workoutAvgSpeedKmh(
  distanceKm: number | null | undefined,
  movingMin: number | null | undefined,
  paceSecPerM: number | null | undefined,
  type: string | null | undefined,
): number | null {
  if (typeof distanceKm === "number" && typeof movingMin === "number" && distanceKm > 0 && movingMin > 0) {
    const kmh = distanceKm / (movingMin / 60);
    if (Number.isFinite(kmh) && kmh <= (MAX_AVG_SPEED_KMH[type ?? ""] ?? 120)) return kmh;
  }
  return avgSpeedKmhFromPace(paceSecPerM, type);
}

/** Distances only make sense for these session types; a strength session keeps none. */
export function takesDistance(type: string | null | undefined): boolean {
  return type === "run" || type === "ride" || type === "walk" || type === "hike";
}

/** Zepp's sport code for a trail run. */
export const ZEPP_TRAIL_SPORT = "7";

/**
 * A Zepp trail run's session details with the trail tag (details.route_type = "trail"), or null when nothing changes:
 * only a session with no route type is tagged, so a road or mixed tag set by hand stays. The app reads trail runs from
 * this tag (Running page, road pace).
 */
export function trailTaggedDetails(details: Record<string, unknown> | null | undefined, sportType: string): Record<string, unknown> | null {
  if (sportType !== ZEPP_TRAIL_SPORT) return null;
  const route = details?.route_type;
  if (route !== undefined && route !== null && route !== "") return null;
  return { ...(details ?? {}), route_type: "trail" };
}
