import type { MetricResult } from './metricAdapter'

export function pearson(xs: number[], ys: number[]): number {
  const n = xs.length
  if (n < 2) return NaN
  const meanX = xs.reduce((a, b) => a + b, 0) / n
  const meanY = ys.reduce((a, b) => a + b, 0) / n
  let num = 0, sdX = 0, sdY = 0
  for (let i = 0; i < n; i++) {
    num  += (xs[i] - meanX) * (ys[i] - meanY)
    sdX  += (xs[i] - meanX) ** 2
    sdY  += (ys[i] - meanY) ** 2
  }
  return num / Math.sqrt(sdX * sdY)
}

export function buildPairs(
  resultX: MetricResult, resultY: MetricResult, lag: number
): { xs: number[]; ys: number[]; dates: string[] } {
  const mapY = new Map(resultY.points.map(p => [p.date, p.value]))
  const xs: number[] = [], ys: number[] = [], dates: string[] = []
  for (const { date, value: vx } of resultX.points) {
    if (vx === null) continue
    const yDate = new Date(date)
    yDate.setDate(yDate.getDate() + lag)
    const yDateStr = yDate.toISOString().slice(0, 10)
    const vy = mapY.get(yDateStr)
    if (vy === null || vy === undefined) continue
    xs.push(vx); ys.push(vy); dates.push(date)
  }
  return { xs, ys, dates }
}
