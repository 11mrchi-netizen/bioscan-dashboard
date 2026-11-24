// Reads and writes of recorded workouts (exercise_sessions) for the session page and the recorded list. The write follows the phone:
// only rpe, notes and details are ever written, and details from the row as it is when saving (lib/sessionEdit.ts).
import { supabase } from './supabase'
import { sessionPatch, type Details, type SessionInput } from './sessionEdit'

export interface SessionRow {
  id: number; type: string | null; start_time: string; end_time: string | null; duration_min: number | null; distance_km: number | null
  calories_active: number | null; calories_total: number | null; avg_hr: number | null; max_hr: number | null; elevation_gain_m: number | null
  avg_power_w: number | null; avg_speed_kmh: number | null; rpe: number | null; notes: string | null; details: Details | null; source: string | null
  health_connect_record_id: string | null
}
export interface PlannedLink { id: number; title: string | null; domain: string | null; scheduled_date: string; status: string; match_status: string | null; block_id: number | null; exercise_session_id: number | null }

const COLS = 'id,type,start_time,end_time,duration_min,distance_km,calories_active,calories_total,avg_hr,max_hr,elevation_gain_m,avg_power_w,avg_speed_kmh,rpe,notes,details,source,health_connect_record_id'
const LIST_COLS = 'id,type,start_time,duration_min,distance_km,avg_hr,rpe,source,notes'

export async function loadSession(id: number): Promise<SessionRow | null> {
  const r = await supabase.from('exercise_sessions').select(COLS).eq('id', id).maybeSingle()
  if (r.error) throw new Error(`exercise_sessions: ${r.error.message}`)
  return (r.data as SessionRow | null) ?? null
}

/** Saves what the person says about a session: reads the row's details NOW, changes only the keys the form owns, writes rpe, notes and details. */
export async function saveSession(id: number, input: SessionInput): Promise<SessionRow> {
  const cur = await supabase.from('exercise_sessions').select('details').eq('id', id).maybeSingle()
  if (cur.error) throw new Error(`exercise_sessions: ${cur.error.message}`)
  if (!cur.data) throw new Error('That session no longer exists.')
  const patch = sessionPatch((cur.data as { details: Details | null }).details, input)
  const w = await supabase.from('exercise_sessions').update({ rpe: patch.rpe, notes: patch.notes, details: patch.details }).eq('id', id).select(COLS).maybeSingle()
  if (w.error) throw new Error(`exercise_sessions: ${w.error.message}`)
  if (!w.data) throw new Error('Not saved: the row was not changed (it may have been removed).')
  return w.data as SessionRow
}

export type ListRow = Pick<SessionRow, 'id' | 'type' | 'start_time' | 'duration_min' | 'distance_km' | 'avg_hr' | 'rpe' | 'source' | 'notes'>
export const PAGE = 30

/**
 * Recorded workouts, newest first, a page at a time by keyset (start time, id) so a page never repeats or skips a row. `type` null:
 * every workout but the walks (auto-detected background movement, thousands of them, which would bury the workouts); a type: just that.
 */
export async function loadRecordedPage(type: string | null, after: { start_time: string; id: number } | null): Promise<ListRow[]> {
  let q = supabase.from('exercise_sessions').select(LIST_COLS).order('start_time', { ascending: false }).order('id', { ascending: false }).limit(PAGE)
  if (type) q = q.eq('type', type); else q = q.neq('type', 'walk')
  if (after) q = q.or(`start_time.lt.${after.start_time},and(start_time.eq.${after.start_time},id.lt.${after.id})`)
  const r = await q
  if (r.error) throw new Error(`exercise_sessions: ${r.error.message}`)
  return (r.data ?? []) as ListRow[]
}

const PCOLS = 'id,title,domain,scheduled_date,status,match_status,block_id,exercise_session_id'

/** The planned session a recorded one is linked to, and the unlinked plans of its day it could be linked to (the phone's rule: same day, still planned). */
export async function loadPlannedFor(sessionId: number, day: string): Promise<{ linked: PlannedLink | null; candidates: PlannedLink[] }> {
  const linked = await supabase.from('planned_sessions').select(PCOLS).eq('exercise_session_id', sessionId).neq('status', 'superseded').limit(1)
  if (linked.error) throw new Error(`planned_sessions: ${linked.error.message}`)
  const l = ((linked.data ?? []) as PlannedLink[])[0] ?? null
  if (l) return { linked: l, candidates: [] }
  const c = await supabase.from('planned_sessions').select(PCOLS).eq('scheduled_date', day).eq('status', 'planned').is('exercise_session_id', null).order('slot_in_day')
  if (c.error) throw new Error(`planned_sessions: ${c.error.message}`)
  return { linked: null, candidates: (c.data ?? []) as PlannedLink[] }
}

/** The phone's linkPlanned: your answer always wins (match_status manual, confirmed). Only a plan that is still planned and unlinked. */
export async function linkPlanned(plannedId: number, sessionId: number): Promise<void> {
  const r = await supabase.from('planned_sessions').update({ exercise_session_id: sessionId, match_status: 'manual', status: 'confirmed', confirmed_at: new Date().toISOString() })
    .eq('id', plannedId).eq('status', 'planned').is('exercise_session_id', null).select('id')
  if (r.error) throw new Error(`planned_sessions: ${r.error.message}`)
  if (!r.data || r.data.length === 0) throw new Error('That plan was changed meanwhile (it is no longer open to link).')
}
