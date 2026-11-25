// The phone's fuel settings as the account keeps them (user_profile.fuel_settings, written by the phone's FuelSettingsStore) and the
// account's Home place (places, category home), read the way the phone reads them. Pure, so it is testable: fuelSettingsData.ts fetches.
// A key the phone did not write takes the default; snack null is "no afternoon snack", an absent snack the default one.
import { DEFAULT_MEAL_TIMES, DEFAULT_RACE_SETTINGS, DEFAULT_TOLERATED_G_PER_H, type RaceFuelSettings } from '../../../supabase/functions/_shared/fuel-core/index.ts'
import type { FuelSettings } from './fuelInputs.ts'

export interface AccountFuelSettings extends FuelSettings { caffeineOk: boolean; caffeineMgPerKg: number; saltySweater: boolean }
/** The settings, and whether they came from the phone (false: the phone has not sent any, so these are the defaults). */
export interface FuelSettingsRead { settings: AccountFuelSettings; fromPhone: boolean }

export const DEFAULT_ACCOUNT_FUEL_SETTINGS: AccountFuelSettings = {
  meals: DEFAULT_MEAL_TIMES, trainLow: false, toleratedGPerHour: DEFAULT_TOLERATED_G_PER_H,
  caffeineOk: DEFAULT_RACE_SETTINGS.caffeineOk, caffeineMgPerKg: DEFAULT_RACE_SETTINGS.caffeineMgPerKg, saltySweater: DEFAULT_RACE_SETTINGS.saltySweater,
}

/** java.time.LocalTime.parse's HH:mm[:ss], back as HH:mm[:ss] the way LocalTime.toString writes it. */
function timeOf(x: unknown): string | null {
  if (typeof x !== 'string') return null
  const m = /^([01]\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?$/.exec(x)
  return m ? (m[3] && m[3] !== '00' ? `${m[1]}:${m[2]}:${m[3]}` : `${m[1]}:${m[2]}`) : null
}

export function fuelSettingsOf(json: unknown): FuelSettingsRead {
  const d = DEFAULT_ACCOUNT_FUEL_SETTINGS
  if (json == null || typeof json !== 'object' || Array.isArray(json)) return { settings: d, fromPhone: false }
  const o = json as Record<string, unknown>
  const bool = (k: string, dflt: boolean) => (typeof o[k] === 'boolean' ? (o[k] as boolean) : dflt)
  const num = (k: string, dflt: number) => (typeof o[k] === 'number' && Number.isFinite(o[k]) && (o[k] as number) > 0 ? (o[k] as number) : dflt)
  return {
    fromPhone: true,
    settings: {
      meals: {
        breakfast: timeOf(o.breakfast) ?? d.meals.breakfast,
        lunch: timeOf(o.lunch) ?? d.meals.lunch,
        snack: 'snack' in o && o.snack === null ? null : timeOf(o.snack) ?? d.meals.snack,
        dinner: timeOf(o.dinner) ?? d.meals.dinner,
      },
      trainLow: bool('trainLow', d.trainLow),
      toleratedGPerHour: num('toleratedGPerHour', d.toleratedGPerHour),
      caffeineOk: bool('caffeineOk', d.caffeineOk),
      caffeineMgPerKg: num('caffeineMgPerKg', d.caffeineMgPerKg),
      saltySweater: bool('saltySweater', d.saltySweater),
    },
  }
}

export const raceSettingsOf = (s: AccountFuelSettings): RaceFuelSettings => ({ toleratedGPerHour: s.toleratedGPerHour, caffeineOk: s.caffeineOk, caffeineMgPerKg: s.caffeineMgPerKg, saltySweater: s.saltySweater })

export interface PlaceRow { lat: number; lon: number; category: string | null; stop_since: string | null; created_at: string | null }
export interface Home { lat: number; lon: number }

/** The account's Home place, as the phone's homeOf: category home, not stopped, the oldest first. */
export function homeOf(places: PlaceRow[]): Home | null {
  const homes = places.filter((p) => p.category === 'home' && p.stop_since == null && Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lon)))
    .sort((a, b) => ((a.created_at ?? '') < (b.created_at ?? '') ? -1 : (a.created_at ?? '') > (b.created_at ?? '') ? 1 : 0))
  return homes[0] ? { lat: Number(homes[0].lat), lon: Number(homes[0].lon) } : null
}

/** "09:00, 12:30, 16:00, 19:30" / "…, no snack, …" for the notes under a plan. */
export function mealTimesText(s: FuelSettings): string {
  const m = s.meals
  return [m.breakfast, m.lunch, m.snack ?? 'no snack', m.dinner].join(', ')
}
