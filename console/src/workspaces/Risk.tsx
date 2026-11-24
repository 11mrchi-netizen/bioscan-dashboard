// ANALYSIS › RISK (risk profile, docs/risk-profile/01-plan.md): exposures and risks to dig into, with ways to improve,
// rather than one number. R1: the air breathed each day (analysis-core/airExposure.ts writes it nightly) with an hour-by-hour
// view of any day; R0: home blood pressure series beside the clinic readings from lab draws. Read-only: readings are
// logged on the phone. The same summary is the phone's Me › EXPOSURES, and the Dashboard's.
import { useEffect, useMemo, useRef, useState } from 'react'
import { supabase } from '../lib/supabase'
import { useChart } from '../lib/useChart'
import { useLoadEffect } from '../lib/useLoadEffect'
import { cssColor, PALETTE } from '../lib/palette'
import type { EChartsCoreOption } from '../lib/echarts'
import { type BpRow, categorize, series as bpSeries, sittings } from '../lib/bp'
import { CO2_POOR, CO2_VENTILATE, WHO_NO2_24H, WHO_O3_8H, WHO_PM25_24H, WHO_PM25_ANNUAL } from '../../../supabase/functions/_shared/analysis-core/airExposure.ts'
import { RiskQuestionnaires, type Response } from './RiskQuestionnaires'
import { RiskFromPhone } from './RiskFromPhone'
import { localToday } from '../lib/instruments'
import './Pages.css'

// Metric hues for canvas charts: the values of design/tokens (--m-pm25, --m-no2, --m-ozone, --m-co2, --m-uv-index,
// --m-blood-pressure). Charts need the colour itself, not the variable.
// One fixed hue per metric (design/tokens --m-*), resolved when read so the canvas charts and the page agree.
const tok = (name: string) => cssColor(`var(--${name})`)
const HUE = {
  get pm25() { return tok('m-pm25') }, get no2() { return tok('m-no2') }, get ozone() { return tok('m-ozone') },
  get co2() { return tok('m-co2') }, get uv() { return tok('m-uv-index') }, get bp() { return tok('m-blood-pressure') },
}

interface AirDay {
  date: string; coverage_h: number; pm25_ugm3: number | null; pm25_indoor_ugm3: number | null; pm25_outdoor_home_ugm3: number | null
  no2_ugm3: number | null; o3_mda8_ugm3: number | null; uv_max: number | null; co2_night_ppm: number | null
  dose_home_share: number | null; sources: Record<string, number>; hours: (string | number | null)[][]
  sessions: { start: string; type: string; minutes: number; pm25: number | null; o3: number | null; uv: number | null }[]
}
interface Improvement { id: string; title: string; why: string; evidence: string; effort: string; lowers: string[] }
interface Summary {
  days_with_data: number; full_days: number; pm25_mean: number | null; pm25_days_over_24h: number; no2_mean: number | null
  no2_days_over_24h: number; o3_days_over_8h: number; dose_home_share: number | null; indoor_sensor: boolean
  co2_night_mean: number | null; co2_poor_nights: number; sessions_outdoor: number; sessions_poor_air: number
  states: Record<string, string | null>; improvements: Improvement[]; missing?: string[]; outdoorError?: string | null
}
interface Clinic { date: string; systolic: number; diastolic: number }

const SOURCE_WORD: Record<string, string> = { indoor: 'indoors (sensor)', session: 'outdoor session', outdoor_home: 'home, outdoor level', outdoor_away: 'away, outdoor level' }
const STATE_COLOR: Record<string, string> = { ok: 'var(--ft-ok, #10B981)', warn: 'var(--ft-warn, #F59E0B)', bad: 'var(--ft-bad, #EF4444)' }
const f1 = (v: number | null | undefined) => (v == null ? '–' : v.toFixed(1))

const AXES = {
  grid: { left: 48, right: 16, top: 28, bottom: 28 },
  tooltip: { trigger: 'axis' },
  legend: { top: 0, textStyle: { color: PALETTE.text } },
} as const
const yAxis = (name: string): EChartsCoreOption => ({ type: 'value', name, scale: false, nameTextStyle: { color: PALETTE.textMuted }, axisLabel: { color: PALETTE.textMuted }, splitLine: { lineStyle: { color: PALETTE.border } } })
const timeAxis: EChartsCoreOption = { type: 'time', axisLabel: { color: PALETTE.textMuted }, axisLine: { lineStyle: { color: PALETTE.border } } }
const guide = (value: number, label: string) => ({ silent: true, symbol: 'none', lineStyle: { color: PALETTE.textMuted, type: 'dashed' }, label: { color: PALETTE.textMuted, formatter: label }, data: [{ yAxis: value }] })
const line = (name: string, data: [string, number][], color: string, extra: object = {}) =>
  ({ name, type: 'line', data, showSymbol: data.length < 40, symbolSize: 4, connectNulls: false, itemStyle: { color }, lineStyle: { color, width: 2 }, ...extra })

function Chart({ option, height = 220, onPick }: { option: EChartsCoreOption; height?: number; onPick?: (date: string) => void }) {
  const ref = useRef<HTMLDivElement>(null)
  const chart = useChart(ref)
  useEffect(() => { chart.current?.setOption(option, true) }, [chart, option])
  useEffect(() => {
    const c = chart.current
    if (!c || !onPick) return
    const h = (p: { value?: unknown }) => { const v = Array.isArray(p.value) ? p.value[0] : null; if (typeof v === 'string') onPick(v.slice(0, 10)) }
    c.on('click', h)
    return () => { c.off('click', h) }
  }, [chart, onPick])
  return <div ref={ref} style={{ width: '100%', height }} />
}

/** `embedded`: inside Me › EXPOSURES, which already draws the phone's own sections above, so they are not drawn a second time here. */
export default function Risk({ embedded = false }: { embedded?: boolean }) {
  const [days, setDays] = useState<AirDay[]>([])
  const [summary, setSummary] = useState<{ asOf: string; payload: Summary } | null>(null)
  const [bp, setBp] = useState<BpRow[]>([])
  const [clinic, setClinic] = useState<Clinic[]>([])
  const [day, setDay] = useState<string | null>(null)
  const [notes, setNotes] = useState<string[]>([])
  // Risk profile R2: the questionnaires, read on their own (reloaded after one is answered here).
  const [responses, setResponses] = useState<Response[]>([])
  const [responsesMissing, setResponsesMissing] = useState(false)
  const [answered, setAnswered] = useState(0)
  // Risk profile R5: ways to improve already started as interventions (improvement id -> start date), and starting one.
  const [started, setStarted] = useState<Record<string, string>>({})
  const [starting, setStarting] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    void supabase.from('interventions').select('subject_key,started_at,lifecycle_state').eq('subject_type', 'risk_improvement').then(({ data }) => {
      if (!live) return
      const m: Record<string, string> = {}
      for (const r of (data ?? []) as { subject_key: string; started_at: string | null; lifecycle_state: string }[]) {
        if (r.lifecycle_state !== 'stopped' && r.started_at) m[r.subject_key.replace(/^air:/, '')] = r.started_at
      }
      setStarted(m)
    })
    return () => { live = false }
  }, [answered])
  async function startImprovement(i: Improvement) {
    setStarting(i.id)
    const today = localToday()
    const evaluate = new Date(Date.parse(today + 'T00:00:00Z') + 28 * 86400000).toISOString().slice(0, 10)
    const { error } = await supabase.from('interventions').insert({
      id: crypto.randomUUID(), domain: 'lifestyle', subject_type: 'risk_improvement', subject_key: `air:${i.id}`,
      name: i.title.slice(0, 80), mechanism: i.why, intended_outcomes: i.lowers, lifecycle_state: 'active',
      started_at: today, evaluate_on: evaluate, source: 'risk_profile',
    })
    setStarting(null)
    if (error) { setNotes((n) => [...n, `could not start: ${error.message}`]); return }
    setAnswered((n) => n + 1)
  }
  useEffect(() => {
    let live = true
    void supabase.from('instrument_responses').select('id,instrument,local_date,score,band,flags,source').order('answered_at').then(({ data, error }) => {
      if (!live) return
      setResponsesMissing(!!error)
      setResponses((data ?? []) as Response[])
    })
    return () => { live = false }
  }, [answered])

  const st = useLoadEffect(async (cur) => {
    const since = new Date(Date.now() - 120 * 86400000).toISOString().slice(0, 10)
    const [a, s, b, lr] = await Promise.all([
      supabase.from('air_exposure_daily').select('date,coverage_h,pm25_ugm3,pm25_indoor_ugm3,pm25_outdoor_home_ugm3,no2_ugm3,o3_mda8_ugm3,uv_max,co2_night_ppm,dose_home_share,sources,hours,sessions').gte('date', since).order('date'),
      supabase.from('analysis_results').select('as_of,payload').eq('kind', 'air_exposure').eq('key', 'summary').order('as_of', { ascending: false }).limit(1),
      supabase.from('bp_readings').select('id,occurred_at,systolic,diastolic,pulse,source').order('occurred_at'),
      supabase.from('lab_results').select('draw_id,marker_name,value').in('marker_name', ['SBP, Sitting', 'DBP, Sitting']),
    ])
    // Read softly: before the migrations reach this database, a missing table is a note, not a failure.
    const missing = [a.error && 'air_exposure_daily', b.error && 'bp_readings'].filter(Boolean) as string[]
    const ids = [...new Set(((lr.data ?? []) as { draw_id: number }[]).map((r) => r.draw_id))]
    const draws = ids.length ? await supabase.from('lab_draws').select('id,draw_date').in('id', ids) : { data: [] }
    if (!cur()) return
    setNotes(missing)
    setDays((a.data ?? []) as AirDay[])
    const row = (s.data ?? [])[0] as { as_of: string; payload: Summary } | undefined
    setSummary(row ? { asOf: row.as_of, payload: row.payload } : null)
    setBp((b.data ?? []) as BpRow[])
    const dateOf = new Map(((draws.data ?? []) as { id: number; draw_date: string }[]).map((d) => [d.id, d.draw_date]))
    const byDraw = new Map<number, Partial<Clinic>>()
    for (const r of (lr.data ?? []) as { draw_id: number; marker_name: string; value: number | null }[]) {
      const c = byDraw.get(r.draw_id) ?? { date: dateOf.get(r.draw_id) }
      if (r.value != null) { if (r.marker_name.startsWith('SBP')) c.systolic = r.value; else c.diastolic = r.value }
      byDraw.set(r.draw_id, c)
    }
    setClinic([...byDraw.values()].filter((c): c is Clinic => !!c.date && c.systolic != null && c.diastolic != null).sort((x, y) => x.date.localeCompare(y.date)))
  }, [])

  const pick = (k: keyof AirDay) => days.filter((d) => d[k] != null).map((d) => [d.date, d[k] as number] as [string, number])
  const pmOption = useMemo<EChartsCoreOption>(() => ({
    ...AXES, xAxis: timeAxis, yAxis: yAxis('µg/m³'),
    series: [
      line('PM2.5 breathed', pick('pm25_ugm3'), HUE.pm25, { markLine: { ...guide(WHO_PM25_24H, 'WHO 24 h'), data: [{ yAxis: WHO_PM25_24H }, { yAxis: WHO_PM25_ANNUAL }] } }),
      line('Indoors at home', pick('pm25_indoor_ugm3'), HUE.pm25, { lineStyle: { color: HUE.pm25, width: 1.5, type: 'dotted' } }),
      line('Outdoors at home', pick('pm25_outdoor_home_ugm3'), HUE.pm25, { lineStyle: { color: HUE.pm25, width: 1.5, type: 'dashed' }, itemStyle: { color: HUE.pm25, opacity: 0.6 } }),
    ],
  }), [days]) // eslint-disable-line react-hooks/exhaustive-deps
  const gasOption = useMemo<EChartsCoreOption>(() => ({
    ...AXES, xAxis: timeAxis, yAxis: yAxis('µg/m³'),
    series: [
      line('NO₂', pick('no2_ugm3'), HUE.no2, { markLine: guide(WHO_NO2_24H, 'NO₂ 24 h') }),
      line('Ozone, highest 8 h', pick('o3_mda8_ugm3'), HUE.ozone, { markLine: guide(WHO_O3_8H, 'O₃ 8 h') }),
    ],
  }), [days]) // eslint-disable-line react-hooks/exhaustive-deps
  const co2Option = useMemo<EChartsCoreOption>(() => ({
    ...AXES, xAxis: timeAxis, yAxis: yAxis('ppm'),
    series: [line('CO₂ at night, home', pick('co2_night_ppm'), HUE.co2, { markLine: { ...guide(CO2_VENTILATE, ''), data: [{ yAxis: CO2_VENTILATE }, { yAxis: CO2_POOR }] } })],
  }), [days]) // eslint-disable-line react-hooks/exhaustive-deps

  // The day clicked, else the latest full day (today is still filling in), else the latest with anything.
  const picked = days.find((d) => d.date === day) ?? [...days].reverse().find((d) => d.coverage_h >= 18) ?? [...days].reverse().find((d) => d.coverage_h > 0) ?? null
  const hourOption = useMemo<EChartsCoreOption | null>(() => {
    if (!picked) return null
    const hrs = picked.hours
    return {
      ...AXES,
      tooltip: { trigger: 'axis', formatter: (ps: { dataIndex: number }[]) => { const h = hrs[ps[0]?.dataIndex ?? 0]; return h ? `${String(h[0]).padStart(2, '0')}:00 · ${SOURCE_WORD[String(h[1])] ?? 'no location'}<br/>PM2.5 ${h[2] ?? '–'} · NO₂ ${h[3] ?? '–'} · O₃ ${h[4] ?? '–'}${h[6] != null ? ` · CO₂ ${h[6]}` : ''}` : '' } },
      xAxis: { type: 'category', data: hrs.map((h) => String(h[0]).padStart(2, '0')), axisLabel: { color: PALETTE.textMuted } },
      yAxis: yAxis('µg/m³'),
      series: [
        { name: 'PM2.5', type: 'bar', itemStyle: { color: HUE.pm25 }, data: hrs.map((h) => ({ value: h[2], itemStyle: { color: HUE.pm25, opacity: h[1] === 'indoor' || h[1] === 'session' ? 1 : 0.55 } })) },
        line('NO₂', hrs.map((h) => h[3] as number).map((v, i) => [String(hrs[i][0]).padStart(2, '0'), v] as [string, number]), HUE.no2),
      ],
    }
  }, [picked])

  const sit = useMemo(() => sittings(bp), [bp])
  const runs = useMemo(() => bpSeries(bp), [bp])
  const lastUsable = [...runs].reverse().find((r) => r.usable)
  const bpOption = useMemo<EChartsCoreOption>(() => ({
    ...AXES, xAxis: timeAxis, yAxis: yAxis('mmHg'),
    series: [
      line('Systolic (sitting mean)', sit.map((x) => [x.start.slice(0, 16), Math.round(x.systolic)] as [string, number]), HUE.bp, { markLine: { ...guide(130, '130 / 80'), data: [{ yAxis: 130 }, { yAxis: 80 }] } }),
      line('Diastolic', sit.map((x) => [x.start.slice(0, 16), Math.round(x.diastolic)] as [string, number]), HUE.bp, { lineStyle: { color: HUE.bp, width: 1.5, type: 'dashed' } }),
      // Clinic readings within 60 days of the home readings; older ones are listed above, not stretched onto the axis.
      { name: 'Clinic', type: 'scatter', symbol: 'diamond', symbolSize: 10, itemStyle: { color: PALETTE.text }, data: clinic.filter((c) => !sit.length || Date.parse(c.date) >= Date.parse(sit[0].date) - 60 * 86400000).flatMap((c) => [[c.date, c.systolic], [c.date, c.diastolic]]) },
    ],
  }), [sit, clinic])

  const s = summary?.payload
  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">RISK</h2>
          <p className="pg-sub">Exposures and risks with ways to improve, not one number. Air is worked out each night from your places, the modelled outdoor air there (Open-Meteo, CAMS) and the home sensors; blood pressure from the home series on the phone (Lab › TESTS) and the clinic readings in your lab draws.</p>
        </div>
      </header>
      {st.error && <p className="pb-warn">Could not load: {st.error}</p>}
      {st.loading && days.length === 0 && bp.length === 0 && <p className="state-loading">LOADING…</p>}
      {notes.length > 0 && <p className="pg-note">Not on this database yet: {notes.join(', ')}.</p>}

      <RiskQuestionnaires responses={responses} missing={responsesMissing} onChanged={() => setAnswered((n) => n + 1)} />

      {!embedded && <RiskFromPhone />}

      <div className="pg-grid">
        <section className="pg-card">
          <h3 className="pg-card-title mono"><span>AIR · LAST 30 DAYS</span><span>{summary?.asOf ?? ''}</span></h3>
          {!s || s.days_with_data === 0 ? <p className="pg-note">No air days yet. They need location history (phone: Setup › Data capture › Locations); the run fills in the outdoor air itself.</p> : (
            <ul className="pg-rows">
              <li className="pg-row"><span className="pg-row-title mono" style={{ color: HUE.pm25 }}>PM2.5 BREATHED, MEAN</span><span className="pg-row-value" style={{ color: STATE_COLOR[s.states.pm25 ?? ''] }}>{f1(s.pm25_mean)} µg/m³</span></li>
              <li className="pg-row"><span className="pg-row-title mono">FULL DAYS ABOVE 15 (WHO 24 H)</span><span className="pg-row-value">{s.pm25_days_over_24h} of {s.full_days}</span></li>
              <li className="pg-row"><span className="pg-row-title mono" style={{ color: HUE.no2 }}>NO₂ WHERE YOU WERE</span><span className="pg-row-value" style={{ color: STATE_COLOR[s.states.no2 ?? ''] }}>{f1(s.no2_mean)} µg/m³ · {s.no2_days_over_24h} days above 25</span></li>
              <li className="pg-row"><span className="pg-row-title mono" style={{ color: HUE.ozone }}>OZONE ABOVE 100 (8 H)</span><span className="pg-row-value">{s.o3_days_over_8h} days</span></li>
              <li className="pg-row"><span className="pg-row-title mono" style={{ color: HUE.co2 }}>CO₂ AT NIGHT</span><span className="pg-row-value" style={{ color: STATE_COLOR[s.states.co2 ?? ''] }}>{s.co2_night_mean ?? '–'} ppm · {s.co2_poor_nights} poor nights</span></li>
              <li className="pg-row"><span className="pg-row-title mono">DOSE AT HOME</span><span className="pg-row-value">{s.dose_home_share == null ? '–' : `${Math.round(s.dose_home_share * 100)} %`} · {s.indoor_sensor ? 'measured indoors' : 'estimated (no indoor PM2.5 sensor)'}</span></li>
              <li className="pg-row"><span className="pg-row-title mono">OUTDOOR SESSIONS IN POOR AIR</span><span className="pg-row-value">{s.sessions_poor_air} of {s.sessions_outdoor}</span></li>
            </ul>
          )}
          {s?.missing && s.missing.length > 0 && <p className="pg-note">Not read last night: {s.missing.join(', ')}.</p>}
          {s?.outdoorError && <p className="pg-note">Outdoor feed: {s.outdoorError}</p>}
        </section>

        <section className="pg-card">
          <h3 className="pg-card-title mono"><span>WAYS TO IMPROVE</span><span>{s?.improvements.length ?? 0}</span></h3>
          {!s || s.improvements.length === 0 ? <p className="pg-note">Nothing your numbers call for right now.</p> : (
            <ul className="pg-rows">{s.improvements.map((i) => (
              <li key={i.id} className="pg-row" style={{ display: 'block' }}>
                <div className="pg-row-title">{i.title}</div>
                <p className="pg-note" style={{ margin: '4px 0' }}>{i.why}</p>
                <p className="pg-note" style={{ margin: '4px 0', opacity: 0.75 }}>{i.evidence}</p>
                <span className="mono" style={{ fontSize: 11, color: PALETTE.textMuted }}>EFFORT {i.effort.toUpperCase()} · LOWERS {i.lowers.join(', ').toUpperCase()}</span>
                {/* Risk profile R5: start it as a Lab intervention (the phone reads it back against the air days). */}
                <div style={{ marginTop: 6 }}>
                  {started[i.id]
                    ? <span className="mono" style={{ fontSize: 11 }}>STARTED {started[i.id]} · ON LAB › INTERVENTIONS · READ ON THE PHONE AFTER 4 WEEKS</span>
                    : <button className="btn-ghost" disabled={starting === i.id} onClick={() => void startImprovement(i)}>{starting === i.id ? 'STARTING…' : 'START AS AN INTERVENTION'}</button>}
                </div>
              </li>
            ))}</ul>
          )}
        </section>
      </div>

      {days.length > 0 && (
        <>
          <section className="pg-card">
            <h3 className="pg-card-title mono"><span>PM2.5 BY DAY</span><span>WHO 15 A DAY · 5 A YEAR · CLICK A DAY FOR ITS HOURS</span></h3>
            <Chart option={pmOption} onPick={setDay} />
          </section>
          {picked && hourOption && (
            <section className="pg-card">
              <h3 className="pg-card-title mono"><span>{picked.date} · HOUR BY HOUR</span><span>{picked.coverage_h} OF 24 H LOCATED</span></h3>
              <Chart option={hourOption} height={200} />
              <p className="pg-note">Solid bars are measured where you were (home sensor, or outdoors during a session); pale bars are the outdoor level used as a stand-in. Sources: {Object.entries(picked.sources ?? {}).filter(([, n]) => n > 0).map(([k, n]) => `${SOURCE_WORD[k] ?? k} ${n} h`).join(' · ') || 'none'}.</p>
              {picked.sessions.length > 0 && <ul className="pg-rows">{picked.sessions.map((x) => (
                <li key={x.start} className="pg-row"><span className="pg-row-title mono">{x.start.slice(11, 16)} · {x.type.toUpperCase()} {x.minutes} MIN</span><span className="pg-row-value">PM2.5 {f1(x.pm25)} · O₃ {f1(x.o3)} · UV {f1(x.uv)}</span></li>
              ))}</ul>}
            </section>
          )}
          <div className="pg-grid">
            <section className="pg-card"><h3 className="pg-card-title mono"><span>NO₂ AND OZONE</span><span>OUTDOOR, WHERE YOU WERE</span></h3><Chart option={gasOption} /></section>
            <section className="pg-card"><h3 className="pg-card-title mono"><span>CO₂ AT NIGHT</span><span>1,000 VENTILATE · 1,400 POOR (CONVENTIONS)</span></h3><Chart option={co2Option} /></section>
          </div>
        </>
      )}

      <section className="pg-card">
        <h3 className="pg-card-title mono"><span style={{ color: HUE.bp }}>BLOOD PRESSURE</span><span>{bp.length} READINGS · {sit.length} SITTINGS</span></h3>
        {bp.length === 0 && clinic.length === 0 ? <p className="pg-note">No readings yet. Log a 7-day series on the phone (Lab › TESTS › BLOOD PRESSURE), or allow blood pressure in Setup › Connected services to import your cuff app's.</p> : (
          <>
            <ul className="pg-rows">
              {lastUsable && <li className="pg-row"><span className="pg-row-title mono">HOME MEAN · {lastUsable.counted[0]} TO {lastUsable.days.at(-1)}</span><span className="pg-row-value">{Math.round(lastUsable.systolic!)}/{Math.round(lastUsable.diastolic!)} mmHg · {categorize(lastUsable.systolic!, lastUsable.diastolic!)}</span></li>}
              {!lastUsable && bp.length > 0 && <li className="pg-row"><span className="pg-row-title mono">HOME MEAN</span><span className="pg-row-value">needs 3 days after the first</span></li>}
              {clinic.at(-1) && <li className="pg-row"><span className="pg-row-title mono">CLINIC · {clinic.at(-1)!.date}</span><span className="pg-row-value">{clinic.at(-1)!.systolic}/{clinic.at(-1)!.diastolic} mmHg</span></li>}
              {runs.map((r) => (
                <li key={r.days[0]} className="pg-row"><span className="pg-row-title mono">SERIES {r.days[0]} · {r.days.length} DAYS</span><span className="pg-row-value">{r.usable ? `${Math.round(r.systolic!)}/${Math.round(r.diastolic!)} · ${r.readings} readings` : 'too short for a mean'}</span></li>
              ))}
            </ul>
            <Chart option={bpOption} />
            <p className="pg-note">Each point is a sitting's mean; the first day of a series is left out of its mean (AHA protocol). Categories: ACC/AHA 2017; home 130/80 is level with clinic 130/80.</p>
          </>
        )}
      </section>
    </div>
  )
}
