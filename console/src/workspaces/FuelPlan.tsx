// PLAN › FOOD › FUEL PLAN: the day's carbohydrate, protein, fat and energy, and the meals that carry them, worked out here with the phone's own
// rules (supabase/functions/_shared/fuel-core: ported and checked against the phone's results). The training of each day comes from the
// plan, the calendar, events and recordings; the energy from the nutrition goal. Read only: the phone's stored plans (fuel_plans) are its own
// record. Meal times, train-low and the carbohydrate the gut tolerates are the phone's fuel settings, read from the account (lib/fuelSettings.ts).
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useLoadEffect } from '../lib/useLoadEffect'
import { loadFuelDays } from '../lib/fuelDay'
import { storeDay, type StoreResult } from '../lib/fuelPlansStore'
import { isLongNonEvent } from '../lib/fuelScheduleKey'
import { PlannedScheduleEditor } from './ScheduleEditor'
import type { FuelDays } from '../lib/fuelInputs'
import { mealTimesText } from '../lib/fuelSettings'
import { formatGPerKg, type DayFuelPlan, type DayMeal, type PlannedItem, roundToInt } from '../../../supabase/functions/_shared/fuel-core/index.ts'
import './PlanBlock.css'
import './Pages.css'
import './FuelPlan.css'

const dayName = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })
const longDay = (d: string) => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' })
const hhmm = (zdt: string | null) => (zdt ? zdt.slice(11, 16) : 'no set time')
const g = (x: number) => String(roundToInt(x))
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)
const hm = (min: number) => { const m = roundToInt(min); return m >= 60 ? `${Math.trunc(m / 60)} h ${String(m % 60).padStart(2, '0')}` : `${m} min` }
const KIND: Record<string, string> = { BREAKFAST: 'BREAKFAST', LUNCH: 'LUNCH', SNACK: 'SNACK', DINNER: 'DINNER', PRE_SESSION: 'PRE-SESSION', RECOVERY: 'RECOVERY' }
const SOURCE: Record<string, string> = { EVENT: 'EVENT', PLANNED_SESSION: 'PLAN', CALENDAR: 'CALENDAR', RECORDED: 'RECORDED' }

export default function FuelPlan() {
  const [params, setParams] = useSearchParams()
  const [data, setData] = useState<FuelDays | null | undefined>(undefined)
  const [kept, setKept] = useState<StoreResult | null>(null)
  const status = useLoadEffect(async (cur) => {
    const d = await loadFuelDays()
    if (!cur()) return
    setData(d)
    // keep today's plan for the record, as the phone does (fuel_plans), without competing with the phone's own rows
    if (d) { const r = await storeDay(d.today, d.snapshot).catch((e) => ({ state: 'error', changes: [], message: String(e) }) as StoreResult); if (cur()) setKept(r) }
  }, [])
  const horizon = useMemo(() => (data ? data.days.map((_, i) => i).filter((i) => i >= data.todayIdx) : []), [data])
  const picked = data ? data.days.findIndex((d) => d.date === params.get('day')) : -1
  const idx = data ? (picked >= data.todayIdx ? picked : data.todayIdx) : -1
  const open = (i: number) => { if (!data) return; const n = new URLSearchParams(params); n.set('day', data.days[i].date); setParams(n, { replace: true }) }

  return (
    <div className="pg fp">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">FUEL PLAN</h2>
          <p className="pg-sub">What each day asks of you: carbohydrate shaped by the training of the next 72 hours, protein, fat and energy from your goal, and the meals that carry them.</p>
        </div>
        <div className="pb-chips">
          <Link className="pb-chip on" to="/plan/food">FUEL PLAN</Link>
          <Link className="pb-chip" to="/plan/food?view=prep">MEAL PREP →</Link>
        </div>
      </header>
      {status.error && <p className="pb-warn">Could not load: {status.error} <button className="pb-chip small" onClick={status.retry}>RETRY</button></p>}
      {status.loading && data === undefined && <p className="state-loading">LOADING…</p>}
      {data === null && <p className="state-empty">NO WEIGHT ON RECORD: THE PLAN IS PER KILOGRAM. LOG A WEIGHT AND IT APPEARS.</p>}
      {data && idx >= 0 && (
        <>
          <nav className="fp-strip" aria-label="Days">
            {horizon.map((i) => {
              const p = data.plans[i]
              const d = data.days[i]
              return (
                <button key={d.date} className={`fp-day ${i === idx ? 'on' : ''}`} onClick={() => open(i)} aria-pressed={i === idx} data-day={d.date}>
                  <span className="fp-day-name mono">{i === data.todayIdx ? 'TODAY' : dayName(d.date).toUpperCase()}</span>
                  <span className="fp-day-class mono">{p.dayClass}</span>
                  <span className="fp-day-num" style={{ color: 'var(--m-carbs)' }}>{g(p.carbG)} g</span>
                  <span className="fp-day-sub">{d.items.length === 0 ? 'rest' : `${d.items.length} session${d.items.length === 1 ? '' : 's'}`}{d.items.some(isLongNonEvent) ? ' · long' : ''}</span>
                </button>
              )
            })}
          </nav>
          <Day data={data} i={idx} />
          <Energy data={data} kept={kept} />
        </>
      )}
    </div>
  )
}

function Energy({ data, kept }: { data: FuelDays; kept: StoreResult | null }) {
  const e = data.energy
  const basis = e.dailyKcal == null ? 'No calorie goal and not enough in your profile to estimate maintenance: the plan carries carbohydrate and protein only.'
    : `${e.fromGoal ? `Your goal of ${g(e.dailyKcal)} kcal` : `Maintenance of ${g(e.dailyKcal)} kcal`} is the week's average; each day adds its sessions' energy and takes off the week's mean${e.observedMaintenance ? ' (maintenance observed from what you log and your weight trend)' : ''}${e.deficit ? '; the goal is a deficit' : ''}.`
  return (
    <section className="pg-card wide fp-foot">
      <h3 className="pg-card-title mono"><span>HOW IT IS WORKED OUT</span><span>{data.weightKg} KG{data.fatFreeMassKg ? ` · ${g(data.fatFreeMassKg)} KG FAT-FREE` : ''}</span></h3>
      <p className="pg-note">{basis}</p>
      {kept && <KeptNote kept={kept} />}
      <p className="pg-note">{settingsNote(data)}</p>
    </section>
  )
}

function settingsNote(data: FuelDays): string {
  const s = data.settings
  const what = `Meal times (${mealTimesText(s)}), training low before easy sessions (${s.trainLow ? 'on' : 'off'}) and the carbohydrate your gut tolerates (${g(s.toleratedGPerHour)} g an hour)`
  return data.settingsFromPhone
    ? `${what} are your fuel settings from the phone; change them there, with FUEL SETTINGS on the fuel plan.`
    : `${what} are the defaults: the phone has not sent its fuel settings yet. Open the updated app on the phone once and reload.`
}

function Day({ data, i }: { data: FuelDays; i: number }) {
  const plan: DayFuelPlan = data.plans[i]
  const day = data.days[i]
  const coded = data.meals[i]
  const today = i === data.todayIdx
  return (
    <div className="fp-detail" data-testid="fuel-day">
      <section className="pg-card wide">
        <h3 className="pg-card-title mono"><span>{longDay(day.date).toUpperCase()}{today ? ' · TODAY' : ''}</span><span>{plan.dayClass}{plan.loading ? ' · LOADING' : ''}</span></h3>
        <div className="fp-nums">
          <Num label="CARBOHYDRATE" value={g(plan.carbG)} unit="g" sub={formatGPerKg(plan.carbGPerKg)} color="var(--m-carbs)" />
          <Num label="PROTEIN" value={g(plan.proteinG)} unit="g" sub={`${(plan.proteinG / data.weightKg).toFixed(1)} G/KG`} color="var(--m-protein)" />
          <Num label="FAT" value={plan.fatG == null ? '–' : g(plan.fatG)} unit={plan.fatG == null ? '' : 'g'} sub={plan.fatG == null ? 'no energy target' : `${g((plan.fatG * 9 * 100) / (plan.kcal ?? 1))} % OF ENERGY`} color="var(--m-fat)" />
          <Num label="ENERGY" value={plan.kcal == null ? '–' : g(plan.kcal)} unit={plan.kcal == null ? '' : 'kcal'} sub={plan.sessionKcal ? `${g(plan.sessionKcal)} kcal TRAINING` : 'NO TRAINING'} color="var(--m-calories)" />
        </div>
        <ul className="fp-why">
          {plan.reasons.map((r) => <li key={r}>{r}</li>)}
          {plan.notes.map((r) => <li key={r} className="fp-note">{r}</li>)}
        </ul>
        {plan.energyAvailability != null && <p className="pg-note">Energy left after training: {g(plan.energyAvailability)} kcal per kg of fat-free mass.</p>}
      </section>

      <div className="pg-grid">
        <section className="pg-card">
          <h3 className="pg-card-title mono"><span>TRAINING</span><span>{day.items.length ? hm(day.items.reduce((a, s) => a + s.durationMin, 0)) : 'REST'}</span></h3>
          {day.items.length === 0 && <p className="pg-note">Nothing planned or recorded: a rest day.</p>}
          <ul className="pg-rows">
            {day.items.map((s, k) => <Session key={`${s.sourceId}${k}`} item={s} fuel={coded.sessions.find((w) => w.item.sourceId === s.sourceId)?.fuel} />)}
          </ul>
          {day.reviews.length > 0 && (
            <p className="pg-note pg-warn">{day.reviews.length === 1 ? 'A calendar entry' : `${day.reviews.length} calendar entries`} left out until you say what {day.reviews.length === 1 ? 'it is' : 'they are'} (on the phone): {day.reviews.map((r) => `“${r.title}”`).join(', ')}.</p>
          )}
        </section>

        <section className="pg-card">
          <h3 className="pg-card-title mono"><span>MEALS</span><span>{coded.meals.length}</span></h3>
          <ul className="pg-rows">
            {coded.meals.map((m) => <Meal key={`${m.kind}${m.time}`} meal={m} eaten={today ? data.logged[m.kind] : undefined} />)}
          </ul>
          <p className="pg-note">Carbohydrate in a meal is a range of ±15 %. What is planned during sessions comes off the day's total first.</p>
        </section>
      </div>
      <LongSessions data={data} i={i} />
    </div>
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

function Session({ item, fuel }: { item: PlannedItem; fuel?: { kind: string; gPerHourLow: number; gPerHourHigh: number; note: string } }) {
  const during = fuel && fuel.kind !== 'NONE' ? (fuel.gPerHourHigh > 0 ? `${fuel.gPerHourLow > 0 ? `${g(fuel.gPerHourLow)}–` : 'up to '}${g(fuel.gPerHourHigh)} g/h` : 'a little') : null
  return (
    <li className="pg-row">
      <span className="pg-row-title">{item.title}</span>
      <span className="pg-row-value mono">{hhmm(item.start)} · {hm(item.durationMin)}</span>
      <span className="pg-row-sub">
        <span className="pg-tag mono">{item.intensity}</span> <span className="pg-tag mono">{SOURCE[item.source]}</span>{' '}
        {item.kcal != null && <>{g(item.kcal)} kcal · </>}{item.intensityReason}
        {during && <><br />During: {during}. {fuel?.note}</>}
      </span>
    </li>
  )
}

const ADHERENCE: Record<string, string> = { WITHIN: 'ON PLAN', UNDER: 'UNDER', OVER: 'OVER', NOT_LOGGED: 'NOT LOGGED' }

function Meal({ meal, eaten }: { meal: DayMeal; eaten?: { carbG: number | null; status: string } }) {
  return (
    <li className="pg-row">
      <span className="pg-row-title"><span className="mono">{hhmm(meal.time)}</span> {KIND[meal.kind]} <span className={`fp-code mono ${meal.code}`}>{meal.code}</span></span>
      <span className="pg-row-value">
        <span style={{ color: 'var(--m-carbs)' }}>{g(meal.carbLowG)}–{g(meal.carbHighG)} g</span> ·{' '}
        <span style={{ color: 'var(--m-protein)' }}>{g(meal.proteinG)} g</span>{meal.fatG != null && <> · <span style={{ color: 'var(--m-fat)' }}>{g(meal.fatG)} g</span></>}
      </span>
      <span className="pg-row-sub">{meal.reason}{meal.beforeKeySession && ' Low in fat and fibre.'}
        {eaten && <><br /><span className="pg-tag mono" data-testid="meal-intake">{ADHERENCE[eaten.status]}</span> {eaten.carbG != null ? `${g(eaten.carbG)} g of carbohydrate logged against ${g(meal.carbLowG)}–${g(meal.carbHighG)}.` : 'Nothing logged for it yet.'}</>}
      </span>
    </li>
  )
}

/** What became of today's plan in the record (fuel_plans). */
export function KeptNote({ kept, what = "today's plan" }: { kept: StoreResult; what?: string }) {
  const lines = kept.changes.length ? <> {kept.changes.join(' ')}</> : null
  return (
    <p className="pg-note" data-testid="fuel-kept">
      {kept.state === 'stored' && <>{cap(what)} was kept in the record (fuel_plans), as the phone keeps it.{lines}</>}
      {kept.state === 'unchanged' && <>{cap(what)} is as stored in the record.</>}
      {kept.state === 'phone' && <>The phone's stored plan differs from this one, so nothing was written.{lines || ' (Its settings, such as meal times, may differ from the defaults used here.)'}</>}
      {kept.state === 'error' && <span className="pg-warn">Could not keep {what} in the record: {kept.message}</span>}
    </p>
  )
}

/** The day's long sessions (90 minutes or more, not events): each has a fuel schedule to plan, kept under its plan item as the phone keeps it. */
function LongSessions({ data, i }: { data: FuelDays; i: number }) {
  const long = data.days[i].items.filter(isLongNonEvent)
  const [closed, setClosed] = useState<Record<string, boolean>>({})
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  if (long.length === 0) return null
  return (
    <section className="pg-card wide" data-testid="long-sessions">
      <h3 className="pg-card-title mono"><span>LONG SESSIONS</span><span>{long.length}</span></h3>
      <p className="pg-note">A session of 90 minutes or more is where fuelling gets tried. The plan below is yours to change: start from the generated one, edit what you will take and when, and the phone shows it and checks it against what you took.</p>
      {long.map((it) => (
        <div key={it.sourceId}>
          <p className="pg-row-title">{it.title} <span className="pg-tag mono">{it.recorded ? 'DONE' : 'PLANNED'}</span> <button className="pb-chip small" aria-expanded={!closed[it.sourceId]} onClick={() => setClosed({ ...closed, [it.sourceId]: !closed[it.sourceId] })}>{closed[it.sourceId] ? 'SHOW YOUR FUEL SCHEDULE' : 'HIDE'}</button></p>
          {!closed[it.sourceId] && <PlannedScheduleEditor item={it} weightKg={data.weightKg} zone={zone} />}
        </div>
      ))}
    </section>
  )
}
