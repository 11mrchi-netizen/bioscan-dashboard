// Where a long session's fuel schedule is kept, as the phone keeps it (ui/screens/fuel/FuelPlanPage.kt scheduleKey, FuelScheduleRepository.planKey): under its plan item
// (planned or recorded as one), else on the recording itself. Events have their own (kind event, on the event's page).
import { LONG_SESSION_MIN, type PlannedItem } from '../../../supabase/functions/_shared/fuel-core/index.ts'

/** The ref_key a plan's schedule is kept under: the plan item's id ("planned:<id>", "calendar:<event id>"), at most 64 characters. */
export const planKey = (planRef: string) => planRef.slice(0, 64)

export function scheduleKey(item: PlannedItem): [kind: 'planned' | 'session', ref: string] | null {
  if (item.eventId != null) return null
  if (!item.recorded) return ['planned', planKey(item.sourceId)]
  if (item.planRef != null && !item.planRef.startsWith('event:')) return ['planned', planKey(item.planRef)]
  const m = /^session:(\d+)$/.exec(item.sourceId)
  return m ? ['session', m[1]] : null
}

/** A long session that is not an event: the ones whose fuelling gets tried out. */
export const isLongNonEvent = (item: PlannedItem) => item.durationMin >= LONG_SESSION_MIN && item.eventId == null
