// One planned session as a row (PLAN pages): date and time, what it is, its block and week, and what became of it.
import { hm, longDate, type PlannedSession } from '../lib/planReads'
import { movedFrom } from '../lib/planEdit'

const STATUS_CLASS: Record<string, string> = { done: 'pg-ok', confirmed: 'pg-ok', completed: 'pg-ok', skipped: 'pg-warn', missed: 'pg-bad' }

export function PlanSessionRows({ sessions, blockName, blockShift, today }: { sessions: PlannedSession[]; blockName: Map<number, string>; blockShift?: Map<number, number>; today: string }) {
  return (
    <ul className="pg-rows">{sessions.map((s) => (
      <li key={s.id} className="pg-row">
        <span className="pg-row-title">
          <span className="mono">{longDate(s.scheduled_date).toUpperCase()}{s.start_time ? ` · ${s.start_time.slice(0, 5)}` : ''}</span>{'  '}{s.title ?? s.work_kind ?? 'Session'}
          {s.scheduled_date === today && <span className="pg-tag mono">TODAY</span>}
        </span>
        <span className={`pg-row-value mono ${STATUS_CLASS[s.status] ?? ''}`}>{s.status.toUpperCase()}{s.status === 'planned' && s.exercise_session_id == null && s.scheduled_date >= today && <> · <a href={`#/plan/sessions/planned/${s.id}`}>EDIT</a></>}{s.status === 'superseded' && s.scheduled_date >= today && <> · <a href={`#/plan/sessions/planned/${s.id}`}>OPEN</a></>}{s.status === 'planned' && (s.duration_min ?? 0) >= 90 && s.scheduled_date >= today && <> · <a href={`#/plan/food?day=${s.scheduled_date}`}>FUEL</a></>}</span>
        <span className="pg-row-sub">{s.exercise_session_id != null && <><a href={`#/plan/sessions/${s.exercise_session_id}`}>RECORDED SESSION{s.match_status ? ` (${s.match_status})` : ''} →</a>{' · '}</>}{[
          s.domain?.replaceAll('_', ' '), s.duration_min ? hm(s.duration_min) : null,
          s.block_id != null ? `${blockName.get(s.block_id) ?? 'block'}${s.week_index ? ` · week ${s.week_index}` : ''}` : null,
          (() => { const f = movedFrom(s.original_date, s.scheduled_date, s.block_id != null ? blockShift?.get(s.block_id) ?? 0 : 0); return f ? `moved from ${longDate(f)}` : null })(),
          s.note ? `note: ${s.note}` : null,
          s.gcal_pushed_at ? 'on the calendar' : null, s.zepp_pushed_at ? 'on the watch' : null,
        ].filter(Boolean).join(' · ')}</span>
      </li>
    ))}</ul>
  )
}
