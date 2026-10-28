// Analysis hub, phase A (docs/analysis-console/PLAN.md): how an integrity issue, an evidence point or a decision points at
// the data it rests on. A DataRef is plain data so it can be stored (a decision's evidence snapshot, an issue's records)
// and turned into a console link; its string key is stable for de-duplication and storage. Erasable TypeScript, no I/O.

export type DataRef =
  /** One row of a table, by primary key. */
  | { kind: 'record'; table: string; id: string | number }
  /** One local day of a series (metric-series.json id). */
  | { kind: 'day'; series: string; date: string }
  /** An inclusive span of local days of a series. */
  | { kind: 'period'; series: string; start: string; end: string }
  /** A published analysis result (analysis_results kind, key, as_of). */
  | { kind: 'result'; resultKind: string; key: string; asOf: string }
  /** A question of the evidence ledger (catalogue id or reg_<id>). */
  | { kind: 'question'; questionId: string }

const enc = encodeURIComponent

/** Stable string form: kind:part:part, each part URI-encoded. */
export function refKey(ref: DataRef): string {
  switch (ref.kind) {
    case 'record': return `record:${enc(ref.table)}:${enc(String(ref.id))}`
    case 'day': return `day:${enc(ref.series)}:${ref.date}`
    case 'period': return `period:${enc(ref.series)}:${ref.start}:${ref.end}`
    case 'result': return `result:${enc(ref.resultKind)}:${enc(ref.key)}:${ref.asOf}`
    case 'question': return `question:${enc(ref.questionId)}`
  }
}

/** Inverse of refKey; null for anything it did not produce. Record ids come back as strings. */
export function parseRefKey(s: string): DataRef | null {
  const [kind, ...raw] = s.split(':')
  let parts: string[]
  try {
    parts = raw.map(decodeURIComponent)
  } catch {
    return null
  }
  const date = /^\d{4}-\d{2}-\d{2}$/
  if (kind === 'record' && parts.length === 2 && parts[0] && parts[1]) return { kind, table: parts[0], id: parts[1] }
  if (kind === 'day' && parts.length === 2 && parts[0] && date.test(parts[1])) return { kind, series: parts[0], date: parts[1] }
  if (kind === 'period' && parts.length === 3 && parts[0] && date.test(parts[1]) && date.test(parts[2]) && parts[1] <= parts[2]) {
    return { kind, series: parts[0], start: parts[1], end: parts[2] }
  }
  if (kind === 'result' && parts.length === 3 && parts[0] && parts[1] && date.test(parts[2])) return { kind, resultKind: parts[0], key: parts[1], asOf: parts[2] }
  if (kind === 'question' && parts.length === 1 && parts[0]) return { kind, questionId: parts[0] }
  return null
}
