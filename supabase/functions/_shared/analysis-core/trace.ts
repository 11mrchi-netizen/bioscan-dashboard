// Optional per-step timing for the run* engines, so a request the Edge runtime kills for CPU time (HTTP 546) still shows in
// the function logs where the time went. Erasable TypeScript, no I/O of its own.
//
// A step is reported with the wall time since the previous step: wall time includes waits on the database, so read compute
// steps (models, curves) against I/O ones (reads, publish). Step names are catalogue ids and positions, never anything a
// person typed, and the counts are sizes, so no personal data reaches a log.
export type Trace = (step: string, ms: number, info?: Record<string, number>) => void

/** A `mark(step, info)` function that reports the time since the previous mark (or since creation); a no-op without a trace. */
export function stepTimer(trace?: Trace, now: () => number = () => performance.now()): (step: string, info?: Record<string, number>) => void {
  let last = now()
  return (step, info) => {
    const t = now()
    trace?.(step, t - last, info)
    last = now()
  }
}
