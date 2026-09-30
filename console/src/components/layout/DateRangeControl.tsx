// Header date-range picker — broadcasts to AnalysisContext
import { useAnalysis } from '../../context/AnalysisContext'
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
  const { ctx, setRange } = useAnalysis()

  return (
    <div className="date-range-control">
      {PRESETS.map(p => {
        const start = daysAgo(p.days)
        const active = ctx.primaryRange.start === start && ctx.primaryRange.end === today()
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
        value={ctx.primaryRange.start}
        max={ctx.primaryRange.end}
        onChange={e => setRange({ ...ctx.primaryRange, start: e.target.value })}
        aria-label="Range start"
      />
      <span className="mono range-sep">→</span>
      <input
        type="date"
        className="range-input mono"
        value={ctx.primaryRange.end}
        min={ctx.primaryRange.start}
        onChange={e => setRange({ ...ctx.primaryRange, end: e.target.value })}
        aria-label="Range end"
      />
    </div>
  )
}
