// Inspector — provenance and lineage panel (Phase 3 / Issue J).
// Shows: metric ID, label, value, unit, aggregation, timezone, confidence,
// provenance chain, data-quality signals, deep-link to Data Explorer.
// Opened by any workspace surface via ctx.inspect(target).

import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { supabase } from '../../lib/supabase'
import { useInspection, useTimezone } from '../../lib/analysisStore'
import type { EventRef, MetricPointInspection } from '../../types/analysis'
import './Inspector.css'

const EVENT_TABLE: Record<EventRef['kind'], string> = {
  session:   'runs',
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
  const navigate = useNavigate()
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

      {target.kind === 'metric_point' && (
        <MetricPointView target={target} timezone={timezone} navigate={navigate} />
      )}

      {target.kind === 'event' && (
        loadingEvent
          ? <div className="state-loading mono" style={{ padding: '24px' }}>LOADING…</div>
          : <EventView ref={target.eventRef} record={eventRecord} />
      )}
    </div>
  )
}

// --- Metric point view ---

type NavFn = (path: string) => void

function MetricPointView({ target, timezone, navigate }: {
  target: MetricPointInspection
  timezone: string
  navigate: NavFn
}) {
  const [table, column] = target.metricId.split('.')

  const drillToExplorer = () =>
    navigate(`/data-explorer?table=${table}&date=${target.date}`)

  return (
    <div className="inspector-body">
      <div className="inspector-section">
        <div className="inspector-label mono">METRIC</div>
        <div className="inspector-value mono">{target.metricId}</div>
      </div>

      <div className="inspector-section">
        <div className="inspector-label mono">DATE</div>
        <div className="inspector-value mono">{target.date} <span className="inspector-muted">({timezone})</span></div>
      </div>

      <div className="inspector-section">
        <div className="inspector-label mono">VALUE</div>
        <div className="inspector-primary mono">
          {target.value !== null ? target.value : '—'}
          {target.unit && <span className="inspector-unit"> {target.unit}</span>}
        </div>
      </div>

      <div className="inspector-section">
        <div className="inspector-label mono">AGGREGATION</div>
        <div className="inspector-value mono">{target.aggregation || 'daily_avg'}</div>
      </div>

      <div className="inspector-section">
        <div className="inspector-label mono">COLUMN</div>
        <div className="inspector-value mono">{table} · {column}</div>
      </div>

      <ProvenanceSection
        source={target.source}
        confidence={target.confidence}
        provenance={target.provenance}
      />

      <button className="inspector-drill mono" onClick={drillToExplorer}>
        VIEW RAW RECORDS →
      </button>
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

// --- Shared provenance section ---

function ProvenanceSection({ source, confidence, provenance }: {
  source: string | null
  confidence: string | null
  provenance: string | null
}) {
  const confColor = confidence === 'high' ? 'var(--color-emerald)'
    : confidence === 'medium' ? 'var(--color-warning)'
    : confidence === 'low' ? 'var(--color-critical)'
    : 'var(--color-text-muted)'

  return (
    <>
      {source && (
        <div className="inspector-section">
          <div className="inspector-label mono">SOURCE</div>
          <div className="inspector-value mono">{source}</div>
        </div>
      )}
      {confidence && (
        <div className="inspector-section">
          <div className="inspector-label mono">CONFIDENCE</div>
          <div className="inspector-value mono" style={{ color: confColor }}>
            {confidence.toUpperCase()}
          </div>
        </div>
      )}
      {provenance && (
        <div className="inspector-section">
          <div className="inspector-label mono">PROVENANCE</div>
          <div className="inspector-value mono inspector-provenance">{provenance}</div>
        </div>
      )}
      {!source && !confidence && !provenance && (
        <div className="inspector-section">
          <div className="inspector-label mono">PROVENANCE</div>
          <div className="inspector-value mono inspector-muted">UNKNOWN</div>
        </div>
      )}
    </>
  )
}
