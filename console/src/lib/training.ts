interface RunLike {
  duration_minutes: number | null
  activity_type: string | null
}

export function tss(run: RunLike): number {
  const hours = (run.duration_minutes ?? 0) / 60
  const factorMap: Record<string, number> = {
    interval: 1.3, track: 1.3, race: 1.5, tempo: 1.2, easy: 0.7
  }
  const type = (run.activity_type ?? '').toLowerCase()
  const factor = Object.entries(factorMap).find(([k]) => type.includes(k))?.[1] ?? 1.0
  return hours * factor * 100
}

export function vo2maxFromPace(distKm: number, durMin: number): number | null {
  if (!distKm || !durMin) return null
  const velKmh = distKm / (durMin / 60)
  return Math.round((-4.60 + 0.182258 * velKmh + 0.000104 * velKmh * velKmh) * 10) / 10
}

export function ema(values: number[], halfLifeDays: number): number[] {
  const k = 1 - Math.exp(-Math.LN2 / halfLifeDays)
  let acc = 0
  return values.map(v => { acc = acc + k * (v - acc); return Math.round(acc * 10) / 10 })
}
