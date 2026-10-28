// Personal health agent, phase 1 (docs/personal-health-agent): EXPLAIN in the Inspector. The request carries only what
// the Inspector shows for the point (series, period, value, how it was aggregated), so the reply can quote only that plus
// what the agent's tools return; the server's guards check it before it is shown.

export interface ExplainPoint {
  seriesId: string
  label: string
  unit: string
  grain: 'day' | 'week' | 'month'
  start: string
  end: string
  value: string
  aggregation: string
}

export interface ExplainRequest {
  action: 'explain'
  question: string
  context: Record<string, unknown>
  client_turn_id: string
  local_now: string
}

/** An ISO time with the browser's own offset (2026-10-28T21:05:00+08:00), as the function expects. */
export function isoWithOffset(d: Date): string {
  const pad = (n: number) => String(Math.abs(n)).padStart(2, '0')
  const off = -d.getTimezoneOffset()
  const sign = off >= 0 ? '+' : '-'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}` +
    `${sign}${pad(Math.trunc(off / 60))}:${pad(off % 60)}`
}

export function explainRequest(p: ExplainPoint, now: Date, turnId: string): ExplainRequest {
  const when = p.grain === 'day' ? p.start : `${p.start} to ${p.end}`
  return {
    action: 'explain',
    question: `What does ${p.label} on ${when} show, and what does it rest on?`,
    context: {
      card: 'INSPECTOR',
      series: p.seriesId,
      date: p.end,
      shown: { series: p.label, period: when, value: p.value, aggregation: p.aggregation },
    },
    client_turn_id: turnId,
    local_now: isoWithOffset(now),
  }
}

/** The function's error codes as sentences. */
export function explainError(code: string | undefined, message: string | undefined): string {
  if (code === 'assistant_off') return 'The assistant is off. Turn it on in the app: Setup › Profile & goals › Data capture › ASSISTANT.'
  if (code === 'daily_cap') return message ?? 'The assistant has answered enough questions for today.'
  return message ?? 'The assistant could not answer right now. Try again in a minute.'
}
