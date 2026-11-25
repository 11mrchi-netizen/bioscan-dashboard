// LAB › REPORTS (docs/reports/README.md): the weekly and monthly reports the nightly analysis run writes once a week (Monday
// to Sunday) or a month has ended. Every key metric against the period before, the regimen's adherence, what the Lab's
// interventions and experiments found, micronutrients from food, a tentative read of food against stool, and a few
// suggestions from fixed rules. The page only reads what was written (lib/reports.ts); "Rebuild" asks the server to write a
// report again, for data that synced late.
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { metricHref, metricInk } from '../lib/metrics'
import {
  adherenceTone, arrow, fmt, LAB_WORDS, latest, lineStatus, lineSub, type PeriodReport, type ReportGrain, SECTION_LABELS, spanText, type Tone,
} from '../lib/reports'
import { Board } from '../components/layout/Board'
import './Pages.css'
import './Reports.css'

async function loadReports(): Promise<PeriodReport[]> {
  const { data, error } = await supabase.from('analysis_results').select('key, as_of, payload').eq('kind', 'period_report').order('as_of', { ascending: false }).limit(200)
  if (error) throw new Error(error.message)
  return ((data ?? []) as { payload: PeriodReport }[]).map((r) => r.payload).filter((p) => p && typeof p.key === 'string')
}

const TONE: Record<Exclude<Tone, null>, string> = { ok: 'var(--ok)', warn: 'var(--warn)', bad: 'var(--bad)' }
const toneStyle = (t: Tone) => (t ? { color: TONE[t] } : undefined)
const HUE_OF: Record<string, string> = { stool_normal_pct: 'var(--m-stool-healthy)', stool_discomfort: 'var(--m-gut-comfort)' }

export default function Reports() {
  const [params, setParams] = useSearchParams()
  const [reports, setReports] = useState<PeriodReport[] | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const st = useLoadEffect(async (cur) => { const r = await loadReports(); if (cur()) setReports(r) }, [])

  const grain = (params.get('grain') === 'month' ? 'month' : params.get('grain') === 'week' ? 'week' : null) as ReportGrain | null
  const wanted = params.get('key')
  const list = (reports ?? []).filter((r) => !grain || r.grain === grain)
  const report = (wanted ? reports?.find((r) => r.key === wanted) : null) ?? latest(reports ?? [], grain ?? undefined)
  const pick = (key: string) => setParams((p) => { const q = new URLSearchParams(p); q.set('key', key); return q })
  const setGrain = (g: ReportGrain | null) => setParams((p) => { const q = new URLSearchParams(p); if (g) q.set('grain', g); else q.delete('grain'); q.delete('key'); return q })

  async function ask(body: Record<string, unknown>, label: string) {
    setBusy(label)
    setNote(null)
    const { data, error } = await supabase.functions.invoke('analysis-run', { body: { action: 'report', ...body } })
    setBusy(null)
    if (error) { setNote(`Could not build: ${error.message}`); return }
    const written = (data as { reports?: { written?: string[] } })?.reports?.written ?? []
    setNote(written.length ? `Written: ${written.length} report${written.length === 1 ? '' : 's'}.` : 'Nothing to write: those periods have not ended yet.')
    st.retry()
  }

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">REPORTS</h2>
          <p className="pg-sub">
            Written each Monday morning for the week before (Monday to Sunday) and on the 1st for the month before: every key metric against the
            period before, what the regimen and the Lab show, and a few suggestions. A change counts only when it is bigger than your usual swing.
          </p>
        </div>
        <div className="pb-chips">
          {([[null, 'ALL'], ['week', 'WEEKLY'], ['month', 'MONTHLY']] as const).map(([g, label]) => (
            <button key={label} className={`pb-chip ${grain === g ? 'on' : ''}`} onClick={() => setGrain(g)}>{label}</button>
          ))}
          {list.length > 0 && (
            <select className="rp-select mono" aria-label="Report" value={report?.key ?? ''} onChange={(e) => pick(e.target.value)}>
              {list.map((r) => <option key={r.key} value={r.key}>{r.grain === 'week' ? 'WEEK' : 'MONTH'} · {spanText(r)}{r.grain === 'month' ? '' : ` ${r.start.slice(0, 4)}`}</option>)}
            </select>
          )}
        </div>
      </header>
      {st.error && <p className="pb-warn">Could not load: {st.error}</p>}
      {st.loading && !reports && <p className="state-loading">LOADING…</p>}
      {note && <p className="rp-note">{note}</p>}
      {reports && !report && (
        <section className="ft-card rp-empty">
          <p>No report has been written yet. The first weekly one comes on Monday morning; you can also build the last few now.</p>
          <button className="pb-chip on" disabled={!!busy} onClick={() => ask({ weeks: 8, months: 3 }, 'fill')}>{busy === 'fill' ? 'BUILDING…' : 'BUILD THE LAST 8 WEEKS AND 3 MONTHS'}</button>
        </section>
      )}
      {report && <ReportView r={report} busy={busy} onRebuild={() => ask({ keys: [report.key] }, 'rebuild')} />}
    </div>
  )
}

function ReportView({ r, busy, onRebuild }: { r: PeriodReport; busy: string | null; onRebuild: () => void }) {
  const shown = r.metrics.filter((l) => l.status !== 'no_data')
  const missing = r.metrics.filter((l) => l.status === 'no_data')
  const range = r.grain === 'week' ? '90' : '365'
  const followed = r.regimen.filter((a) => !a.asNeeded)
  const asNeeded = r.regimen.filter((a) => a.asNeeded)
  const microLogged = r.micro.lines.filter((l) => l.status !== 'no_data')
  // Flags first: over the limit, then over it on some days, then possibly short; the rest in the list's order.
  const RANK: Record<string, number> = { high: 0, high_days: 1, low: 2, ok: 3 }
  const microRows = [...microLogged].sort((a, b) => (RANK[a.status] ?? 3) - (RANK[b.status] ?? 3))
  return (
    <>
      <div className="rp-title">
        <h3 className="mono">{r.label.toUpperCase()} <span className="rp-dim">· {spanText(r)} · against {spanText({ start: r.prevStart, end: r.prevEnd, grain: r.grain })}</span></h3>
        <button className="pb-chip" disabled={!!busy} onClick={onRebuild}>{busy === 'rebuild' ? 'REBUILDING…' : 'REBUILD'}</button>
      </div>

      <div className="ft-band rp-band" aria-label="Key metrics">
        {shown.map((l) => {
          const s = lineStatus(l)
          const body = (
            <>
              <span className="me-tile-label">{l.label}</span>
              <span className="me-tile-value" style={{ color: HUE_OF[l.id] ?? metricInk(l.id) }}>{fmt(l.value)} <small>{l.unit}</small></span>
              <span className="me-tile-sub"><span style={toneStyle(s.tone)}>{arrow(l.delta)} {s.word}</span> · {lineSub(l, r.grain)}</span>
            </>
          )
          return l.ref
            ? <a key={l.id} className="me-tile tappable" href={metricHref(l.ref, { day: r.end, range })} title={SECTION_LABELS[l.section]}>{body}</a>
            : <div key={l.id} className="me-tile" title={SECTION_LABELS[l.section]}>{body}</div>
        })}
      </div>
      {missing.length > 0 && <p className="rp-dim rp-missing">No data this {r.grain}: {missing.map((l) => l.label).join(', ')}.</p>}

      <Board className="pg-grid">
        <section className="ft-card pg-card rp-card">
          <h3 className="pg-card-title mono"><span>SUGGESTIONS</span><span>{r.suggestions.length}</span></h3>
          {r.suggestions.length === 0 ? <p className="rp-dim">Nothing to change: no rule fired this {r.grain}.</p> : (
            <ol className="rp-sugg">
              {r.suggestions.map((s) => (
                <li key={s.id}>
                  <span>{s.text}</span>
                  <span className="rp-dim mono"> {s.because.toUpperCase()}</span>
                  {s.ref && <> · <a className="rp-link" href={metricHref(s.ref, { day: r.end, range })}>HISTORY ›</a></>}
                </li>
              ))}
            </ol>
          )}
        </section>

        <section className="ft-card pg-card rp-card">
          <h3 className="pg-card-title mono"><span>AT A GLANCE</span></h3>
          <ul className="pg-rows">
            <Glance label="Better" items={r.summary.better} tone="ok" />
            <Glance label="Worse" items={r.summary.worse} tone="bad" />
            <Glance label="Working" items={r.summary.working} tone="ok" />
            <Glance label="No effect or worse" items={r.summary.wasted} tone="warn" />
            <Glance label="Not followed" items={r.summary.notFollowed} tone="bad" />
            <Glance label="Nutrients over the upper limit" items={r.summary.excess ?? []} tone="bad" />
            <Glance label="Nutrients possibly short" items={r.summary.short ?? []} tone="warn" />
          </ul>
        </section>

        <section className="ft-card pg-card rp-card">
          <h3 className="pg-card-title mono"><span>REGIMEN</span><span>{followed.length} scheduled</span></h3>
          {followed.length === 0 ? <p className="rp-dim">Nothing scheduled in this {r.grain}.</p> : (
            <ul className="pg-rows">{followed.map((a) => (
              <li key={a.id} className="pg-row">
                <span className="pg-row-title">{a.name}</span>
                <span className="pg-row-value" style={toneStyle(adherenceTone(a.rate))}>{a.rate == null ? '–' : `${Math.round(a.rate * 100)}%`}</span>
                <span className="pg-row-sub">
                  {a.taken} of {a.planned} planned doses
                  {a.prevRate != null && ` · ${Math.round(a.prevRate * 100)}% the ${r.grain} before`}
                  {a.status === 'not_followed' && ' · not followed'}
                </span>
              </li>
            ))}</ul>
          )}
          {asNeeded.length > 0 && <p className="rp-dim rp-foot">As needed: {asNeeded.map((a) => `${a.name} ${a.taken}×`).join(' · ')}</p>}
          <p className="rp-foot"><a className="rp-link" href="#/lab/regimen">REGIMEN ›</a></p>
        </section>

        <section className="ft-card pg-card rp-card">
          <h3 className="pg-card-title mono"><span>LAB</span><span>{r.lab.length}</span></h3>
          {r.lab.length === 0 ? (
            <p className="rp-dim">No intervention or experiment ran this {r.grain}. Something taken for months with nothing measuring it is a candidate: start one in <a className="rp-link" href="#/lab/experiments?view=interventions">Experiments ›</a></p>
          ) : (
            <ul className="pg-rows">{r.lab.map((l) => (
              <li key={`${l.source}:${l.id}`} className="pg-row">
                <span className="pg-row-title"><a className="rp-link" href={l.href}>{l.name} ›</a></span>
                <span className="pg-row-value" style={toneStyle(LAB_WORDS[l.verdict].tone)}>{LAB_WORDS[l.verdict].word}</span>
                <span className="pg-row-sub">{l.source === 'experiment' ? 'Experiment' : 'Intervention'} · {l.detail}</span>
              </li>
            ))}</ul>
          )}
        </section>

        <section className="ft-card pg-card rp-card">
          <h3 className="pg-card-title mono"><span>NUTRIENTS · FOOD AND SUPPLEMENTS</span><span>{r.micro.foodDays} of {r.micro.days ?? '–'} days of food logged</span></h3>
          {microLogged.length === 0 ? <p className="rp-dim">No food or supplement with nutrient detail was logged this {r.grain}.</p> : (
            <ul className="pg-rows">{microRows.map((m) => {
              const tone: Tone = m.status === 'high' ? 'bad' : m.status === 'high_days' || m.status === 'low' ? 'warn' : null
              const flag = m.status === 'high' ? 'over the safe upper limit' : m.status === 'high_days' ? `over the upper limit on ${m.daysOver} day${m.daysOver === 1 ? '' : 's'}` : m.status === 'low' ? 'possibly short' : null
              return (
                <li key={m.key} className="pg-row">
                  <span className="pg-row-title">{m.label}{flag && <span className="pg-tag mono" style={toneStyle(tone)}>{flag.toUpperCase()}</span>}</span>
                  <span className="pg-row-value">{fmt(m.value)} {m.unit}{m.pct != null && <span className="rp-dim"> · {m.pct}%</span>}</span>
                  <span className="pg-row-sub">
                    {m.food != null ? `${fmt(m.food)} from food` : 'not tracked in food'}
                    {m.supplements > 0 && ` + ${fmt(m.supplements)} from supplements${m.fromSupplements?.length ? ` (${m.fromSupplements.join(', ')})` : ''}`}
                    {m.reference != null && ` · need ${fmt(m.reference)}`}
                    {m.upper != null && ` · upper limit ${fmt(m.upper)}${m.upperScope === 'supplements' ? ' from supplements' : ''}`}
                    {m.prev != null && ` · ${fmt(m.prev)} the ${r.grain} before`}
                  </span>
                </li>
              )
            })}</ul>
          )}
          <p className="rp-dim rp-foot">Food is a daily mean over the days with food logged; supplements over every day of the {r.grain} (a dose not logged was not taken). Needs are adult-male RDA or AI; upper limits are the safe upper intake levels.</p>
        </section>

        {r.mealQuality && (
          <section className="ft-card pg-card rp-card">
            <h3 className="pg-card-title mono"><span>MEAL QUALITY</span><span>{r.mealQuality.meals} meals</span></h3>
            <ul className="pg-rows">{r.mealQuality.lines.map((l) => (
              <li key={l.key} className="pg-row">
                <span className="pg-row-title">{l.label}</span>
                <span className="pg-row-value" style={{ color: `var(--${MEAL_QUALITY_HUE[l.key]})` }}>{fmt(l.value)}{l.unit && <small> {l.unit}</small>}</span>
                <span className="pg-row-sub">{l.basis}{l.prev != null && ` · ${fmt(l.prev)} the ${r.grain} before`}</span>
              </li>
            ))}</ul>
            <p className="rp-dim rp-foot">Each index on its own, from the profile of every meal (open a meal in the phone's Log to see it). Glycemic load and the food groups are estimated from typical values of the foods.</p>
          </section>
        )}

        <section className="ft-card pg-card rp-card">
          <h3 className="pg-card-title mono"><span>FOOD AND STOOL</span><span>TENTATIVE</span></h3>
          {r.foodStool.length === 0 ? (
            <p className="rp-dim">No food stands out against the next day's stool over the last 90 days (it needs eight days each side and a gap of 20 points).</p>
          ) : (
            <ul className="pg-rows">{r.foodStool.map((f) => (
              <li key={f.food} className="pg-row">
                <span className="pg-row-title">{f.label} above {fmt(f.split)} {f.unit}</span>
                <span className="pg-row-value">{f.highPct}% vs {f.lowPct}%</span>
                <span className="pg-row-sub">Normal stools (Bristol 3–5) the day after a high day ({f.highDays}) against a low day ({f.lowDays}). An association, not a cause.</span>
              </li>
            ))}</ul>
          )}
        </section>
      </Board>
      <p className="rp-dim rp-missing">Written {r.generatedOn}. Rules: docs/reports/README.md.</p>
    </>
  )
}

/** The hue of each meal-quality index (design/tokens), the same as on the phone's meal profile. */
const MEAL_QUALITY_HUE: Record<string, string> = {
  protein_dose: 'm-protein', energy_density: 'm-energy-density', glycemic_load: 'm-glycemic-load', fiber_density: 'm-fiber', ultra_processed: 'm-ultra-processed',
}

function Glance({ label, items, tone }: { label: string; items: string[]; tone: Tone }) {
  return (
    <li className="pg-row">
      <span className="pg-row-title">{label}</span>
      <span className="pg-row-value" style={items.length ? toneStyle(tone) : undefined}>{items.length}</span>
      {items.length > 0 && <span className="pg-row-sub">{items.join(', ')}</span>}
    </li>
  )
}
