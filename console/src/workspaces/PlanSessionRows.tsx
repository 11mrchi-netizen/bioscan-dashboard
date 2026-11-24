// One planned session as a row (PLAN pages): date and time, what it is, its block and week, and what became of it.
import { hm, longDate, type PlannedSession } from '../lib/planReads'

const STATUS_CLASS: Record<string, string> = { done: 'pg-ok', confirmed: 'pg-ok', completed: 'pg-ok', skipped: 'pg-warn', missed: 'pg-bad' }

export function PlanSessionRows({ sessions, blockName, today }: { sessions: PlannedSession[]; blockName: Map<number, string>; today: string }) {
  return (
    <ul className="pg-rows">{sessions.map((s) => (
      <li key={s.id} className="pg-row">
        <span className="pg-row-title">
          <span className="mono">{longDate(s.scheduled_date).toUpperCase()}{s.start_time ? ` · ${s.start_time.slice(0, 5)}` : ''}</span>{'  '}{s.title ?? s.work_kind ?? 'Session'}
          {s.scheduled_date === today && <span className="pg-tag mono">TODAY</span>}
        </span>
        <span className={`pg-row-value mono ${STATUS_CLASS[s.status] ?? ''}`}>{s.status.toUpperCase()}</span>
        <span className="pg-row-sub">{s.exercise_session_id != null && <><a href={`#/plan/sessions/${s.exercise_session_id}`}>RECORDED SESSION{s.match_status ? ` (${s.match_status})` : ''} →</a>{' · '}</>}{[
          s.domain?.replaceAll('_', ' '), s.duration_min ? hm(s.duration_min) : null,
          s.block_id != null ? `${blockName.get(s.block_id) ?? 'block'}${s.week_index ? ` · week ${s.week_index}` : ''}` : null,
          s.original_date && s.original_date !== s.scheduled_date ? `moved from ${longDate(s.original_date)}` : null,
          s.gcal_pushed_at ? 'on the calendar' : null, s.zepp_pushed_at ? 'on the watch' : null,
        ].filter(Boolean).join(' · ')}</span>
      </li>
    ))}</ul>
  )
}
