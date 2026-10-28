// Analysis hub phase A: the console link for a data reference (analysis-core/dataRef.ts). Every issue, evidence point and
// decision points at its data through one of these, so the convention lives in one place.
//   record   -> Data, that row          #/data?table=meals&id=42
//   day      -> Data, the series' table on that day
//   period   -> Explore, the series over that span
//   result   -> Evidence, the published result
//   question -> Evidence, the question
// and the way back: itemIdFromParams reads an Evidence link into the evidence item it names (evidenceItems.ts ids), and
// integrityHref opens the Integrity workspace on one series.
import type { DataRef } from '../../../supabase/functions/_shared/analysis-core/dataRef.ts'
import { seriesById } from './schema.ts'

function href(path: string, params: Record<string, string>): string {
  return `#/${path}?${new URLSearchParams(params).toString()}`
}

/** The hash link for a reference; null when it names a series the contract file does not know. */
export function hrefFor(ref: DataRef): string | null {
  switch (ref.kind) {
    case 'record':
      return href('data', { table: ref.table, id: String(ref.id) })
    case 'day': {
      const s = seriesById(ref.series)
      return s ? href('data', { table: s.table, date: ref.date }) : null
    }
    case 'period':
      return seriesById(ref.series) ? href('explore', { series: ref.series, start: ref.start, end: ref.end }) : null
    case 'result':
      return href('evidence', { result: ref.resultKind, key: ref.key, asOf: ref.asOf })
    case 'question':
      return href('evidence', { question: ref.questionId })
  }
}

/** The evidence item (analysis-core/evidenceItems.ts id) an Evidence link names, or null. */
export function itemIdFromParams(p: URLSearchParams): string | null {
  const q = p.get('question')
  if (q) return q.startsWith('reg_') ? `reg:${q.slice(4)}` : `q:${q}`
  const kind = p.get('result')
  const key = p.get('key')
  if (!kind || !key) return null
  if (kind === 'relationship') return `q:${key}`
  if (kind === 'registered_question') return `reg:${key.replace(/^reg_/, '')}`
  if (kind === 'experiment') return `exp:${key}`
  if (kind === 'intervention_its') return `its:${key}`
  return null
}

/** The Integrity workspace focused on one series. */
export function integrityHref(seriesId: string): string {
  return href('integrity', { series: seriesId })
}
