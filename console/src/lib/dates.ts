// Local calendar dates. `new Date().toISOString().slice(0, 10)` is the UTC date: for a user east of UTC it is still
// "yesterday" for the first hours of the local day, so "today" and the preset ranges were a day behind in the morning.
export function localDateString(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export function localToday(now: Date = new Date()): string {
  return localDateString(now)
}

export function localDaysAgo(n: number, now: Date = new Date()): string {
  const d = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  d.setDate(d.getDate() - n)
  return localDateString(d)
}

// Every calendar day from start to end inclusive (YYYY-MM-DD), for charts that must show missing days as gaps.
export function dailyAxis(start: string, end: string): string[] {
  const out: string[] = []
  const d = new Date(start + 'T00:00:00Z')
  const last = new Date(end + 'T00:00:00Z')
  while (d <= last && out.length < 4000) {
    out.push(d.toISOString().slice(0, 10))
    d.setUTCDate(d.getUTCDate() + 1)
  }
  return out
}

/** The calendar day `n` days from a local date (YYYY-MM-DD), by the local calendar: a DST change never moves it. */
export function plusDays(day: string, n: number): string {
  const [y, m, d] = day.split('-').map(Number)
  return localDateString(new Date(y, m - 1, d + n))
}

/** Monday of the week a local date is in (weeks run Monday to Sunday). */
export function mondayOf(day: string): string {
  const [y, m, d] = day.split('-').map(Number)
  return plusDays(day, -((new Date(y, m - 1, d).getDay() + 6) % 7))
}

export type WeekWindow = 'ahead' | 'recent' | 'both'

/**
 * The window of a view that draws WEEK CARDS (Plan › Sessions: NEXT 6 WEEKS, LAST 6 WEEKS, BOTH), counting this week as week 1.
 * It is made of whole weeks, Monday to Sunday: a window of "today + 41 days" ends on whatever weekday that is, and the week
 * card drawn for its last days then holds only the sessions before the cut, looking complete with one row (the bug this
 * replaces). The only edges that are not week edges are today's: AHEAD starts today and RECENT ends today, because the
 * other half of this week belongs to the other window.
 */
export function weekWindow(win: WeekWindow, today: string): { from: string; to: string } {
  const monday = mondayOf(today)
  return {
    from: win === 'ahead' ? today : plusDays(monday, -35),
    to: win === 'recent' ? today : plusDays(monday, 41), // the Sunday of the 6th week
  }
}
