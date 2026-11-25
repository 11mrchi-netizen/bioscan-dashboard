// PLAN › RACES › one event's fuel plan: what to take and when (by time and by aid station), what to carry between stations, what to do before and
// after, worked out with the phone's own rules (supabase/functions/_shared/fuel-core, checked against it). Read only: the phone keeps the saved
// schedule (fuel_schedules) and its stored plans. Carbohydrate tolerance, caffeine and salty sweat are the phone's fuel settings, read from the account.
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useLoadEffect } from '../lib/useLoadEffect'
import { loadRaceFuel, type RaceFuel } from '../lib/raceFuelData'
import { storeEvent, type StoreResult } from '../lib/fuelPlansStore'
import { KeptNote } from './FuelPlan'
import ScheduleEditor from './ScheduleEditor'
import { eventSnapshot, fmtMin, roundToInt, seedBlocks, type FuelStep, type CarryLeg, type PrepItem } from '../../../supabase/functions/_shared/fuel-core/index.ts'
import './PlanBlock.css'
import './Pages.css'
import './FuelPlan.css'
import { Board } from '../components/layout/Board'

const g = (x: number) => String(roundToInt(x))
const when = (zdt: string) => new Date(zdt.replace(/\[.*\]$/, '')).toLocaleString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: zdt.slice(-1) === ']' ? zdt.slice(zdt.indexOf('[') + 1, -1) : undefined })

export default function RaceFuelPage() {
  const { id } = useParams()
  const [d, setD] = useState<RaceFuel | null | undefined>(undefined)
  const [kept, setKept] = useState<StoreResult | null>(null)
  const st = useLoadEffect(async (cur) => {
    const x = await loadRaceFuel(Number(id))
    if (!cur()) return
    setD(x)
    if (x) { const r = await storeEvent(x.event.id, eventSnapshot(x.plan)).catch((e) => ({ state: 'error', changes: [], message: String(e) }) as StoreResult); if (cur()) setKept(r) }
  }, [id])
  const e = d?.event
  return (
    <div className="pg fp" data-testid="race-fuel">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">{e ? e.name.toUpperCase() : 'EVENT FUEL PLAN'}</h2>
          <p className="pg-sub">{d ? `${when(d.start)}${e?.distance_km ? ` · ${Math.round(e.distance_km * 10) / 10} km` : ''}${e?.ascent_m ? ` · +${Math.round(e.ascent_m)} m` : ''}. ` : ''}What to take and when, worked out with the phone's rules. The saved schedule and the plans it stores are the phone's.</p>
        </div>
        <div className="pb-chips"><Link className="pb-chip" to="/plan/races">← RACES</Link></div>
      </header>
      {st.error && <p className="pb-warn">Could not load: {st.error} <button className="pb-chip small" onClick={st.retry}>RETRY</button></p>}
      {st.loading && d === undefined && <p className="state-loading">LOADING…</p>}
      {d === null && <p className="state-empty">NO PLAN: THE EVENT HAS NO TARGET TIME OR EXPECTED DURATION, AND TOO FEW RECENT RUNS TO ESTIMATE ONE.</p>}
      {d && <Plan d={d} kept={kept} />}
    </div>
  )
}

function Plan({ d, kept }: { d: RaceFuel; kept: StoreResult | null }) {
  const p = d.plan
  return (
    <>
      <ScheduleEditor title={d.event.name} kind="event" refKey={String(d.event.id)} durationMin={p.durationMin} seed={async () => seedBlocks(p)} />
      <section className="pg-card wide">
        <h3 className="pg-card-title mono"><span>THE GENERATED PLAN</span><span>{fmtMin(p.durationMin)} · LIKELY {fmtMin(p.durationLowMin)}–{fmtMin(p.durationHighMin)}</span></h3>
        <div className="fp-nums">
          <Num label="CARBOHYDRATE" value={p.carbsPerHourHigh > 0 ? `${p.carbsPerHourLow > 0 ? `${g(p.carbsPerHourLow)}–` : ''}${g(p.carbsPerHourHigh)}` : '–'} unit="g/h" sub={`${g(p.totalCarbsG)} G IN ALL`} color="var(--m-carbs)" />
          <Num label="FLUID" value={p.fluidMlPerHourHigh > 0 ? `${g(p.fluidMlPerHourLow)}–${g(p.fluidMlPerHourHigh)}` : 'to thirst'} unit={p.fluidMlPerHourHigh > 0 ? 'ml/h' : ''} sub={`${g(p.totalFluidMl)} ML IN ALL`} color="var(--m-hydration)" />
          <Num label="SODIUM" value={p.sodiumMgPerL > 0 ? g(p.sodiumMgPerL) : '–'} unit={p.sodiumMgPerL > 0 ? 'mg/L' : ''} sub={`${g(p.totalSodiumMg)} MG IN ALL`} color="var(--color-text-primary)" />
          <Num label="EXPECTED LOSS" value={p.expectedLossPct.toFixed(1)} unit="%" sub={`OF BODY WEIGHT · SWEAT ~${g(p.sweat.mlPerHour)} ML/H`} color="var(--color-text-primary)" />
        </div>
        {p.totalCaffeineMg > 0 && <p className="pg-note">Caffeine {g(p.totalCaffeineMg)} mg.</p>}
      </section>

      <Board className="pg-grid">
        <section className="pg-card">
          <h3 className="pg-card-title mono"><span>WEATHER</span><span>{p.weatherSource}{p.heat ? ` · ${p.heat.band.replace('_', ' ')}` : ''}</span></h3>
          {!p.heat && <p className="pg-note">{d.hasPlace || d.atHome ? `No forecast or typical weather could be read for ${d.hasPlace ? 'this place' : 'your home'}.` : 'The event has no place and the account has no Home place, so no weather: a mild 18 °C is assumed. Add the start point on the phone, or mark a place as Home on the phone (Setup › DATA CAPTURE › PLACES).'}</p>}
          {p.heat && d.atHome && <p className="pg-note">The event has no start point, so this is the weather at your Home place.</p>}
          {p.heat && <ul className="fp-why">{p.heat.lines.map((l) => <li key={l}>{l}</li>)}</ul>}
        </section>
        {(p.warnings.length > 0) && (
          <section className="pg-card">
            <h3 className="pg-card-title mono"><span>WATCH FOR</span><span>{p.warnings.length}</span></h3>
            <ul className="fp-why">{p.warnings.map((w) => <li key={w} className="pg-warn">{w}</li>)}</ul>
          </section>
        )}
      </Board>

      <section className="pg-card wide">
        <h3 className="pg-card-title mono"><span>ON THE COURSE</span><span>{p.steps.length} STEPS</span></h3>
        <div className="pg-table-wrap">
          <table className="rf-table"><thead><tr><th>TIME</th><th>KM</th><th>°C</th><th>CARBS</th><th>FLUID</th><th>SODIUM</th><th>TAKE</th></tr></thead>
            <tbody>{p.steps.map((s) => <Step key={s.elapsedMin} s={s} />)}</tbody></table>
        </div>
      </section>

      {p.legs.length > 1 || p.legs[0]?.items && Object.keys(p.legs[0].items).length > 0 ? (
        <section className="pg-card wide">
          <h3 className="pg-card-title mono"><span>WHAT TO CARRY</span><span>{p.legs.length} LEG{p.legs.length === 1 ? '' : 'S'}</span></h3>
          <ul className="pg-rows">{p.legs.map((l) => <Leg key={`${l.from}${l.to}`} l={l} />)}</ul>
        </section>
      ) : null}

      <Board className="pg-grid">
        <Prep title="BEFORE" items={p.before} />
        <Prep title="AFTER" items={p.after} />
      </Board>

      <section className="pg-card wide fp-foot">
        <h3 className="pg-card-title mono"><span>WHAT THE PLAN ASSUMES</span><span>{p.assumptions.length}</span></h3>
        <ul className="fp-why">{p.assumptions.map((a) => <li key={a} className="fp-note">{a}</li>)}</ul>
        {kept && <KeptNote kept={kept} what="this event's plan" />}
        <p className="pg-note">{settingsNote(d)}</p>
      </section>
    </>
  )
}

function Num({ label, value, unit, sub, color }: { label: string; value: string; unit: string; sub: string; color: string }) {
  return (
    <div className="fp-num">
      <span className="fp-num-label mono">{label}</span>
      <span className="fp-num-value" style={{ color }}>{value}<small>{unit}</small></span>
      <span className="fp-num-sub mono">{sub}</span>
    </div>
  )
}

function Step({ s }: { s: FuelStep }) {
  return (
    <tr>
      <td className="mono">{fmtMin(s.elapsedMin)}{s.aidStation && <span className="pg-tag mono" style={{ marginLeft: 6 }}>{s.aidStation.name}</span>}</td>
      <td>{s.km != null ? s.km.toFixed(1) : '–'}</td><td>{s.tempC != null ? g(s.tempC) : '–'}</td>
      <td style={{ color: 'var(--m-carbs)' }}>{g(s.carbsG)} g</td><td>{g(s.fluidMl)} ml</td><td>{g(s.sodiumMg)} mg</td>
      <td>{s.items.filter((i) => !/ml to drink$/.test(i)).join(' · ') || '–'}</td>
    </tr>
  )
}

function Leg({ l }: { l: CarryLeg }) {
  const items = Object.entries(l.items).map(([n, c]) => `${c} × ${n}`).join(', ')
  return (
    <li className="pg-row">
      <span className="pg-row-title">{l.from} → {l.to}</span>
      <span className="pg-row-value">{fmtMin(l.minutes)} · {g(l.carbsG)} g · {l.bottlesMl} ml</span>
      <span className="pg-row-sub">{items || 'nothing to take'}{l.note ? `. ${l.note}` : ''}</span>
    </li>
  )
}

function Prep({ title, items }: { title: string; items: PrepItem[] }) {
  return (
    <section className="pg-card">
      <h3 className="pg-card-title mono"><span>{title}</span><span>{items.length}</span></h3>
      <ul className="pg-rows">{items.map((i) => <li key={i.whenLabel} className="pg-row"><span className="pg-row-title mono">{i.whenLabel}</span><span className="pg-row-sub">{i.text}</span></li>)}</ul>
    </section>
  )
}

function settingsNote(d: RaceFuel): string {
  const s = d.settings
  const what = `The carbohydrate your gut tolerates (${g(s.toleratedGPerHour)} g an hour), caffeine (${s.caffeineOk ? `on, ${s.caffeineMgPerKg} mg/kg` : 'off'}) and a salty sweat (${s.saltySweater ? 'on' : 'off'})`
  return d.settingsFromPhone
    ? `${what} are your fuel settings from the phone; change them there, with FUEL SETTINGS on the fuel plan.`
    : `${what} are the defaults: the phone has not sent its fuel settings yet. Open the updated app on the phone once and reload.`
}
