// DAV-312: Timeline workspace — vertical chronological event stream.
// Events: runs, sleep, wellbeing, meals, injuries, illnesses, rest_days.
// All driven by AnalysisContext primaryRange + filters.

import { useEffect, useState, useMemo } from 'react'
import { supabase } from '../lib/supabase'
import { useRange, useInspect } from '../lib/analysisStore'
import { NOTABLE_SESSION_TYPES, dayBounds, localDate, warnOnError } from '../lib/schema'
import type { EventRef } from '../types/analysis'
import './Timeline.css'

interface TimelineEvent {
  id: string
  date: string
  kind: EventRef['kind']
  headline: string
  sub: string
  source: string | null
}

// One fetcher per domain — returns normalized TimelineEvents
async function fetchRuns(start: string, end: string): Promise<TimelineEvent[]> {
  const { from, to } = dayBounds(start, end)
  const { data, error } = await supabase.from('exercise_sessions')
    .select('id, start_time, distance_km, duration_min, type, source')
    .in('type', NOTABLE_SESSION_TYPES)
    .gte('start_time', from).lte('start_time', to).order('start_time', { ascending: false })
  warnOnError('timeline sessions', error)
  return (data ?? []).map((r: Record<string, unknown>) => ({
    id: String(r.id), date: localDate(r.start_time as string), kind: 'session' as const,
    headline: `${r.type ?? 'Session'} · ${Number(r.distance_km ?? 0).toFixed(1)} km`,
    sub: `${Math.round(Number(r.duration_min ?? 0))} min`,
    source: r.source as string | null,
  }))
}

async function fetchSleep(start: string, end: string): Promise<TimelineEvent[]> {
  const { data, error } = await supabase.from('sleep_daily')
    .select('id, date, hours, deep_min, rem_min, source')
    .gte('date', start).lte('date', end).order('date', { ascending: false })
  warnOnError('timeline sleep', error)
  return (data ?? []).map((r: Record<string, unknown>) => ({
    id: String(r.id ?? r.date), date: String(r.date), kind: 'sleep' as const,
    headline: `Sleep · ${Math.round(Number(r.hours ?? 0) * 10) / 10} h`,
    sub: r.deep_min != null && r.rem_min != null ? `deep ${r.deep_min} · REM ${r.rem_min} min` : '',
    source: r.source as string | null,
  }))
}


async function fetchInjuries(start: string, end: string): Promise<TimelineEvent[]> {
  const { data, error } = await supabase.from('injuries')
    .select('id, start_date, part, type, notes, status')
    .gte('start_date', start).lte('start_date', end).order('start_date', { ascending: false })
  warnOnError('timeline injuries', error)
  return (data ?? []).map((r: Record<string, unknown>) => ({
    id: String(r.id), date: String(r.start_date), kind: 'injury' as const,
    headline: `Injury · ${r.part ?? '—'}`,
    sub: String(r.notes ?? r.type ?? r.status ?? ''),
    source: null,
  }))
}

async function fetchIllnesses(start: string, end: string): Promise<TimelineEvent[]> {
  const { data, error } = await supabase.from('illnesses')
    .select('id, start_date, name, severity')
    .gte('start_date', start).lte('start_date', end).order('start_date', { ascending: false })
  warnOnError('timeline illnesses', error)
  return (data ?? []).map((r: Record<string, unknown>) => ({
    id: String(r.id), date: String(r.start_date), kind: 'illness' as const,
    headline: `Illness · ${r.name ?? '—'}`,
    sub: r.severity ? `Severity ${r.severity}` : '',
    source: null,
  }))
}

async function fetchRestDays(start: string, end: string): Promise<TimelineEvent[]> {
  const { data, error } = await supabase.from('rest_days')
    .select('id, date, source')
    .gte('date', start).lte('date', end).order('date', { ascending: false })
  warnOnError('timeline rest days', error)
  return (data ?? []).map((r: Record<string, unknown>) => ({
    id: String(r.id ?? r.date), date: String(r.date), kind: 'rest_day' as const,
    headline: 'Rest Day',
    sub: String(r.source ?? ''),
    source: null,
  }))
}

const KIND_BADGE: Record<TimelineEvent['kind'], { label: string; color: string }> = {
  session:  { label: 'ACTIVITY', color: 'var(--color-training)' },
  sleep:    { label: 'SLEEP',    color: 'var(--color-info)' },
  meal:     { label: 'MEAL',     color: 'var(--color-fuel)' },
  lab_draw: { label: 'LAB',      color: 'var(--color-labs)' },
  illness:  { label: 'ILLNESS',  color: 'var(--color-critical)' },
  injury:   { label: 'INJURY',   color: 'var(--color-warning)' },
  rest_day: { label: 'REST',     color: 'var(--color-text-muted)' },
}

export function Timeline() {
  const range = useRange()
  const inspect = useInspect()
  const [events, setEvents] = useState<TimelineEvent[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    const { start, end } = range
    Promise.all([
      fetchRuns(start, end),
      fetchSleep(start, end),
      fetchInjuries(start, end),
      fetchIllnesses(start, end),
      fetchRestDays(start, end),
    ]).then(batches => {
      const all = batches.flat().sort((a, b) => b.date.localeCompare(a.date))
      setEvents(all)
      setLoading(false)
    })
  }, [range])

  // Group by date for sticky headers
  const grouped = useMemo(() => {
    const map = new Map<string, TimelineEvent[]>()
    for (const e of events) {
      const arr = map.get(e.date) ?? []
      arr.push(e)
      map.set(e.date, arr)
    }
    return [...map.entries()].sort(([a], [b]) => b.localeCompare(a))
  }, [events])

  if (loading) return <div className="state-loading mono">LOADING TIMELINE…</div>
  if (events.length === 0) return <div className="state-empty mono">NO EVENTS IN RANGE</div>

  return (
    <div className="timeline">
      <h2 className="timeline-title">Timeline</h2>
      <div className="timeline-stream">
        {grouped.map(([date, dayEvents]) => (
          <div key={date} className="timeline-day">
            <div className="timeline-day-header mono">{date}</div>
            {dayEvents.map(ev => {
              const badge = KIND_BADGE[ev.kind]
              return (
                <button
                  key={ev.id}
                  className="timeline-event"
                  onClick={() => inspect({
                    kind: 'event',
                    eventRef: { kind: ev.kind, id: ev.id },
                  })}
                >
                  <span
                    className="timeline-badge mono"
                    style={{ color: badge.color, borderColor: badge.color }}
                  >
                    {badge.label}
                  </span>
                  <div className="timeline-event-body">
                    <div className="timeline-headline">{ev.headline}</div>
                    {ev.sub && <div className="timeline-sub mono">{ev.sub}</div>}
                  </div>
                  {ev.source && (
                    <span className="timeline-source mono">{ev.source}</span>
                  )}
                </button>
              )
            })}
          </div>
        ))}
      </div>
    </div>
  )
}

export default Timeline
