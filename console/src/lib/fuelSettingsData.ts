// Reads the phone's fuel settings and the Home place from the account (see fuelSettings.ts). Either read failing (offline, or the
// fuel_settings column not created yet) gives the defaults / no home, as before the phone shared them.
import { supabase } from './supabase'
import { fuelSettingsOf, homeOf, type FuelSettingsRead, type Home, type PlaceRow } from './fuelSettings'

export async function loadFuelSettings(): Promise<FuelSettingsRead> {
  const r = await supabase.from('user_profile').select('fuel_settings').maybeSingle()
  return fuelSettingsOf(r.error ? null : (r.data as { fuel_settings?: unknown } | null)?.fuel_settings ?? null)
}

export async function loadHome(): Promise<Home | null> {
  const r = await supabase.from('places').select('lat,lon,category,stop_since,created_at').eq('category', 'home')
  return r.error ? null : homeOf((r.data ?? []) as PlaceRow[])
}
