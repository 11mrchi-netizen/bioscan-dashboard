// Analysis hub phase A: the console link for a data reference (analysis-core/dataRef.ts). Every issue, evidence point and
// decision points at its data through one of these, so the convention lives in one place.
//   record   -> Data, that row          #/data?table=meals&id=42
//   day      -> Data, the series' table on that day
//   period   -> Explore, the series over that span
//   result   -> Evidence, the published result
//   question -> Evidence, the question
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
