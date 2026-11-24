// Number text as Java prints it, for the plans' sentences (the shared helpers of fuel-core).
import { fmt } from './day.ts'

/** Kotlin's Double.toString for the numbers a sentence names: whole numbers keep a ".0" ("3.0"), others print as they are. */
export const fmtNum = (v: number) => (Number.isInteger(v) ? `${v}.0` : String(v))
/** "%d:%02d" of minutes as h:mm, else "N min" (the plans' clock text). */
export function fmtMin(m: number): string {
  const t = Math.round(m)
  return t >= 60 ? `${Math.trunc(t / 60)}:${String(t % 60).padStart(2, '0')}` : `${t} min`
}
export { fmt }
