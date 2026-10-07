// Header date-range picker — broadcasts to AnalysisContext
import { useRange, useSetRange } from '../../lib/analysisStore'
import './DateRangeControl.css'

const PRESETS = [
  { label: '7D',  days: 7 },
  { label: '28D', days: 28 },
  { label: '90D', days: 90 },
  { label: '1Y',  days: 365 },
]

function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}

const today = () => new Date().toISOString().slice(0, 10)

export function DateRangeControl() {
  const range = useRange()
  const setRange = useSetRange()

  return (
    <div className="date-range-control">
      {PRESETS.map(p => {
        const start = daysAgo(p.days)
        const active = range.start === start && range.end === today()
        return (
          <button
            key={p.label}
            className={`range-pill mono ${active ? 'active' : ''}`}
            onClick={() => setRange({ start, end: today() })}
          >
            {p.label}
          </button>
        )
      })}
      <input
        type="date"
        className="range-input mono"
        value={range.start}
        max={range.end}
        onChange={e => setRange({ ...range, start: e.target.value })}
        aria-label="Range start"
      />
      <span className="mono range-sep">→</span>
      <input
        type="date"
        className="range-input mono"
        value={range.end}
        min={range.start}
        onChange={e => setRange({ ...range, end: e.target.value })}
        aria-label="Range end"
      />
    </div>
  )
}
