// Inspector — provenance and lineage panel (Phase 3 / Issue J; series points since the analysis hub's phase B).
// A series point shows its period, value and how it was aggregated (method, days observed, rows, range, sources, the
// series' time rule); a single day also lists the records behind it, each linked to Data. Events show their raw record.
// Opened by any workspace surface via ctx.inspect(target).

import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { supabase } from '../../lib/supabase'
import { useInspection, useTimezone } from '../../lib/analysisStore'
import { seriesById } from '../../lib/schema'
import { loadDayRecords, type DayRecords } from '../../lib/seriesQuery'
import { isAbort } from '../../lib/rangeCache'
import { hrefFor } from '../../lib/links'
import type { EventRef, SeriesPointInspection } from '../../types/analysis'
import './Inspector.css'

const EVENT_TABLE: Record<EventRef['kind'], string> = {
  session:   'exercise_sessions',
  meal:      'meals',
  sleep:     'sleep_daily',
  lab_draw:  'lab_draws',
  illness:   'illnesses',
  injury:    'injuries',
  rest_day:  'rest_days',
}

interface Props { onClose: () => void }

export function Inspector({ onClose }: Props) {
  const target = useInspection()
  const timezone = useTimezone()
  const [eventRecord, setEventRecord] = useState<Record<string, unknown> | null>(null)
  const [loadingEvent, setLoadingEvent] = useState(false)

  // Load raw record when target is an event
  useEffect(() => {
    if (!target || target.kind !== 'event') { setEventRecord(null); return }
    setLoadingEvent(true)
    const table = EVENT_TABLE[target.eventRef.kind]
    supabase.from(table).select('*').eq('id', target.eventRef.id).single()
      .then(({ data }) => { setEventRecord(data as Record<string, unknown> | null); setLoadingEvent(false) })
  }, [target])

  if (!target) return null

  return (
    <div className="inspector">
      <div className="inspector-header">
        <span className="mono inspector-eyebrow">INSPECTOR</span>
        <button className="inspector-close mono" onClick={onClose} aria-label="Close inspector">✕</button>
      </div>

      {target.kind === 'series_point' && <SeriesPointView key={`${target.seriesId}|${target.start}|${target.end}`} target={target} timezone={timezone} />}

      {target.kind === 'event' && (
        loadingEvent
          ? <div className="state-loading mono" style={{ padding: '24px' }}>LOADING…</div>
          : <EventView ref={target.eventRef} record={eventRecord} />
      )}
    </div>
  )
}

// --- Series point view ---

const METHOD_TEXT = {
  mean: 'average of the days',
  sum: 'total of the days',
  last: 'last day with a value',
} as const

const DAY_METHOD_TEXT = {
  mean: 'average of the day\'s rows',
  sum: 'total of the day\'s rows',
  last: 'last row of the day',
} as const

const TIME_TEXT = {
  local_date: 'calendar date column (already the local day)',
  local_wall: 'local wall time labelled UTC (first ten characters are the local day)',
  utc: 'true UTC instant, placed on the local day with the record\'s offset (default +8 h)',
} as const

function fmt(v: number | null): string {
  if (v === null) return '—'
  const a = Math.abs(v)
  return a >= 1000 ? Math.round(v).toLocaleString() : a >= 100 ? v.toFixed(0) : a >= 10 ? v.toFixed(1) : v.toFixed(2)
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="inspector-section">
      <div className="inspector-label mono">{label}</div>
      {children}
    </div>
  )
}

function SeriesPointView({ target, timezone }: { target: SeriesPointInspection; timezone: string }) {
  const navigate = useNavigate()
  const series = seriesById(target.seriesId)
  const isDay = target.grain === 'day'
  const [records, setRecords] = useState<DayRecords | null>(null)
  const [recordsError, setRecordsError] = useState<string | null>(null)

  useEffect(() => {
    if (!series || !isDay) return
    const ac = new AbortController()
    loadDayRecords(series, target.start, ac.signal)
      .then(setRecords)
      .catch((e: unknown) => { if (!isAbort(e)) setRecordsError(e instanceof Error ? e.message : String(e)) })
    return () => ac.abort()
  }, [series, isDay, target.start])

  if (!series) return <div className="state-empty mono" style={{ padding: '24px' }}>UNKNOWN SERIES {target.seriesId}</div>

  const exceptions = series.time.exceptions ?? []
  return (
    <div className="inspector-body">
      <Section label="SERIES">
        <div className="inspector-value">{series.label}</div>
        <div className="inspector-value mono inspector-muted">{series.table}.{series.column}{series.filters ? ` · ${Object.entries(series.filters).map(([k, v]) => `${k} in ${v.join(', ')}`).join(' · ')}` : ''}</div>
      </Section>

      <Section label={isDay ? 'DAY' : target.grain === 'week' ? 'WEEK' : 'MONTH'}>
        <div className="inspector-value mono">
          {isDay ? target.start : `${target.start} → ${target.end}`} <span className="inspector-muted">({timezone})</span>
        </div>
        {target.clipped && <div className="inspector-value mono inspector-muted">partial: the selected range cuts this {target.grain} short</div>}
      </Section>

      <Section label="VALUE">
        <div className="inspector-primary mono">
          {fmt(target.value)}{series.unit && <span className="inspector-unit"> {series.unit}</span>}
        </div>
        <div className="inspector-value mono inspector-muted">
          {target.value === null
            ? 'no observation: shown as a gap, never as 0'
            : isDay
              ? (target.samples > 1 ? `${DAY_METHOD_TEXT[series.day]} · ${target.samples} rows` : 'one row')
              : `${METHOD_TEXT[target.method]} · ${target.daysObserved} of ${target.days} days observed · ${target.samples} rows`}
        </div>
      </Section>

      {!isDay && target.value !== null && (
        <Section label="DAY RANGE">
          <div className="inspector-value mono">{fmt(target.min)} – {fmt(target.max)} {series.unit}</div>
        </Section>
      )}

      <Section label="SOURCES">
        <div className="inspector-value mono">{target.sources.length ? target.sources.join(', ') : series.hasSource ? 'none recorded' : 'table has no source column'}</div>
      </Section>

      <Section label="TIME RULE">
        <div className="inspector-value mono">{series.time.column}: {TIME_TEXT[series.time.kind]}</div>
        {exceptions.map(x => <div key={x.before} className="inspector-value mono inspector-muted">before {x.before}: {TIME_TEXT[x.kind]}. {x.note}</div>)}
      </Section>

      {series.plausible && (
        <Section label="PLAUSIBLE RANGE">
          <div className="inspector-value mono">{series.plausible[0]} – {series.plausible[1]} {series.unit}</div>
        </Section>
      )}

      {isDay && (
        <Section label="RECORDS">
          {recordsError && <div className="inspector-value mono" style={{ color: 'var(--color-critical)' }}>{recordsError}</div>}
          {!records && !recordsError && <div className="inspector-value mono inspector-muted">LOADING…</div>}
          {records && records.observations.length === 0 && <div className="inspector-value mono inspector-muted">no rows on this day</div>}
          {records?.observations.map(o => {
            const href = hrefFor({ kind: 'record', table: series.table, id: o.id })
            return (
              <div key={String(o.id)} className="inspector-record mono">
                <span className="inspector-muted">{o.localTime ?? o.date}</span>
                <span>{fmt(o.value)}</span>
                <span className="inspector-muted">{o.source ?? ''}{o.assumedOffset ? ' · offset assumed' : ''}</span>
                {href && <a className="inspector-link" href={href}>#{String(o.id)}</a>}
              </div>
            )
          })}
        </Section>
      )}

      {isDay
        ? <button className="inspector-drill mono" onClick={() => navigate(`/data?table=${series.table}&date=${target.start}`)}>VIEW THE DAY IN DATA →</button>
        : <button className="inspector-drill mono" onClick={() => navigate(`/explore?${new URLSearchParams({ series: target.seriesId, start: target.start, end: target.end })}`)}>OPEN THE DAYS OF THIS {target.grain.toUpperCase()} →</button>}
    </div>
  )
}

// --- Event view ---

function EventView({ ref: eventRef, record }: { ref: EventRef; record: Record<string, unknown> | null }) {
  if (!record) return <div className="state-empty mono" style={{ padding: '24px' }}>NO RECORD FOUND</div>

  const entries = Object.entries(record).filter(([, v]) => v !== null && v !== undefined)

  return (
    <div className="inspector-body">
      <div className="inspector-section">
        <div className="inspector-label mono">TYPE</div>
        <div className="inspector-value mono">{eventRef.kind.toUpperCase()}</div>
      </div>

      {entries.map(([k, v]) => (
        <div key={k} className="inspector-section">
          <div className="inspector-label mono">{k.toUpperCase()}</div>
          <div className="inspector-value mono">{String(v)}</div>
        </div>
      ))}
    </div>
  )
}
