// ANALYSIS › RISK › MIND, CONNECTION, WORK (risk profile R2): the questionnaires' results over time, and the longer ones
// answered here on a large screen (owner decision: short ones on the phone, longer ones on either). Scored with the same
// definitions and rules as the phone (lib/instruments.ts, instruments.json). Saved rows are append-only; local_date is the
// person's calendar date in this browser, never the UTC date.
import { useEffect, useMemo, useRef, useState } from 'react'
import { supabase } from '../lib/supabase'
import { useChart } from '../lib/useChart'
import { cssColor, PALETTE } from '../lib/palette'
import type { EChartsCoreOption } from '../lib/echarts'
import './PlanBlock.css'
import { DEFS, dueOn, type Instrument, INSTRUMENTS, localToday, optionsOf, score } from '../lib/instruments'

export interface Response { id: number; instrument: string; local_date: string; score: number; band: string | null; flags: string[]; source: string }

// Each questionnaire's hue: the values of design/tokens (--m-phq9 ... --m-work-hours), for canvas charts.
// The metric hue of each instrument (design/tokens --m-*), resolved when read.
const HUE_TOKEN: Record<string, string> = { phq9: 'm-phq9', gad7: 'm-gad7', who5: 'm-who5', ucla3: 'm-loneliness', cbi_work: 'm-burnout', work_hours: 'm-work-hours' }
const hueOf = (id: string): string => cssColor(`var(--${HUE_TOKEN[id] ?? 'm-weight-ema'})`)
const STATE: Record<string, string> = { ok: 'var(--ft-ok, #10B981)', warn: 'var(--ft-warn, #F59E0B)', bad: 'var(--ft-bad, #EF4444)' }
const DOMAINS: [string, string][] = [['mind', 'MIND'], ['connection', 'CONNECTION'], ['work', 'WORK']]

function Chart({ option }: { option: EChartsCoreOption }) {
  const ref = useRef<HTMLDivElement>(null)
  const chart = useChart(ref)
  useEffect(() => { chart.current?.setOption(option, true) }, [chart, option])
  return <div ref={ref} style={{ width: '100%', height: 180 }} />
}

function Form({ inst, onSaved, onCancel }: { inst: Instrument; onSaved: () => void; onCancel: () => void }) {
  const [answers, setAnswers] = useState<(number | null)[]>(() => inst.items.map(() => null))
  const [hours, setHours] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<ReturnType<typeof score> | null>(null)
  const values = inst.number ? (hours.trim() === '' ? null : [Number(hours.replace(',', '.'))]) : answers.every((a) => a !== null) ? (answers as number[]) : null
  let scored: ReturnType<typeof score> | null = null
  try { scored = values ? score(inst, values) : null } catch { scored = null }

  async function save() {
    if (!values || !scored) return
    setSaving(true); setError(null)
    const { error: e } = await supabase.from('instrument_responses').insert({
      instrument: inst.id, defs_version: DEFS.version, local_date: localToday(), answers: values,
      score: scored.score, band: scored.band?.label ?? null, flags: scored.flags.map((f) => f.id), source: 'console',
    })
    setSaving(false)
    if (e) { setError(e.message); return }
    setDone(scored)
    onSaved()
  }

  if (done) return (
    <div className="pg-card">
      <h3 className="pg-card-title mono"><span>{inst.name.toUpperCase()} · {inst.short}</span><span>SAVED</span></h3>
      <p style={{ fontSize: 32, margin: '8px 0' }}>{done.score} <span className="pg-note">{inst.unit}</span></p>
      {done.band && <p style={{ color: STATE[done.band.state] }}>{done.band.label}</p>}
      {done.flags.map((f) => <p key={f.id} className="pg-row" style={{ display: 'block', padding: 12 }}>{f.text}</p>)}
      <p className="pg-note">{inst.source}</p>
      <button className="btn-ghost" onClick={onCancel}>DONE</button>
    </div>
  )
  return (
    <div className="pg-card">
      <h3 className="pg-card-title mono"><span>{inst.name.toUpperCase()} · {inst.short}</span><button className="btn-ghost" onClick={onCancel}>CANCEL</button></h3>
      <p>{inst.prompt}</p>
      {inst.number && (
        <label className="mono" style={{ display: 'block', margin: '8px 0' }}>
          <input className="pb-input" inputMode="decimal" value={hours} onChange={(e) => setHours(e.target.value)} aria-label={inst.prompt} /> {inst.number.unit}
        </label>
      )}
      <ol style={{ paddingLeft: 20 }}>
        {inst.items.map((item, i) => (
          <li key={item.id} style={{ margin: '12px 0' }}>
            <div>{item.text}</div>
            <div role="radiogroup" aria-label={item.text} style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
              {optionsOf(item).map((o) => {
                const on = answers[i] === o.value
                return (
                  <button key={o.label} role="radio" aria-checked={on} className="btn-ghost mono"
                    style={{ fontSize: 11, background: on ? 'var(--ft-platinum, #e6e9ec)' : undefined, color: on ? '#111' : undefined }}
                    onClick={() => setAnswers((a) => a.map((x, j) => (j === i ? o.value : x)))}>{o.label.toUpperCase()}</button>
                )
              })}
            </div>
          </li>
        ))}
      </ol>
      {error && <p className="pb-warn">Could not save: {error}</p>}
      <button className="btn-ghost" disabled={!scored || saving} onClick={save}>{saving ? 'SAVING…' : 'SAVE'}</button>
      <p className="pg-note">{inst.source}</p>
    </div>
  )
}

export function RiskQuestionnaires({ responses, missing, onChanged }: { responses: Response[]; missing: boolean; onChanged: () => void }) {
  const [taking, setTaking] = useState<string | null>(null)
  const today = localToday()
  const byInst = useMemo(() => {
    const m = new Map<string, Response[]>()
    for (const r of [...responses].sort((a, b) => a.local_date.localeCompare(b.local_date))) m.set(r.instrument, [...(m.get(r.instrument) ?? []), r])
    return m
  }, [responses])
  const inst = taking ? INSTRUMENTS.find((i) => i.id === taking) : undefined

  return (
    <>
      {inst && <Form inst={inst} onSaved={onChanged} onCancel={() => setTaking(null)} />}
      {missing && <p className="pg-note">Not on this database yet: instrument_responses.</p>}
      <div className="pg-grid">
        {DOMAINS.map(([domain, title]) => {
          const list = INSTRUMENTS.filter((i) => i.domain === domain)
          const series = list.filter((i) => (byInst.get(i.id) ?? []).length > 0)
          const option: EChartsCoreOption = {
            grid: { left: 40, right: 12, top: 28, bottom: 24 }, tooltip: { trigger: 'axis' }, legend: { top: 0, textStyle: { color: PALETTE.text } },
            xAxis: { type: 'time', axisLabel: { color: PALETTE.textMuted } },
            yAxis: { type: 'value', axisLabel: { color: PALETTE.textMuted }, splitLine: { lineStyle: { color: PALETTE.border } } },
            series: series.map((i) => ({ name: i.short, type: 'line', showSymbol: true, symbolSize: 6, itemStyle: { color: hueOf(i.id) }, lineStyle: { color: hueOf(i.id), width: 2 }, data: (byInst.get(i.id) ?? []).map((r) => [r.local_date, Number(r.score)]) })),
          }
          return (
            <section key={domain} className="pg-card">
              <h3 className="pg-card-title mono"><span>{title}</span><span>QUESTIONNAIRES</span></h3>
              <ul className="pg-rows">
                {list.map((i) => {
                  const rows = byInst.get(i.id) ?? []
                  const last = rows.at(-1)
                  const due = last ? dueOn(i, last.local_date)! : null
                  const isDue = !due || due <= today
                  const band = last ? i.bands.find((b) => Number(last.score) >= b.min && Number(last.score) <= b.max) : undefined
                  return (
                    <li key={i.id} className="pg-row">
                      <span className="pg-row-title mono" style={{ color: hueOf(i.id) }}>{i.name.toUpperCase()} · {i.short}</span>
                      <span className="pg-row-value">
                        {last ? <><b style={{ color: hueOf(i.id) }}>{Number(last.score)}</b> {i.unit} · <span style={{ color: band ? STATE[band.state] : undefined }}>{last.band}</span> · {last.local_date}</> : 'not taken'}
                        {' · '}{isDue ? 'due now' : `next ${due}`}{' '}
                        <button className="btn-ghost" onClick={() => setTaking(i.id)}>{isDue ? 'TAKE' : 'AGAIN'}</button>
                      </span>
                    </li>
                  )
                })}
              </ul>
              {series.length > 0 && <Chart option={option} />}
            </section>
          )
        })}
      </div>
    </>
  )
}
