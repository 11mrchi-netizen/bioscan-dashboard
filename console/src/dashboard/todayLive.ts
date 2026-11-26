// The day's plan kept current on the web (Nov 2026). The phone saves TODAY's plan (today_plans) only when TODAY is built
// on the phone, so the saved copy stops at the last time the tab was open there. What is logged afterwards (a meal, a
// workout, a routine, supplements, the evening check-in) lands in Supabase straight away; this lays it over the saved
// plan, so the web shows the plan as the phone would draw it now:
//   - SO FAR is the sum of the day's meals, against the saved target;
//   - a meal row is done once a meal sits in its slot (meals.meal_slot, else the planned meal nearest in time);
//   - a planned session is done once a recorded workout started within 4 h of it;
//   - a supplement row counts down by what was logged in its block since the plan was saved;
//   - "Close the day" is done once that day's evening check-in exists;
//   - meals, workouts, routines and breathwork that no plan row holds are added as their own done rows.
// Logged times are wall clock labelled +00 (CLAUDE.md, "Dates and times"): their digits are read as local, never shifted.
import type { DayPlan, PlanRowJson, SoFarJson } from './todayData'

export interface LoggedMeal { id?: number; logged_at: string; meal_slot: string | null; calories: number | null; carbs_g: number | null; protein_g: number | null; fat_g: number | null; description: string | null }
export interface LoggedSession { start_time: string; type: string | null; duration_min: number | null }
export interface LoggedSupplement { taken_at: string; created_at: string }
export interface LoggedRoutine { started_at: string | null; minutes: number | null; name: string | null }
export interface LoggedBreathwork { started_at: string; duration_s: number | null; pattern_name: string | null }

export interface DayLogs {
  meals: LoggedMeal[]
  sessions: LoggedSession[]
  supplements: LoggedSupplement[]
  routines: LoggedRoutine[]
  breathwork: LoggedBreathwork[]
  /** Evening check-in saved for the day / for the day before. */
  closed: boolean
  closedBefore: boolean
}

export type LiveKind = PlanRowJson['kind'] | 'ACTIVITY'
export interface LiveRow extends Omit<PlanRowJson, 'kind'> { kind: LiveKind; /** Added from the log: no plan row held it. */ logged?: boolean; /** The logged meals this row stands for (meals.id), for its per-meal view. */ mealIds?: number[] }
export interface LivePlan { rows: LiveRow[]; so_far?: SoFarJson; builtAt: string | null }

const SESSION_WINDOW_MIN = 4 * 60
const DUPLICATE_WINDOW_MIN = 10

/** "HH:MM" of a logged (wall clock) time. */
export function wallTime(iso: string | null | undefined): string | undefined {
  const m = iso?.match(/[T ](\d{2}):(\d{2})/)
  return m ? `${m[1]}:${m[2]}` : undefined
}
const minutesOf = (hm: string | undefined) => (hm ? Number(hm.slice(0, 2)) * 60 + Number(hm.slice(3, 5)) : null)

/** The supplement block a wall clock time belongs to (domain/SupplementsToday.kt, supplementBucketAt). */
export function supplementBucket(hm: string | undefined): string | null {
  const m = minutesOf(hm)
  if (m == null) return null
  const h = Math.floor(m / 60)
  return h < 4 ? null : h < 11 ? 'morning' : h < 17 ? 'afternoon' : 'night'
}

/** The slot a plan's meal row stands for ("Pre-session" → pre_session). */
const rowSlot = (title: string) => title.trim().toLowerCase().replace(/[-\s]+/g, '_')
const titleCase = (s: string) => s.replace(/_/g, '-').replace(/^./, (c) => c.toUpperCase())
const sum = (xs: (number | null)[]) => xs.reduce<number>((a, x) => a + (x ?? 0), 0)

export function livePlan(saved: DayPlan | null, logs: DayLogs): LivePlan {
  const builtAt = saved?.builtAt ?? null
  const rows: LiveRow[] = (saved?.rows ?? []).map((r) => ({ ...r }))

  // ---- meals and SO FAR ----
  const mealRows = rows.filter((r) => r.kind === 'MEAL')
  const slotRow = new Map(mealRows.map((r) => [rowSlot(r.title), r]))
  for (const m of logs.meals) {
    const t = wallTime(m.logged_at)
    let row = m.meal_slot ? slotRow.get(m.meal_slot.toLowerCase()) : undefined
    if (!row && !m.meal_slot) {
      // Unlabelled: the planned meal nearest in time, as the phone places it.
      const at = minutesOf(t)
      if (at != null) row = mealRows.reduce<LiveRow | undefined>((best, r) => {
        const rm = minutesOf(r.time)
        if (rm == null) return best
        const bm = minutesOf(best?.time)
        return bm == null || Math.abs(rm - at) < Math.abs(bm - at) ? r : best
      }, undefined)
    }
    if (row) { row.done = true; if (m.id != null) (row.mealIds ??= []).push(m.id); continue }
    rows.push({
      time: t, title: m.meal_slot ? titleCase(m.meal_slot) : 'Meal', kind: 'MEAL', done: true, logged: true,
      detail: [m.calories != null ? `${Math.round(m.calories)} kcal` : null, m.description].filter(Boolean).join(' · ') || undefined,
      kcal: m.calories ?? undefined, mealIds: m.id != null ? [m.id] : undefined,
    })
  }
  const so_far: SoFarJson | undefined = logs.meals.length || saved?.so_far ? {
    kcal: sum(logs.meals.map((m) => m.calories)),
    target_kcal: saved?.so_far?.target_kcal,
    carbs_g: sum(logs.meals.map((m) => m.carbs_g)),
    protein_g: sum(logs.meals.map((m) => m.protein_g)),
    fat_g: sum(logs.meals.map((m) => m.fat_g)),
  } : undefined

  // ---- workouts ----
  // The same workout can arrive from two sources (watch app and Health Connect): one start within 10 min of the same type.
  const sessions = [...logs.sessions].sort((a, b) => a.start_time.localeCompare(b.start_time)).filter((s, i, all) => {
    const prev = all[i - 1]
    if (!prev) return true
    const a = minutesOf(wallTime(prev.start_time)), b = minutesOf(wallTime(s.start_time))
    return !(prev.start_time.slice(0, 10) === s.start_time.slice(0, 10) && a != null && b != null && b - a <= DUPLICATE_WINDOW_MIN && (prev.type ?? '') === (s.type ?? ''))
  })
  const free = rows.filter((r) => r.kind === 'SESSION')
  for (const s of sessions) {
    const t = wallTime(s.start_time)
    const at = minutesOf(t)
    let pick: LiveRow | undefined
    let gap = Infinity
    for (const r of free) {
      const rm = minutesOf(r.time)
      const g = rm == null || at == null ? SESSION_WINDOW_MIN : Math.abs(rm - at)
      if (g <= SESSION_WINDOW_MIN && g < gap) { pick = r; gap = g }
    }
    if (pick) { pick.done = true; free.splice(free.indexOf(pick), 1); continue }
    rows.push({
      time: t, title: s.type ? titleCase(s.type) : 'Workout', kind: 'SESSION', done: true, logged: true,
      detail: s.duration_min != null ? `${Math.round(s.duration_min)} min` : undefined, duration_min: s.duration_min ?? undefined,
    })
  }

  // ---- supplements: the saved "N due" counts down by what was logged in that block after the plan was saved ----
  const since = builtAt ? Date.parse(builtAt) : -Infinity
  for (const r of rows) {
    if (r.kind !== 'SUPPLEMENTS') continue
    const bucket = r.title.trim().split(/\s+/)[0]?.toLowerCase()
    const due = Number(r.detail?.match(/(\d+)\s+due/)?.[1])
    if (!Number.isFinite(due)) continue
    const taken = logs.supplements.filter((x) => Date.parse(x.created_at) > since && supplementBucket(wallTime(x.taken_at)) === bucket).length
    if (taken === 0) continue
    const left = due - taken
    if (left <= 0) { r.done = true; r.detail = 'taken' } else r.detail = `${left} due`
  }

  // ---- closing the day ----
  for (const r of rows) {
    if (r.kind !== 'CLOSE' || !r.title.startsWith('Close ')) continue
    if (r.title === 'Close the day' ? logs.closed : logs.closedBefore) r.done = true
  }

  // ---- routines and breathwork ----
  for (const x of logs.routines) {
    rows.push({ time: wallTime(x.started_at), title: x.name ?? 'Routine', kind: 'ACTIVITY', done: true, logged: true,
      detail: x.minutes != null ? `${Math.round(x.minutes)} min` : undefined })
  }
  for (const x of logs.breathwork) {
    rows.push({ time: wallTime(x.started_at), title: x.pattern_name ? `Breathwork · ${x.pattern_name}` : 'Breathwork', kind: 'ACTIVITY', done: true, logged: true,
      detail: x.duration_s != null ? `${Math.max(1, Math.round(x.duration_s / 60))} min` : undefined })
  }

  // As the phone orders them: untimed rows first, then by time (a stable sort keeps the plan's own order on ties).
  rows.sort((a, b) => (a.time == null ? 0 : 1) - (b.time == null ? 0 : 1) || (a.time ?? '').localeCompare(b.time ?? ''))
  return { rows, so_far, builtAt }
}
