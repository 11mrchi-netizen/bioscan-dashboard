import { create } from 'zustand'
import { loadRange, saveRange } from './rangeStore'
import {
  defaultContext,
  type AnalysisContext,
  type DateRange,
  type EventRef,
  type InspectionTarget,
  type MetricId,
} from '../types/analysis'

interface AnalysisStore extends AnalysisContext {
  setRange: (range: DateRange) => void
  setComparisonRange: (range: DateRange | null) => void
  setSelectedDate: (date: string | null) => void
  setSelectedEvent: (event: EventRef | null) => void
  toggleMetric: (id: MetricId) => void
  inspect: (target: InspectionTarget) => void
  clearInspection: () => void
  update: (patch: Partial<AnalysisContext>) => void
}

export const useAnalysisStore = create<AnalysisStore>()((set) => ({
  ...defaultContext(),
  // the range chosen last time (a preset stays relative to today)
  primaryRange: loadRange() ?? defaultContext().primaryRange,

  setRange: (range) => { saveRange(range); set({ primaryRange: range }) },
  setComparisonRange: (range) => set({ comparisonRange: range }),
  setSelectedDate: (date) => set({ selectedDate: date }),
  setSelectedEvent: (event) => set({ selectedEvent: event }),
  toggleMetric: (id) =>
    set((s) => ({
      selectedMetrics: s.selectedMetrics.includes(id)
        ? s.selectedMetrics.filter((m) => m !== id)
        : [...s.selectedMetrics, id],
    })),
  inspect: (target) => set({ inspectionTarget: target }),
  clearInspection: () => set({ inspectionTarget: null }),
  update: (patch) => set(patch),
}))

export function useRange() {
  return useAnalysisStore((s) => s.primaryRange)
}

export function useSetRange() {
  return useAnalysisStore((s) => s.setRange)
}

export function useSelectedDate() {
  return useAnalysisStore((s) => s.selectedDate)
}

export function useSetSelectedDate() {
  return useAnalysisStore((s) => s.setSelectedDate)
}

export function useInspection() {
  return useAnalysisStore((s) => s.inspectionTarget)
}

export function useInspect() {
  return useAnalysisStore((s) => s.inspect)
}

export function useClearInspection() {
  return useAnalysisStore((s) => s.clearInspection)
}

export function useTimezone() {
  return useAnalysisStore((s) => s.timezone)
}
