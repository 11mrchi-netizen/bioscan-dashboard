// Turns raw scale_readings + the profile into the series the Body workspace draws. Pure and erasable (node --test runs it);
// the equations live in supabase/functions/_shared/body-composition/bodyComposition.ts, shared with the Android app.
// occurred_at is local wall-clock time labelled UTC (the app's convention), so the date and hour are read off the string.

import { bodyComposition, type BodyComposition, type WeighIn } from '../../../supabase/functions/_shared/body-composition/bodyComposition.ts'

export interface ScaleRow {
  occurred_at: string
  weight_kg: number
  impedance_ohm: number | null
}

export interface ProfileRow {
  date_of_birth: string | null
  sex: string | null
  height_cm?: number | null
}

export type PointStatus = 'ok' | 'no-impedance' | 'no-profile' | 'rejected'

export interface BodyPoint {
  date: string
  hour: number
  weightKg: number
  impedanceOhm: number | null
  composition: BodyComposition | null
  status: PointStatus
}

export function ageOn(dateOfBirth: string, isoDate: string): number | null {
  const dob = /^(\d{4})-(\d{2})-(\d{2})/.exec(dateOfBirth)
  const day = /^(\d{4})-(\d{2})-(\d{2})/.exec(isoDate)
  if (!dob || !day) return null
  let age = Number(day[1]) - Number(dob[1])
  if (Number(day[2]) < Number(dob[2]) || (Number(day[2]) === Number(dob[2]) && Number(day[3]) < Number(dob[3]))) age -= 1
  return age
}

// heightFallbackCm: newest body_metrics.height_cm, used when the profile has none.
export function buildPoints(rows: ScaleRow[], profile: ProfileRow | null, heightFallbackCm: number | null): BodyPoint[] {
  const heightCm = profile?.height_cm ?? heightFallbackCm
  const sex = profile?.sex
  const haveProfile = !!profile?.date_of_birth && heightCm != null && (sex === 'male' || sex === 'female')
  return rows
    .map((r): BodyPoint => {
      const date = r.occurred_at.slice(0, 10)
      const hour = Number(r.occurred_at.slice(11, 13))
      const base = { date, hour: Number.isFinite(hour) ? hour : 0, weightKg: r.weight_kg, impedanceOhm: r.impedance_ohm }
      if (r.impedance_ohm == null) return { ...base, composition: null, status: 'no-impedance' }
      if (!haveProfile) return { ...base, composition: null, status: 'no-profile' }
      const age = ageOn(profile!.date_of_birth!, date)
      if (age == null) return { ...base, composition: null, status: 'no-profile' }
      const composition = bodyComposition({ weightKg: r.weight_kg, impedanceOhm: r.impedance_ohm, heightCm: heightCm!, ageYears: age, isMale: sex === 'male' })
      return { ...base, composition, status: composition.qualityOk ? 'ok' : 'rejected' }
    })
    .sort((a, b) => (a.date + String(a.hour).padStart(2, '0')).localeCompare(b.date + String(b.hour).padStart(2, '0')))
}

// One value per day (the last weigh-in of the day that has one), oldest first.
export function dailyLast(points: BodyPoint[], pick: (p: BodyPoint) => number | null): Array<[string, number]> {
  const byDay = new Map<string, number>()
  for (const p of points) {
    const v = pick(p)
    if (v != null && Number.isFinite(v)) byDay.set(p.date, v)
  }
  return [...byDay.entries()].sort((a, b) => a[0].localeCompare(b[0]))
}

export function goodWeighIns(points: BodyPoint[]): WeighIn[] {
  return points.filter((p) => p.status === 'ok').map((p) => ({ date: p.date, weightKg: p.weightKg, split: p.composition!.split! }))
}

// Mean impedance index by time-of-day bucket (hours 0-23 → 3-hour buckets): shows how much the time of day moves the reading.
export function impedanceIndexByTimeOfDay(points: BodyPoint[]): Array<{ label: string; mean: number; n: number }> {
  const buckets = new Map<number, number[]>()
  for (const p of points) {
    const v = p.composition?.impedanceIndex
    if (v == null) continue
    const b = Math.floor(p.hour / 3)
    buckets.set(b, [...(buckets.get(b) ?? []), v])
  }
  return [...buckets.entries()].sort((a, b) => a[0] - b[0]).map(([b, xs]) => ({
    label: `${String(b * 3).padStart(2, '0')}–${String(b * 3 + 3).padStart(2, '0')}h`,
    mean: xs.reduce((s, x) => s + x, 0) / xs.length,
    n: xs.length,
  }))
}

export const STATUS_LABEL: Record<PointStatus, string> = {
  ok: 'used',
  'no-impedance': 'no impedance in the reading',
  'no-profile': 'needs height, date of birth and sex',
  rejected: 'fails the quality gate (fat-free mass above weight, body fat outside 1-70 %, or water outside 60-80 % of fat-free mass)',
}
