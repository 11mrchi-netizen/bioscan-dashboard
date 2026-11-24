// The pure part of an event's fuel plan: an event row and what was read about the person in, the plan out (tests need no database). raceFuelData.ts reads.
import {
  type AidStation, atTime, DEFAULT_RACE_SETTINGS, durationRange, estimateRunTime, type FuelProductOption, instant, type Intensity, planRaceFuel, type RaceFuelPlan,
  type RecentRun, sweatProfile, type SweatTest, type WeatherSeries, zdtString, type CoursePoint, type IntakeHistoryResult,
} from '../../../supabase/functions/_shared/fuel-core/index.ts'

export interface EventRow {
  id: number; name: string; kind: string; sport: string | null; priority: string | null; event_date: string; start_time: string | null; status: string | null
  target_time_min: number | null; expected_duration_min: number | null; distance_km: number | null; ascent_m: number | null; start_lat: number | null; start_lon: number | null
  course_profile: (number | null)[][] | null; aid_stations: unknown
}
export interface RaceFuel { event: EventRow; plan: RaceFuelPlan; start: string; hasPlace: boolean; history: IntakeHistoryResult | null }

const SPORT_TYPE: Record<string, string> = { run: 'run', trail_run: 'run', ride: 'ride', hike: 'hike', swim: 'swim', triathlon: 'run', team_sport: 'other' }
const SESSION_TYPES: Record<string, string[]> = { run: ['run'], trail_run: ['run', 'hike'] }

export const aidStationsOf = (a: unknown): AidStation[] => (Array.isArray(a) ? a : []).filter((x) => x && typeof x.name === 'string' && typeof x.km === 'number').map((x) => ({
  name: x.name, km: x.km, water: x.water ?? true, food: x.food ?? false, dropBag: x.drop_bag ?? false, cutoffMin: x.cutoff_min ?? null,
}))
export const courseOf = (c: (number | null)[][] | null): CoursePoint[] => (c ?? []).filter((p) => p[0] != null).map((p) => ({ distanceM: p[0] as number, elevationM: p[1] ?? null }))

export function intensityOf(kind: string, priority: string | null, duration: number): Intensity {
  return kind === 'race' && priority !== 'C' ? 'RACE' : duration > 90 ? 'LONG' : 'HARD'
}

export interface RaceRows {
  event: EventRow; weightKg: number | null; sweatTests: SweatTest[]; products: FuelProductOption[]; runs: RecentRun[]; weather: WeatherSeries; zone: string
  history: IntakeHistoryResult | null
}

/** The plan from what was read (pure, for tests). */
export function buildRaceFuel(r: RaceRows): { plan: RaceFuelPlan; start: string; hasPlace: boolean } | null {
  const e = r.event
  const kg = r.weightKg ?? 70
  const startMs = e.start_time ? instant(e.start_time) : atTime(e.event_date, '09:00:00', r.zone)
  const start = zdtString(startMs, r.zone)
  const estimate = e.distance_km != null && (e.sport === 'run' || e.sport === 'trail_run') ? estimateRunTime(e.distance_km, e.ascent_m ?? 0, r.runs) : null
  const d = durationRange(e.target_time_min, e.expected_duration_min, estimate)
  if (!d) return null
  const intensity = intensityOf(e.kind, e.priority, d.base)
  const plan = planRaceFuel({
    title: e.name, type: (SESSION_TYPES[e.sport ?? ''] ?? [SPORT_TYPE[e.sport ?? ''] ?? 'other'])[0], intensity, start, durationMin: d.base, durationLowMin: d.low, durationHighMin: d.high, durationBasis: d.basis,
    course: courseOf(e.course_profile), distanceKm: e.distance_km, aidStations: aidStationsOf(e.aid_stations), weather: r.weather, sweat: sweatProfile(r.sweatTests, kg), weightKg: kg,
    settings: DEFAULT_RACE_SETTINGS, products: r.products, history: r.history, loadingDays: intensity === 'RACE' && d.base > 90,
  })
  return { plan, start, hasPlace: e.start_lat != null && e.start_lon != null }
}

