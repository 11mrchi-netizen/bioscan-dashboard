// Port of generate/LayoutPlanner.kt: the suggested week for a strength template and a conditioning protocol, and what
// looks wrong with a layout the lifter has edited. Warnings never block.

import { plusWeekday, type Weekday, weekdayShort } from './dates.ts'
import type { ConditioningProtocolDef, StrengthModuleDef } from './definitions.ts'
import { type CondSlot, type DayPlan, isRest } from './timeline.ts'

const gapTo = (from: Weekday, to: Weekday): number => (to - from + 7) % 7
const CONDITIONING_KINDS = new Set(['lic', 'hic', 'wc'])

export function hingeSessions(m: StrengthModuleDef): Set<number> {
  const roles = new Map(m.slots.map((s) => [s.id, s.role]))
  const out = new Set<number>()
  m.sessions.forEach((s, i) => { if (s.slots.some((id) => roles.get(id) === 'hinge')) out.add(i) })
  return out
}

export function suggestLayout(strength: StrengthModuleDef | null | undefined, protocol: ConditioningProtocolDef | null | undefined, startWeekday: Weekday, conditioningPerWeek?: number | null): DayPlan[] {
  const at = (position: number): Weekday => plusWeekday(startWeekday, position - 1)
  const strengthByDay = new Map<Weekday, number>()
  strength?.day_positions.forEach((pos, i) => strengthByDay.set(at(pos), i))
  const cond = new Map<Weekday, CondSlot>()
  if (protocol) {
    if (protocol.default_layout.length > 0) {
      for (const d of protocol.default_layout) {
        if (!CONDITIONING_KINDS.has(d.kind)) continue
        const day = at(d.day)
        if (!cond.has(day)) cond.set(day, { kind: d.kind, minutes: d.minutes ?? null })
      }
    } else {
      const b = protocol.budget
      const n = Math.min(7, Math.max(0, conditioningPerWeek ?? (b?.sessions_per_week ? Math.trunc(b.sessions_per_week.min) : undefined) ?? b?.min_conditioning_sessions_per_week ?? 1))
      const free = [0, 1, 2, 3, 4, 5, 6].map((i) => plusWeekday(startWeekday, i)).filter((d) => !strengthByDay.has(d))
      const kind = protocol.suggested.hic.length > 0 && protocol.suggested.lic.length === 0 ? 'hic' : 'lic'
      const count = Math.min(n, free.length)
      for (let i = 0; i < count; i++) cond.set(free[Math.floor((i * free.length) / count)], { kind })
    }
  }
  // Hard conditioning the day after a deadlift session moves to the next free day.
  if (strength) {
    const hinge = hingeSessions(strength)
    const hingeDays = new Set([...strengthByDay].filter(([, i]) => hinge.has(i)).map(([d]) => d))
    for (const [day, slot] of [...cond]) {
      if (slot.kind !== 'hic' || !hingeDays.has(plusWeekday(day, -1))) continue
      const later = [1, 2, 3, 4, 5, 6].map((k) => plusWeekday(day, k))
      const window: Weekday[] = []
      for (const d of later) { if (gapTo(startWeekday, d) > gapTo(startWeekday, day)) window.push(d); else break }
      const target = window.find((d) => !cond.has(d) && !strengthByDay.has(d) && !hingeDays.has(plusWeekday(d, -1)))
      if (target != null) { cond.delete(day); cond.set(target, slot) }
    }
  }
  return [0, 1, 2, 3, 4, 5, 6].map((i) => plusWeekday(startWeekday, i)).map((d) => ({ weekday: d, strengthSession: strengthByDay.get(d) ?? null, conditioning: cond.get(d) ?? null }))
}

const fmtInt = (x: number): string => String(Math.trunc(x))

export function layoutWarnings(layout: DayPlan[], strength: StrengthModuleDef | null | undefined, protocol: ConditioningProtocolDef | null | undefined): string[] {
  const out: string[] = []
  const days = new Map(layout.map((d) => [d.weekday, d]))
  const next = (d: Weekday) => days.get(plusWeekday(d, 1))
  if (strength) {
    const placed = layout.map((d) => d.strengthSession).filter((x): x is number => x != null)
    const missing = strength.sessions.map((_, i) => i).filter((i) => !placed.includes(i))
    if (missing.length > 0) out.push(`${strength.title}: ${missing.length} of ${strength.sessions.length} strength sessions are not on a day yet`)
    if (placed.length !== new Set(placed).size) out.push('A strength session is on two days')
    const templateAdjacent = strength.day_positions.some((p, i, a) => i > 0 && p - a[i - 1] === 1)
    if (!templateAdjacent) {
      for (const d of layout) {
        if (d.strengthSession != null && next(d.weekday)?.strengthSession != null) {
          out.push(`Strength on ${weekdayShort(d.weekday)} and ${weekdayShort(plusWeekday(d.weekday, 1))} back to back: ${strength.title} spaces its sessions`)
        }
      }
    }
    const hinge = hingeSessions(strength)
    for (const d of layout) {
      const c = d.conditioning
      if (!c || c.kind !== 'hic') continue
      if (d.strengthSession != null && hinge.has(d.strengthSession)) out.push(`Hard conditioning on ${weekdayShort(d.weekday)} shares a day with a deadlift session`)
      const prev = days.get(plusWeekday(d.weekday, -1))?.strengthSession
      if (prev != null && hinge.has(prev)) out.push(`Hard conditioning on ${weekdayShort(d.weekday)} follows a deadlift day: consider moving one of them`)
    }
  }
  const restDays = layout.filter(isRest).length
  const minRest = protocol?.budget?.min_rest_days_per_week ?? 1
  if (restDays < minRest) out.push(`Only ${restDays} rest day(s) in the week; ${protocol?.title ?? 'the plan'} asks for at least ${minRest}`)
  if (protocol) {
    const b = protocol.budget
    const lic = layout.filter((d) => d.conditioning?.kind === 'lic').length
    const hic = layout.filter((d) => d.conditioning?.kind === 'hic').length
    const all = layout.filter((d) => d.conditioning != null).length
    if ((b?.low_intensity_minutes_per_week?.min ?? 0) > 0 && lic === 0) out.push(`${protocol.title} expects low-intensity work but no day has a LIC session`)
    const hr = b?.high_intensity_per_week
    if (hr && hic > hr.max) out.push(`${hic} hard conditioning days; ${protocol.title} allows at most ${fmtInt(hr.max)} a week`)
    if (hr && hr.min > 0 && hic < hr.min) out.push(`${hic} hard conditioning day(s); ${protocol.title} asks for at least ${fmtInt(hr.min)}`)
    if (b?.min_conditioning_sessions_per_week != null && all < b.min_conditioning_sessions_per_week) out.push(`${all} conditioning day(s); ${protocol.title} asks for at least ${b.min_conditioning_sessions_per_week}`)
    if (b?.sessions_per_week && all > b.sessions_per_week.max) out.push(`${all} conditioning day(s); ${protocol.title} allows at most ${fmtInt(b.sessions_per_week.max)}`)
    const li = b?.low_intensity_minutes_per_week
    if (li && lic > 0 && b?.session_min_minutes != null && li.max / lic < b.session_min_minutes) out.push(`${lic} LIC days is more than ${fmtInt(li.max)} minutes a week supports at ${b.session_min_minutes} minutes each`)
  }
  return [...new Set(out)]
}
