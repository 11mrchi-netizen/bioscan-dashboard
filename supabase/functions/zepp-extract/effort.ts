// DAV-272: Grade-Adjusted Pace (GAP), Efficiency Factor (EF) and HR decoupling
// from a decoded Zepp workout's per-second series. Pure functions, no imports,
// so the same file runs in the edge function and under `node effort.test.ts`.
//
// Method (docs/trail-intelligence/11-gap-efficiency-factor.md):
//  1. Smooth altitude (GPS/baro altitude is noisy second to second).
//  2. Cut the run into segments of >= SEGMENT_MIN_M metres of travel and take
//     each segment's grade as (smoothed altitude change / distance).
//  3. Minetti et al. 2002 cost of transport: a segment's flat-equivalent
//     distance = distance x cost(grade) / cost(0). GAP = time / flat distance.
//     Same polynomial as domain/trail/UphillPerformance.kt's
//     minettiCostOfTransport() -- keep the two in step (parity fixture below).
//  4. EF = GAP speed (m/min) / average HR over the same segments. Only stored
//     for aerobic runs (avg HR below the watch's own lactate-threshold HR) of
//     at least EF_MIN_DURATION_S -- EF from a threshold/interval run isn't
//     comparable to an easy run's.
//  5. Decoupling = EF drop from the first to the second half of the run, in %.

export interface Point {
  offsetSeconds: number;
  value: number;
}

export interface EffortInput {
  speedKmh: Point[];
  altitudeM: Point[];
  distanceKm: Point[];
  heartRate: Point[];
  lactateThresholdHrBpm: number | null;
}

export interface EffortResult {
  gapMinPerKm: number | null;
  efficiencyFactor: number | null;
  hrDecouplingPct: number | null;
  // Smoothed total ascent -- kept only to sanity-check the altitude signal
  // against Zepp's own altitude_ascend.
  smoothedAscentM: number | null;
}

export const SEGMENT_MIN_M = 30;
export const ALTITUDE_SMOOTH_WINDOW_S = 15; // +/- seconds
export const MIN_SEGMENT_SPEED_MS = 0.5; // below this the runner is stopped
export const MAX_SEGMENT_GAP_S = 120; // a longer segment spans a pause
export const MIN_GAP_DISTANCE_M = 1000;
export const EF_MIN_DURATION_S = 20 * 60;
const MAX_GRADE = 0.45; // Minetti's fit range

export function minettiCost(i: number): number {
  return 155.4 * i ** 5 - 30.4 * i ** 4 - 43.3 * i ** 3 + 46.3 * i ** 2 + 19.5 * i + 3.6;
}

const EMPTY: EffortResult = { gapMinPerKm: null, efficiencyFactor: null, hrDecouplingPct: null, smoothedAscentM: null };

function byOffset(points: Point[]): Map<number, number> {
  return new Map(points.map((p) => [p.offsetSeconds, p.value]));
}

interface Segment {
  seconds: number;
  meters: number;
  flatMeters: number;
  hrSum: number;
  hrCount: number;
}

export function computeEffort(input: EffortInput): EffortResult {
  const dist = byOffset(input.distanceKm); // km
  const alt = byOffset(input.altitudeM);
  const hr = byOffset(input.heartRate);
  const seconds = [...dist.keys()].filter((t) => alt.has(t)).sort((a, b) => a - b);
  if (seconds.length < 60) return EMPTY;

  // Moving-average altitude over +/- ALTITUDE_SMOOTH_WINDOW_S seconds.
  const altSeries = seconds.map((t) => alt.get(t)!);
  const w = ALTITUDE_SMOOTH_WINDOW_S;
  const smooth = altSeries.map((_, i) => {
    let sum = 0, n = 0;
    for (let j = Math.max(0, i - w); j <= Math.min(altSeries.length - 1, i + w); j++) {
      sum += altSeries[j];
      n++;
    }
    return sum / n;
  });

  let smoothedAscentM = 0;
  for (let i = 1; i < smooth.length; i++) if (smooth[i] > smooth[i - 1]) smoothedAscentM += smooth[i] - smooth[i - 1];

  const flatCost = minettiCost(0);
  const segments: Segment[] = [];
  let start = 0;
  for (let i = 1; i < seconds.length; i++) {
    const meters = (dist.get(seconds[i])! - dist.get(seconds[start])!) * 1000;
    if (meters < SEGMENT_MIN_M) continue;
    const secs = seconds[i] - seconds[start];
    const speed = meters / secs;
    if (secs <= MAX_SEGMENT_GAP_S && speed >= MIN_SEGMENT_SPEED_MS) {
      const grade = Math.max(-MAX_GRADE, Math.min(MAX_GRADE, (smooth[i] - smooth[start]) / meters));
      let hrSum = 0, hrCount = 0;
      for (let k = start; k <= i; k++) {
        const h = hr.get(seconds[k]);
        if (h !== undefined && h > 0) {
          hrSum += h;
          hrCount++;
        }
      }
      segments.push({ seconds: secs, meters, flatMeters: meters * (minettiCost(grade) / flatCost), hrSum, hrCount });
    }
    start = i;
  }

  const totalMeters = segments.reduce((a, s) => a + s.meters, 0);
  const totalSeconds = segments.reduce((a, s) => a + s.seconds, 0);
  if (totalMeters < MIN_GAP_DISTANCE_M) return { ...EMPTY, smoothedAscentM };

  const flatMeters = segments.reduce((a, s) => a + s.flatMeters, 0);
  const gapMinPerKm = totalSeconds / 60 / (flatMeters / 1000);

  const efOf = (segs: Segment[]): number | null => {
    const secs = segs.reduce((a, s) => a + s.seconds, 0);
    const flat = segs.reduce((a, s) => a + s.flatMeters, 0);
    const hrSum = segs.reduce((a, s) => a + s.hrSum, 0);
    const hrCount = segs.reduce((a, s) => a + s.hrCount, 0);
    if (secs <= 0 || hrCount === 0 || hrSum <= 0) return null;
    return (flat / secs * 60) / (hrSum / hrCount);
  };

  const wholeEf = efOf(segments);
  const lt = input.lactateThresholdHrBpm;
  const avgHr = segments.reduce((a, s) => a + s.hrSum, 0) / Math.max(1, segments.reduce((a, s) => a + s.hrCount, 0));
  const aerobic = lt === null || avgHr < lt;
  const eligible = aerobic && totalSeconds >= EF_MIN_DURATION_S && wholeEf !== null;

  let hrDecouplingPct: number | null = null;
  if (eligible) {
    let acc = 0;
    const half = totalSeconds / 2;
    const first: Segment[] = [], second: Segment[] = [];
    for (const s of segments) {
      (acc < half ? first : second).push(s);
      acc += s.seconds;
    }
    const e1 = efOf(first), e2 = efOf(second);
    if (e1 !== null && e2 !== null) hrDecouplingPct = (e1 - e2) / e1 * 100;
  }

  return { gapMinPerKm, efficiencyFactor: eligible ? wholeEf : null, hrDecouplingPct, smoothedAscentM };
}
