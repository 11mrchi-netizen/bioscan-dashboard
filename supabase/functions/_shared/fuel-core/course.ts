// Port of the course timing of the Android domain/fuel/CourseTimeEstimate.kt (and the Minetti cost of transport of domain/trail): when the person
// will be where on a course, so fuel and fluid can be planned by time and by aid station.

export interface CoursePoint { distanceM: number; elevationM: number | null }
const DOWNHILL_FLOOR = 0.8
const UPHILL_CEILING = 5

/** Minetti et al. 2002 metabolic cost of transport, J/kg/m, at a grade fraction (positive uphill). */
export const minettiCostOfTransport = (i: number) => 155.4 * Math.pow(i, 5) - 30.4 * Math.pow(i, 4) - 43.3 * Math.pow(i, 3) + 46.3 * Math.pow(i, 2) + 19.5 * i + 3.6

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi)

/** Relative cost of each step of the course (1.0 = flat), aligned with points[1..]. */
export function stepCosts(points: CoursePoint[]): number[] {
  const flat = minettiCostOfTransport(0)
  const out: number[] = []
  for (let k = 0; k + 1 < points.length; k++) {
    const a = points[k]
    const b = points[k + 1]
    const d = b.distanceM - a.distanceM
    const grade = d > 0 && a.elevationM != null && b.elevationM != null ? clamp((b.elevationM - a.elevationM) / d, -0.45, 0.45) : 0
    out.push(clamp(minettiCostOfTransport(grade) / flat, DOWNHILL_FLOOR, UPHILL_CEILING) * Math.max(d, 0))
  }
  return out
}

/** Elapsed minutes at each course point when the whole course takes totalMin, spread by cost. */
export function timeAlongCourse(points: CoursePoint[], totalMin: number): number[] {
  if (points.length < 2) return points.map(() => 0)
  const costs = stepCosts(points)
  const total = costs.reduce((a, b) => a + b, 0)
  if (!(total > 0)) return points.map((_, i) => (i * totalMin) / (points.length - 1))
  let acc = 0
  return [0, ...costs.map((c) => { acc += c; return (acc / total) * totalMin })]
}

/** Elapsed minutes at km along a course, by interpolation; a straight line in distance without a course. */
export function elapsedAtKm(points: CoursePoint[], times: number[], km: number, totalMin: number, totalKm: number | null): number {
  if (points.length >= 2 && times.length === points.length) {
    const d = km * 1000
    const i = points.findIndex((p) => p.distanceM >= d)
    if (i <= 0) return i === 0 ? 0 : times[times.length - 1]
    const a = points[i - 1]
    const b = points[i]
    const f = clamp((d - a.distanceM) / (b.distanceM - a.distanceM), 0, 1)
    return times[i - 1] + (times[i] - times[i - 1]) * f
  }
  if (totalKm == null || !(totalKm > 0)) return totalMin
  return Math.min(1, km / totalKm) * totalMin
}

/** Where the person is (km) at elapsedMin; null without a course or a distance. */
export function kmAtElapsed(points: CoursePoint[], times: number[], elapsedMin: number, totalMin: number, totalKm: number | null): number | null {
  if (points.length >= 2 && times.length === points.length) {
    const i = times.findIndex((t) => t >= elapsedMin)
    if (i <= 0) return i === 0 ? 0 : points[points.length - 1].distanceM / 1000
    const f = clamp((elapsedMin - times[i - 1]) / (times[i] - times[i - 1]), 0, 1)
    return (points[i - 1].distanceM + (points[i].distanceM - points[i - 1].distanceM) * f) / 1000
  }
  return totalKm != null ? Math.min(1, elapsedMin / totalMin) * totalKm : null
}

/** Elevation (m) at km along the course; null without one. */
export function elevationAtKm(points: CoursePoint[], km: number): number | null {
  const d = km * 1000
  const i = points.findIndex((p) => p.distanceM >= d)
  if (i < 0) return points.length ? points[points.length - 1].elevationM : null
  if (i === 0) return points[0].elevationM
  const a = points[i - 1]
  const b = points[i]
  if (a.elevationM == null) return b.elevationM
  if (b.elevationM == null) return a.elevationM
  return a.elevationM + (b.elevationM - a.elevationM) * clamp((d - a.distanceM) / (b.distanceM - a.distanceM), 0, 1)
}
