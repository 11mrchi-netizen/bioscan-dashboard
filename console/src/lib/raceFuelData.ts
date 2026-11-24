// The reads behind an event's fuel plan, the web's version of the phone's FuelPlanRepository.loadEvent: the event, the person's weight, sweat tests and
// products, the weather at the start (Open-Meteo), the person's recent runs for a duration the event does not state, and, for an event today or
// tomorrow, the days before. Read only. Phone-only settings (carbohydrate tolerance, caffeine) and the home place are not readable here: the
// defaults are used and an event with no place has no weather.
import { supabase } from './supabase'
import { localToday } from './dates'
import { fetchHourly } from './weather'
import { loadFuelDays } from './fuelDay'
import { atTime, instant, NO_WEATHER, type RecentRun, type WeatherSeries, type IntakeHistoryResult } from '../../../supabase/functions/_shared/fuel-core/index.ts'
import { buildRaceFuel, type EventRow, type RaceFuel, productsOf, sweatTestsOf } from './raceFuelPlan'
export type { EventRow, RaceFuel } from './raceFuelPlan'

const num = (x: unknown) => (x == null ? null : Number(x))

export async function loadRaceFuel(eventId: number, now: Date = new Date()): Promise<RaceFuel | null> {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  const er = await supabase.from('athlete_events').select('id,name,kind,sport,priority,event_date,start_time,status,target_time_min,expected_duration_min,distance_km,ascent_m,start_lat,start_lon,course_profile,aid_stations').eq('id', eventId).maybeSingle()
  if (er.error) throw new Error(`athlete_events: ${er.error.message}`)
  if (!er.data) return null
  const event = er.data as unknown as EventRow
  const today = localToday(now)
  const [body, tests, prods, versions, runs] = await Promise.all([
    supabase.from('body_metrics').select('weight_kg').not('weight_kg', 'is', null).order('date', { ascending: false }).limit(1),
    supabase.from('sweat_tests').select('tested_on,session_type,intensity,duration_min,weight_before_kg,weight_after_kg,fluid_in_ml,urine_ml,temp_c,humidity_pct').order('tested_on', { ascending: false }).limit(200),
    supabase.from('fueling_products').select('id,kind,name,brand').order('name', { ascending: true }),
    supabase.from('fueling_recipe_versions').select('product_id,version,carbs_g,fluid_ml,sodium_mg,caffeine_mg,fructose_g'),
    supabase.from('exercise_sessions').select('distance_km,elevation_gain_m,duration_min').eq('type', 'run').gte('start_time', new Date(Date.parse(`${today}T00:00:00Z`) - 120 * 86400000).toISOString().slice(0, 10)).order('start_time', { ascending: false }).limit(60),
  ])
  const weightKg = num((body.data?.[0] as { weight_kg: number } | undefined)?.weight_kg)
  const sweatTests = sweatTestsOf((tests.data ?? []) as Record<string, unknown>[])
  const products = productsOf((prods.data ?? []) as { id: number; kind: string; name: string; brand: string | null }[], (versions.data ?? []) as Record<string, unknown>[])
  const recent: RecentRun[] = ((runs.data ?? []) as Record<string, unknown>[]).filter((r) => r.distance_km != null && r.duration_min != null).map((r) => ({ distanceKm: Number(r.distance_km), ascentM: Number(r.elevation_gain_m ?? 0), durationMin: Number(r.duration_min) }))

  // the weather at the start, when the event says where it is
  const startMs = event.start_time ? instant(event.start_time) : atTime(event.event_date, '09:00:00', zone)
  const dMin = Math.max(event.target_time_min ?? event.expected_duration_min ?? 60, 30)
  let weather: WeatherSeries = NO_WEATHER
  if (event.start_lat != null && event.start_lon != null) {
    try { weather = await fetchHourly(Number(event.start_lat), Number(event.start_lon), startMs - 3600000, startMs + (Math.trunc(dMin) + 60) * 60000, zone, now.getTime()) } catch { weather = NO_WEATHER }
  }
  // the days before: only for an event today or tomorrow
  const daysToGo = Math.round((Date.parse(`${event.event_date}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86400000)
  let history: IntakeHistoryResult | null = null
  if (daysToGo >= 0 && daysToGo <= 1) { try { history = (await loadFuelDays(now))?.history ?? null } catch { history = null } }

  const built = buildRaceFuel({ event, weightKg, sweatTests, products, runs: recent, weather, zone, history })
  return built ? { event, plan: built.plan, start: built.start, hasPlace: built.hasPlace, history } : null
}
