// Calendar dates without a clock or a zone: a LocalDate is 'YYYY-MM-DD', a weekday is 1 (Monday) .. 7 (Sunday), as in
// java.time. The block engine works on the person's local calendar (CLAUDE.md, "Dates and times"), never on UTC instants.

export type LocalDate = string
export type Weekday = 1 | 2 | 3 | 4 | 5 | 6 | 7

export const WEEKDAY_NAMES = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const

const toUtc = (d: LocalDate): number => {
  const [y, m, day] = d.split('-').map(Number)
  return Date.UTC(y, m - 1, day)
}

const fromUtc = (ms: number): LocalDate => new Date(ms).toISOString().slice(0, 10)

export function plusDays(d: LocalDate, n: number): LocalDate {
  return fromUtc(toUtc(d) + n * 86_400_000)
}

export function daysBetween(a: LocalDate, b: LocalDate): number {
  return Math.round((toUtc(b) - toUtc(a)) / 86_400_000)
}

export function weekdayOf(d: LocalDate): Weekday {
  const js = new Date(toUtc(d)).getUTCDay() // 0 = Sunday
  return (js === 0 ? 7 : js) as Weekday
}

export function plusWeekday(w: Weekday, n: number): Weekday {
  return ((((w - 1 + n) % 7) + 7) % 7 + 1) as Weekday
}

export function weekdayShort(w: Weekday): string {
  const s = WEEKDAY_NAMES[w - 1]
  return s.charAt(0) + s.slice(1, 3).toLowerCase()
}

/** The next date on or after [d] that falls on [w]. */
export function nextOrSame(d: LocalDate, w: Weekday): LocalDate {
  return plusDays(d, (w - weekdayOf(d) + 7) % 7)
}

/** Today on the person's own calendar (the browser's zone), never the UTC date. */
export function localToday(now: Date = new Date()): LocalDate {
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export function isAfter(a: LocalDate, b: LocalDate): boolean { return a > b }
export function isBefore(a: LocalDate, b: LocalDate): boolean { return a < b }
