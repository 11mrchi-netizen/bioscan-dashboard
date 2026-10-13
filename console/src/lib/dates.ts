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
