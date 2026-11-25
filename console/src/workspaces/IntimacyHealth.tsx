// Me › INTIMACY › HEALTH (STI tests, as the phone keeps them, and the morning signs) and › EFFECTS (the days around an
// activity). Nothing here says when to test or what a result means: that is for a clinician.
import { useMemo, useState } from 'react'
import type { EChartsCoreOption } from '../lib/echarts'
import { localToday } from '../lib/dates'
import { metricHref } from '../lib/metrics'
import { useLoadEffect } from '../lib/useLoadEffect'
import { addDays, eventStudy, inRange, MIN_EVENT_N, partnersBefore, pearson, STI_LOOKBACK_DAYS, STI_VOCAB, stiStatus, type StiTest } from '../lib/intimacy'
import { addStiTests, deleteStiTest, loadOutcomes, type Outcome, setStiTreated } from '../lib/intimacyData'
import { AXES, Card, Chart, f1, fullDate, Rows, signed, Tiles, timeAxis, tok, useName, valueAxis, ago } from './IntimacyKit'
import type { ViewProps } from './Intimacy'
import { Board } from '../components/layout/Board'

const RESULT_TONE: Record<string, 'ok' | 'warn' | 'bad'> = { negative: 'ok', positive: 'bad', inconclusive: 'warn', pending: 'warn' }

export function IntimacyHealth({ data, onChanged }: ViewProps) {
  const name = useName()
  const today = localToday()
  const tests = data.tests
  const status = useMemo(() => (tests ? stiStatus(tests, data.encounters) : null), [tests, data.encounters])
  const [adding, setAdding] = useState(false)
  const [confirm, setConfirm] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const run = async (f: () => Promise<void>) => { setError(null); try { await f(); onChanged() } catch (e) { setError(e instanceof Error ? e.message : String(e)) } }
  // the panel grid: infection by test date
  const dates = [...new Set((tests ?? []).map((t) => t.test_date))].sort().reverse()
  const infections = [...new Set((tests ?? []).map((t) => t.infection))].sort((a, b) => {
    const r = (i: string) => { const k = (STI_VOCAB.infections as readonly string[]).indexOf(i); return k < 0 ? 99 : k }
    return r(a) - r(b) || a.localeCompare(b)
  })
  const cell = (inf: string, d: string) => (tests ?? []).filter((t) => t.infection === inf && t.test_date === d)
  const since = status?.lastTest ? 'since then' : 'on record'

  return (
    <div className="it-views-body">
      {error && <p className="it-error">Couldn't save ({error}).</p>}
      <Card title="STI TESTS" wide aside={<button className="ft-chip" onClick={() => setAdding(!adding)}>{adding ? 'CLOSE' : '+ LOG A TEST'}</button>} info="Your own record of tests and results. Nothing here says when to test or what a result means: that is for a clinician.">
        {adding && <StiForm onSaved={() => { setAdding(false); onChanged() }} />}
        {data.testsError ? <p className="it-error">Couldn't load the tests ({data.testsError}).</p> : status && (
          <>
            <Tiles tiles={[
              { label: 'Last test', value: status.lastTest ? fullDate(status.lastTest) : 'none yet', sub: status.lastTest ? ago(status.lastTest, today) : null },
              { label: `Partners ${since}`, value: String(status.partnersSince) },
              { label: `Encounters ${since}`, value: String(status.encountersSince) },
              { label: 'Without a barrier', value: String(status.withoutBarrierSince), sub: status.protectionUnknownSince ? `${status.protectionUnknownSince} not recorded` : since },
            ]} />
            {status.open.map((t) => {
              const before = partnersBefore(t.test_date, data.encounters)
              return (
                <div key={t.id} className="it-open">
                  <p className="me-note"><b className="it-bad">{t.infection} · positive {fullDate(t.test_date)}, not marked treated</b></p>
                  {before.length > 0 && <p className="it-info">Partners in the {STI_LOOKBACK_DAYS} days before: {before.map(name).join(', ')}</p>}
                  <button className="ft-chip" onClick={() => void run(() => setStiTreated(t.id, today))}>MARK TREATED TODAY</button>
                </div>
              )
            })}
            {status.latest.length > 0 && (
              <>
                <div className="me-label">LATEST RESULT PER INFECTION</div>
                <Rows rows={status.latest.map((t) => ({ label: t.infection, value: `${t.result}${t.treated_on ? ' · treated' : ''}`, tone: RESULT_TONE[t.result], sub: [fullDate(t.test_date), t.site, t.lab_name].filter(Boolean).join(' · ') }))} />
              </>
            )}
            {dates.length > 0 && (
              <>
                <div className="me-label">EVERY TEST</div>
                <div className="it-table-wrap"><table className="it-heat it-sti">
                  <thead><tr><th />{dates.map((d) => <th key={d} className="mono">{fullDate(d)}</th>)}</tr></thead>
                  <tbody>{infections.map((inf) => <tr key={inf}><th>{inf}</th>{dates.map((d) => {
                    const cs = cell(inf, d)
                    return <td key={d}>{cs.map((t) => (
                      <span key={t.id} className="it-sti-cell">
                        <span style={{ color: `var(--${RESULT_TONE[t.result] ?? 'ft-fg'})` }}>{t.result}{t.treated_on ? ' ✓' : ''}</span>
                        {confirm === t.id
                          ? <><button className="it-link it-bad mono" onClick={() => void run(() => deleteStiTest(t.id))}>DELETE</button><button className="it-link mono" onClick={() => setConfirm(null)}>KEEP</button></>
                          : <button className="it-link it-dim mono" aria-label={`Delete ${t.infection} on ${t.test_date}`} onClick={() => setConfirm(t.id)}>×</button>}
                      </span>
                    ))}</td>
                  })}</tr>)}</tbody>
                </table></div>
              </>
            )}
            {tests?.length === 0 && <p className="it-empty">No test logged yet.</p>}
          </>
        )}
      </Card>
      <MorningSigns data={data} />
    </div>
  )
}

function StiForm({ onSaved }: { onSaved: () => void }) {
  const [date, setDate] = useState(localToday())
  const [picked, setPicked] = useState<string[]>([])
  const [result, setResult] = useState('negative')
  const [site, setSite] = useState<string | null>(null)
  const [lab, setLab] = useState('')
  const [notes, setNotes] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function save() {
    setSaving(true); setError(null)
    try {
      await addStiTests(picked.map((infection) => ({ test_date: date, infection, result, site, lab_name: lab.trim() || null, notes: notes.trim() || null })))
      onSaved()
    } catch (e) { setError(e instanceof Error ? e.message : String(e)) } finally { setSaving(false) }
  }
  return (
    <div className="it-edit">
      <p className="it-info">One save logs a panel: the infections tested with one result. A mixed panel is two saves, as on the phone.</p>
      <label className="it-field"><span className="me-label">TEST DATE</span><input type="date" value={date} max={localToday()} onChange={(e) => setDate(e.target.value)} /></label>
      <div className="me-label">TESTED FOR</div>
      <div className="it-chips">{STI_VOCAB.infections.map((i) => <button key={i} className="ft-chip" aria-current={picked.includes(i)} onClick={() => setPicked(picked.includes(i) ? picked.filter((x) => x !== i) : [...picked, i])}>{i}</button>)}</div>
      <div className="me-label">RESULT</div>
      <div className="it-chips">{STI_VOCAB.results.map((r) => <button key={r} className="ft-chip" aria-current={result === r} onClick={() => setResult(r)}>{r.toUpperCase()}</button>)}</div>
      <div className="me-label">SAMPLE (OPTIONAL)</div>
      <div className="it-chips">{STI_VOCAB.sites.map((s) => <button key={s} className="ft-chip" aria-current={site === s} onClick={() => setSite(site === s ? null : s)}>{s.toUpperCase()}</button>)}</div>
      <label className="it-field"><span className="me-label">LAB OR CLINIC (OPTIONAL)</span><input value={lab} onChange={(e) => setLab(e.target.value)} /></label>
      <label className="it-field"><span className="me-label">NOTES (OPTIONAL)</span><textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} /></label>
      {error && <p className="it-error">Couldn't save ({error}).</p>}
      <div className="it-chips"><button className="ft-chip it-primary" disabled={saving || !picked.length || !/^\d{4}-\d{2}-\d{2}$/.test(date)} onClick={() => void save()}>{saving ? 'SAVING…' : `SAVE ${picked.length || ''}`.trim()}</button></div>
    </div>
  )
}

// ---- the morning signs, and the phone's correlations ----

function useOutcomes(from: string) {
  const [out, setOut] = useState<{ outcomes: Outcome[]; missing: string[] } | null>(null)
  const st = useLoadEffect(async (cur) => { const o = await loadOutcomes(from); if (cur()) setOut(o) }, [from])
  return { out, st }
}

function MorningSigns({ data }: { data: ViewProps['data'] }) {
  const { out } = useOutcomes(addDays(localToday(), -730))
  const get = (id: string) => out?.outcomes.find((o) => o.id === id)?.days ?? new Map<string, number>()
  const erection = get('erection'), arousal = get('arousal')
  const pairs = (x: Map<string, number>, y: Map<string, number>) => [...x].flatMap(([d, v]) => (y.has(d) ? [[v, y.get(d)!] as [number, number]] : []))
  // the phone's fifth pair: the strongest orgasm of a day against the days since the previous active day
  const active = [...new Set([...data.encounters.filter((e) => e.sexual).map((e) => e.date), ...data.solo.filter((s) => s.instances.length).map((s) => s.date)])].sort()
  const strongest = new Map<string, number>()
  for (const e of data.encounters) if (e.sexual && e.orgasm_intensity != null) strongest.set(e.date, Math.max(strongest.get(e.date) ?? 0, e.orgasm_intensity))
  for (const s of data.solo) for (const i of s.instances) if (i.orgasmIntensity != null) strongest.set(s.date, Math.max(strongest.get(s.date) ?? 0, i.orgasmIntensity))
  const gapPairs = active.slice(1).flatMap((d, i) => (strongest.has(d) ? [[strongest.get(d)!, Math.round((Date.parse(d) - Date.parse(active[i])) / 864e5)] as [number, number]] : []))
  const rows = [
    { label: 'Morning erection × sleep duration', c: pearson(pairs(erection, get('sleep_hours'))), note: 'a pairing with research behind it' },
    { label: 'Morning erection × deep sleep', c: pearson(pairs(erection, get('sleep_deep'))), note: 'a pairing with research behind it' },
    { label: 'Arousal × HRV', c: pearson(pairs(arousal, get('hrv'))), note: 'observed in your data only' },
    { label: 'Arousal × resting heart rate', c: pearson(pairs(arousal, get('rhr'))), note: 'observed in your data only' },
    { label: 'Orgasm intensity × days since the last activity', c: pearson(gapPairs), note: 'observed in your data only' },
  ]
  const last = (m: Map<string, number>) => [...m].sort((a, b) => (a[0] < b[0] ? 1 : -1))[0]
  const avg30 = (m: Map<string, number>) => { const from = addDays(localToday(), -29); const v = [...m].filter(([d]) => d >= from).map((x) => x[1]); return v.length ? v.reduce((s, x) => s + x, 0) / v.length : null }
  const option = useMemo<EChartsCoreOption>(() => ({
    ...AXES, xAxis: timeAxis(), yAxis: { ...valueAxis('/10'), max: 10 },
    series: [
      { name: 'Morning erection', type: 'line', showSymbol: false, data: [...erection].sort().map(([d, v]) => [d, v]), itemStyle: { color: tok('m-arousal') }, lineStyle: { color: tok('m-arousal'), width: 2 } },
      { name: 'Arousal', type: 'line', showSymbol: false, data: [...arousal].sort().map(([d, v]) => [d, v]), itemStyle: { color: tok('m-arousal') }, lineStyle: { color: tok('m-arousal'), width: 1.5, type: 'dashed' } },
    ],
  }), [erection, arousal])
  const le = last(erection), la = last(arousal)
  return (
    <Board className="it-grid">
      <Card title="MORNING SIGNS" info="Self-rated, 1 to 10. Morning erection quality tends to follow sleep and recovery. Each opens its page with the whole history and Compare.">
        <Tiles tiles={[
          { label: 'Morning erection', value: le ? f1(le[1]) : '–', sub: le ? `${fullDate(le[0])} · 30-day average ${f1(avg30(erection))}` : 'not rated yet', hue: 'm-arousal', href: metricHref('recovery.morning_erection') },
          { label: 'Arousal', value: la ? f1(la[1]) : '–', sub: la ? `${fullDate(la[0])} · 30-day average ${f1(avg30(arousal))}` : 'not rated yet', hue: 'm-arousal', href: metricHref('recovery.arousal') },
        ]} />
        {erection.size + arousal.size > 0 && <Chart option={option} height={200} label="Morning erection and arousal over time" />}
      </Card>
      <Card title="WHAT GOES WITH WHAT" info="Pearson's r with its 95% interval, at least 10 paired days, as on the phone. A correlation never shows cause.">
        <Rows rows={rows.map((r) => ({
          label: r.label,
          value: r.c ? <span className={r.c.clear ? '' : 'it-dim'}>r {f1(r.c.r)}</span> : '–',
          sub: r.c ? `95% interval ${f1(r.c.lo)} to ${f1(r.c.hi)} · ${r.c.n} days · ${r.note}${r.c.clear ? '' : ' · includes zero'}` : 'Not enough paired days yet (10 needed).',
        }))} />
      </Card>
    </Board>
  )
}

// ---- effects: the days around an activity ----

const EVENTS = [['partnered', 'PARTNERED SEX'], ['solo', 'SOLO'], ['any', 'ANY']] as const
const OFFSETS = [-1, 0, 1, 2]
const OFFSET_WORD: Record<number, string> = { [-1]: 'DAY BEFORE', 0: 'THE DAY', 1: 'NEXT DAY', 2: 'DAY AFTER' }

export function IntimacyEffects({ data, from, to, params, set }: ViewProps) {
  const kind = (EVENTS.find((e) => e[0] === params.get('event'))?.[0] ?? 'partnered') as typeof EVENTS[number][0]
  const { out, st } = useOutcomes(addDays(from ?? '2016-01-01', -3))
  const partnered = new Set(data.encounters.filter((e) => e.sexual && inRange(e.date, from, to)).map((e) => e.date))
  const solo = new Set(data.solo.filter((s) => s.instances.length && inRange(s.date, from, to)).map((s) => s.date))
  const any = new Set([...partnered, ...solo])
  const events = kind === 'partnered' ? partnered : kind === 'solo' ? solo : any
  // quiet days are judged against every activity on record, not only the window's
  const allActive = new Set([...data.encounters.filter((e) => e.sexual).map((e) => e.date), ...data.solo.filter((s) => s.instances.length).map((s) => s.date)])
  const rows = useMemo(() => (out?.outcomes ?? []).map((o) => ({ o, offsets: eventStudy(events, allActive, new Map([...o.days].filter(([d]) => inRange(d, from ? addDays(from, -3) : null, to))), OFFSETS) })), [out, events, from, to]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="it-views-body">
      <div className="it-chips">{EVENTS.map(([k, l]) => <button key={k} className="ft-chip" aria-current={kind === k} onClick={() => set('event', k === 'partnered' ? null : k)}>{l}</button>)}</div>
      <Card title="THE DAYS AROUND IT" wide aside={`${events.size} DAYS`}
        info={`Each number is the average on days around a ${kind === 'solo' ? 'solo' : kind === 'any' ? 'active' : 'partnered'} day minus the average on quiet days (no activity of any kind two days either side), with a 95% interval. Sleep, HRV and resting heart rate are keyed to the morning they end on, so NEXT DAY is the night after. Days with sex differ in other ways too (weekends, travel, alcohol): this is a description, not a cause. Each cell needs ${MIN_EVENT_N} days on both sides.`}>
        {st.error && <p className="it-error">Could not load the daily series: {st.error}</p>}
        {out?.missing.length ? <p className="it-info">Not readable here: {out.missing.join(', ')}.</p> : null}
        {!out && st.loading ? <p className="it-info">Loading…</p> : (
          <div className="it-table-wrap"><table className="it-table it-effects">
            <thead><tr><th className="mono">MEASURE</th><th className="mono">QUIET DAYS</th>{OFFSETS.map((o) => <th key={o} className="mono">{OFFSET_WORD[o]}</th>)}</tr></thead>
            <tbody>{rows.map(({ o, offsets }) => (
              <tr key={o.id} className="it-static">
                <td><a className="it-link" href={o.metric ? metricHref(o.metric) : undefined} style={{ color: `var(--${o.hue})` }}>{o.label} ›</a></td>
                <td className="it-num">{offsets[0].baseMean != null ? `${f1(offsets[0].baseMean)}${o.unit ? ` ${o.unit}` : ''}` : '–'}<span className="it-dim"> · {offsets[0].baseN} d</span></td>
                {offsets.map((x) => (
                  <td key={x.offset} className="it-num" title={x.diff != null ? `${f1(x.mean)} on ${x.n} days; interval ${signed(x.lo!)} to ${signed(x.hi!)}` : `${x.n} days`}>
                    {x.diff != null
                      ? <><span className={x.clear ? '' : 'it-dim'}>{signed(x.diff)}</span><span className="it-sub">{signed(x.lo!)} to {signed(x.hi!)} · {x.n} d</span></>
                      : <span className="it-dim">{x.n} d</span>}
                  </td>
                ))}
              </tr>
            ))}</tbody>
          </table></div>
        )}
        <p className="it-info">A pale number has an interval that includes zero. To test one of these properly, ask it as a question on Lab › Evidence; to look at any two days side by side, open a measure and use + COMPARE.</p>
      </Card>
    </div>
  )
}

export type { StiTest }
