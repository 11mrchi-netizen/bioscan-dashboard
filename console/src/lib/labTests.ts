// The numbers of Lab › TESTS that are not daily series: a blood marker's value against the lab's reference range, and the
// sweat rate of a sweat test. Shared by the Tests page and the metric page (lib/metricData.ts), so both say the same.

export interface RangeRow { value: number | null; ref_low: number | null; ref_high: number | null; flag?: string | null }

/** The lab's reference range as text ("3.5–5", "≥ 40", "≤ 5.6"), empty when the lab gave none. */
export function rangeText(r: Pick<RangeRow, 'ref_low' | 'ref_high'>): string {
  if (r.ref_low != null && r.ref_high != null) return `${r.ref_low}–${r.ref_high}`
  if (r.ref_low != null) return `≥ ${r.ref_low}`
  if (r.ref_high != null) return `≤ ${r.ref_high}`
  return ''
}

/** Where a value sits against its reference range: below, above, within, or unknown (no value or no range). */
export function rangeSide(r: RangeRow): 'below' | 'above' | 'within' | null {
  if (r.value == null || (r.ref_low == null && r.ref_high == null)) return null
  if (r.ref_low != null && r.value < r.ref_low) return 'below'
  if (r.ref_high != null && r.value > r.ref_high) return 'above'
  return 'within'
}

export const outsideRange = (r: RangeRow): boolean => { const s = rangeSide(r); return s === 'below' || s === 'above' }

/** One line on a result against its range, in words (direction is never told by colour): "above the reference range (≤ 150) · high". */
export function rangeLine(r: RangeRow): string | null {
  const side = rangeSide(r)
  const range = rangeText(r)
  const words = side == null ? (range ? `reference ${range}` : '') : `${side} the reference range (${range})`
  const out = [words, r.flag && r.flag !== 'normal' ? r.flag : ''].filter(Boolean).join(' · ')
  return out || null
}

/**
 * The metric hue (design/tokens `--m-*`) of a blood marker, by its name as the lab wrote it (brand and spelling vary: the phone
 * matches "Triglyceride" and "Triglycerides" alike). Markers without a hue of their own take MetricInk.kt's fallback.
 */
export function markerHue(name: string): string {
  const n = name.toLowerCase()
  if (/triglycerid/.test(n)) return 'm-triglycerides'
  if (/\bhdl\b/.test(n)) return 'm-hdl'
  if (/hba1c|a1c|glycated/.test(n)) return 'm-hba1c'
  if (/glucose/.test(n)) return 'm-fasting-glucose'
  return 'm-weight-ema'
}

export interface SweatTestNumbers { duration_min: number | null; weight_before_kg: number | null; weight_after_kg: number | null; fluid_in_ml: number | null; urine_ml: number | null }

/** Sweat rate in ml an hour, the phone's rule (domain/fuel/SweatProfile.kt): null when impossible (under 100 or over 4000). */
export function sweatRate(t: SweatTestNumbers): number | null {
  if (!t.duration_min || t.duration_min <= 0 || t.weight_before_kg == null || t.weight_after_kg == null) return null
  const loss = (t.weight_before_kg - t.weight_after_kg) * 1000 + (t.fluid_in_ml ?? 0) - (t.urine_ml ?? 0)
  const rate = loss / (t.duration_min / 60)
  return rate >= 100 && rate <= 4000 ? rate : null
}
