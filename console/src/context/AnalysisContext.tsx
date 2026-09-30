// DAV-308: Shared AnalysisContext — one source of truth for all analytical surfaces.
import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'
import { defaultContext, type AnalysisContext, type DateRange, type EventRef, type MetricId } from '../types/analysis'

interface AnalysisContextValue {
  ctx: AnalysisContext
  setRange: (range: DateRange) => void
  setComparisonRange: (range: DateRange | null) => void
  setSelectedDate: (date: string | null) => void
  setSelectedEvent: (event: EventRef | null) => void
  toggleMetric: (id: MetricId) => void
  update: (patch: Partial<AnalysisContext>) => void
}

const Ctx = createContext<AnalysisContextValue | null>(null)

export function AnalysisProvider({ children }: { children: ReactNode }) {
  const [ctx, setCtx] = useState<AnalysisContext>(defaultContext)

  const update = useCallback((patch: Partial<AnalysisContext>) =>
    setCtx(prev => ({ ...prev, ...patch })), [])

  const setRange = useCallback((range: DateRange) => update({ primaryRange: range }), [update])
  const setComparisonRange = useCallback((range: DateRange | null) => update({ comparisonRange: range }), [update])
  const setSelectedDate = useCallback((date: string | null) => update({ selectedDate: date }), [update])
  const setSelectedEvent = useCallback((event: EventRef | null) => update({ selectedEvent: event }), [update])

  const toggleMetric = useCallback((id: MetricId) =>
    setCtx(prev => {
      const has = prev.selectedMetrics.includes(id)
      return { ...prev, selectedMetrics: has ? prev.selectedMetrics.filter(m => m !== id) : [...prev.selectedMetrics, id] }
    }), [])

  return (
    <Ctx.Provider value={{ ctx, setRange, setComparisonRange, setSelectedDate, setSelectedEvent, toggleMetric, update }}>
      {children}
    </Ctx.Provider>
  )
}

export function useAnalysis(): AnalysisContextValue {
  const val = useContext(Ctx)
  if (!val) throw new Error('useAnalysis must be used inside AnalysisProvider')
  return val
}
