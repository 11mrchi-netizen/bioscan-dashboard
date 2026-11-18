// The PLAN pages' reads (web transition plan, phase 4): planned sessions in a date window with their block's name, and the
// athlete's events (races, long sessions). Dates here are calendar dates (scheduled_date, event_date), never shifted.
import { supabase } from './supabase'

export interface PlannedSession {
  id: number; block_id: number | null; week_index: number | null; sequence_no: number | null; domain: string | null; work_kind: string | null
  title: string | null; scheduled_date: string; original_date: string | null; start_time: string | null; duration_min: number | null
  status: string; exercise_session_id: number | null; match_status: string | null; gcal_pushed_at: string | null; zepp_pushed_at: string | null
}
export interface AthleteEvent {
  id: number; name: string; kind: string; sport: string | null; priority: string | null; event_date: string; start_time: string | null; status: string | null
  target_time_min: number | null; expected_duration_min: number | null; distance_km: number | null; ascent_m: number | null; descent_m: number | null
  location_name: string | null; aid_stations: unknown; calendar_event_id: string | null; course_source: string | null; planned_route_id: number | null; exercise_session_id: number | null; notes: string | null
}

export async function loadPlannedSessions(from: string, to: string): Promise<{ sessions: PlannedSession[]; blockName: Map<number, string> }> {
  const r = await supabase.from('planned_sessions')
    .select('id,block_id,week_index,sequence_no,domain,work_kind,title,scheduled_date,original_date,start_time,duration_min,status,exercise_session_id,match_status,gcal_pushed_at,zepp_pushed_at')
    .gte('scheduled_date', from).lte('scheduled_date', to).order('scheduled_date').order('start_time', { nullsFirst: true })
  if (r.error) throw new Error(`planned_sessions: ${r.error.message}`)
  const sessions = (r.data ?? []) as PlannedSession[]
  const ids = [...new Set(sessions.map((s) => s.block_id).filter((x): x is number => x != null))]
  const blockName = new Map<number, string>()
  if (ids.length) {
    const b = await supabase.from('training_blocks').select('id,name').in('id', ids)
    for (const x of (b.data ?? []) as { id: number; name: string }[]) blockName.set(x.id, x.name)
  }
  return { sessions, blockName }
}

export async function loadAthleteEvents(): Promise<AthleteEvent[]> {
  const r = await supabase.from('athlete_events')
    .select('id,name,kind,sport,priority,event_date,start_time,status,target_time_min,expected_duration_min,distance_km,ascent_m,descent_m,location_name,aid_stations,calendar_event_id,course_source,planned_route_id,exercise_session_id,notes')
    .order('event_date')
  if (r.error) throw new Error(`athlete_events: ${r.error.message}`)
  return (r.data ?? []) as AthleteEvent[]
}

/** "1 h 05" for minutes; "–" when unknown. */
export const hm = (min: number | null | undefined): string => {
  if (min == null) return '–'
  const m = Math.round(min)
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}`
}
export const longDate = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })
export const daysUntil = (d: string, today: string) => Math.round((Date.parse(`${d}T12:00:00`) - Date.parse(`${today}T12:00:00`)) / 86400000)
/** Monday of the week [d] is in. */
export function mondayOf(d: string): string {
  const x = new Date(`${d}T12:00:00`)
  x.setDate(x.getDate() - ((x.getDay() + 6) % 7))
  return x.toLocaleDateString('sv-SE')
}
