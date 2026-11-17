// MEAL PREP: the Sunday prep worked through on a big screen (owner's request, Nov 2026). The week's meal targets are the
// phone's own (fuel_week_plans, written each time the phone works the week out); the prep engine is the phone's, ported
// and parity-tested (supabase/functions/_shared/fuel-core). Picks, recipes, meals eaten out, the cooked containers and the
// breakfast are saved to the same rows the phone reads, so a week prepped here is the phone's week.

import { useEffect, useMemo, useRef, useState } from 'react'
import { useLoadEffect } from '../lib/useLoadEffect'
import { emptyWeek, loadBreakfast, loadPublished, loadWeek, type PrepWeek, type PublishedWeek, saveBreakfast, saveWeek } from '../lib/mealPrepData'
import {
  addDays, BREAKFAST_MAX_SCALE, BREAKFAST_MIN_SCALE, type BreakfastItem, buildRecipes, carbMid, type CodedMeal, type Container, containerFromStored, cookSheet,
  currentPrepWeek, type FoodOption, letterFor, MAX_PICKS_PER_GROUP, type MealSlotKind, picksReady, type Plate, plateCarb, plateFat, plateKcal, plateLabel,
  plateProtein, plateTarget, planContainers, portionLabel, prepChoices, prepDay, PREP_DAYS, PREP_SLOTS, type PrepPicks, type PrepSlot, prepText,
  rawPerCooked, rawWord, type Recipe, recipeName, roundToInt, scaleBreakfast, shoppingList, slotKey, upcomingPrepWeek,
} from '../../../supabase/functions/_shared/fuel-core/index.ts'
import './PlanBlock.css'
import './MealPrep.css'

type Step = 'week' | 'pick' | 'recipes' | 'containers' | 'days' | 'shop' | 'cook' | 'breakfast'
const STEPS: { id: Step; label: string }[] = [
  { id: 'week', label: 'THE WEEK' }, { id: 'pick', label: 'PICK' }, { id: 'recipes', label: 'RECIPES' }, { id: 'containers', label: 'CONTAINERS' },
  { id: 'days', label: 'EACH DAY' }, { id: 'shop', label: 'SHOPPING' }, { id: 'cook', label: 'COOK' }, { id: 'breakfast', label: 'BREAKFAST' },
]

const localToday = () => new Date().toLocaleDateString('sv-SE')
const dayName = (d: string, len: 'short' | 'long' = 'short') => new Date(`${d}T12:00:00`).toLocaleDateString('en-GB', { weekday: len, day: 'numeric', month: 'short' })
const g = (x: number) => String(roundToInt(x))
const isoDow = (d: string) => { const w = new Date(`${d}T12:00:00`).getDay(); return w === 0 ? 7 : w }
const mondayOf = (d: string) => addDays(d, -(isoDow(d) - 1))
const SLOT_KIND: Record<PrepSlot, MealSlotKind> = { LUNCH: 'LUNCH', DINNER: 'DINNER' }
type SaveState = 'saved' | 'saving' | 'error' | 'idle'

export default function MealPrep() {
  const today = localToday()
  const thisWeek = currentPrepWeek(today)
  const nextWeek = upcomingPrepWeek(today)
  // Monday to Friday the week being eaten comes first; at the weekend, the coming one (as on the phone).
  const [weekStart, setWeekStart] = useState(isoDow(today) <= PREP_DAYS ? thisWeek : nextWeek)
  const [published, setPublished] = useState<PublishedWeek | null>(null)
  const [week, setWeekState] = useState<PrepWeek>(emptyWeek(weekStart))
  const [breakfast, setBreakfast] = useState<BreakfastItem[]>([])
  const [step, setStep] = useState<Step>('week')
  const [save, setSave] = useState<{ state: SaveState; message?: string }>({ state: 'idle' })
  const timer = useRef<number | null>(null)

  const status = useLoadEffect(async (isCurrent) => {
    const [p, w, b] = await Promise.all([loadPublished(weekStart), loadWeek(weekStart), loadBreakfast().catch(() => [] as BreakfastItem[])])
    if (!isCurrent()) return
    setPublished(p); setWeekState(w); setBreakfast(b); setSave({ state: 'idle' })
  }, [weekStart])

  useEffect(() => () => { if (timer.current) window.clearTimeout(timer.current) }, [])

  /** Every change is saved shortly after it is made, as on the phone. */
  const setWeek = (f: (w: PrepWeek) => PrepWeek) => {
    setWeekState((w) => {
      const next = f(w)
      if (timer.current) window.clearTimeout(timer.current)
      setSave({ state: 'saving' })
      timer.current = window.setTimeout(() => {
        saveWeek(next).then(() => setSave({ state: 'saved' })).catch((e) => setSave({ state: 'error', message: e instanceof Error ? e.message : String(e) }))
      }, 600)
      return next
    })
  }

  const plan = published?.plan ?? null
  const foods = useMemo(() => new Map<string, FoodOption>((plan?.foods ?? []).map((f) => [f.id, f])), [plan])
  const mealFor = (date: string, slot: PrepSlot): CodedMeal | null => plan?.days.find((d) => d.date === date)?.meals.find((m) => m.kind === SLOT_KIND[slot]) ?? null
  const cooked = week.containers != null
  const containers: Container[] = useMemo(() => {
    if (!plan) return []
    if (week.containers) {
      return week.containers.map((s) => { const m = mealFor(s.date, s.slot); return m ? containerFromStored(s, foods, plateTarget(m)) : null }).filter((c): c is Container => !!c)
    }
    return planContainers(week.weekStart, week.recipes, new Set(week.skipped), foods, mealFor)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [plan, week, foods])
  const prepped = useMemo(() => plan ? plan.days.slice(0, PREP_DAYS).map((d) => prepDay(d, breakfast, containers, PREP_SLOTS.filter((s) => week.skipped.includes(slotKey(d.date, s))), foods)) : [], [plan, breakfast, containers, week.skipped, foods])
  const breakfasts: Plate[] = useMemo(() => (!plan || breakfast.length === 0) ? [] : plan.days.map((d) => {
    const m = d.meals.find((x) => x.kind === 'BREAKFAST')
    return m ? scaleBreakfast(breakfast, plateTarget(m), foods) : null
  }).filter((p): p is Plate => !!p), [plan, breakfast, foods])
  const shopping = useMemo(() => shoppingList([...containers.map((c) => c.plate), ...breakfasts]), [containers, breakfasts])

  if (status.loading) return <div className="state-loading">LOADING THE PREP WEEK…</div>
  if (status.error) return <div className="state-error">COULD NOT LOAD: {status.error}<button className="btn-ghost mono" onClick={status.retry}>RETRY</button></div>

  const stale = published && (Date.now() - new Date(published.computedAt).getTime()) > 3 * 86_400_000

  return (
    <div className="pb">
      <nav className="pb-steps" aria-label="Meal prep steps">
        <h2 className="pb-title mono">MEAL PREP</h2>
        <div className="pb-chips mp-weeks">
          {[...new Set([thisWeek, nextWeek])].map((w) => (
            <button key={w} className={`pb-chip ${weekStart === w ? 'on' : ''}`} onClick={() => setWeekStart(w)}>{w === thisWeek ? 'THIS WEEK' : 'NEXT WEEK'}</button>
          ))}
        </div>
        <label className="pb-field"><span className="pb-label mono">WEEK OF</span>
          <input className="pb-input" type="date" value={weekStart} onChange={(e) => e.target.value && setWeekStart(mondayOf(e.target.value))} />
        </label>
        {STEPS.map((s, i) => (
          <button key={s.id} className={`pb-step ${step === s.id ? 'active' : ''}`} onClick={() => setStep(s.id)} disabled={!plan && s.id !== 'breakfast' && s.id !== 'week'}>
            <span className="pb-step-n mono">{i + 1}</span>
            <span className="pb-step-label mono">{s.label}</span>
            <span className="pb-step-sum">{summary(s.id, week, containers, shopping.length, breakfast.length, cooked)}</span>
          </button>
        ))}
        <div className="pb-steps-foot">
          <span className={`mono mp-save ${save.state}`}>{save.state === 'saving' ? 'SAVING…' : save.state === 'saved' ? 'SAVED' : save.state === 'error' ? `NOT SAVED: ${save.message}` : ''}</span>
          {published && <p className="pb-hint">Targets from the phone, worked out {new Date(published.computedAt).toLocaleString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}.</p>}
          {stale && <p className="pb-warn">These targets are a few days old: open Fuel › Plan › Prep on the phone to work the week out again.</p>}
        </div>
      </nav>

      <main className="pb-main">
        {!plan && step !== 'breakfast' ? (
          <section className="pb-section">
            <h3 className="pb-section-title mono">NO TARGETS FOR THIS WEEK YET</h3>
            <p className="pb-info">Meal prep portions each container for its own day's lunch or dinner, from the targets the phone works out
              (training, energy, carbohydrate periodisation). Open <b>Fuel › Plan › Prep</b> on the phone for the week of {dayName(weekStart, 'long')}:
              it publishes the week here, and this page fills in.</p>
            <p className="pb-hint">The breakfast template can be edited without it (BREAKFAST).</p>
          </section>
        ) : (
          <>
            {step === 'week' && plan && <WeekStep plan={plan} week={week} cooked={cooked} setWeek={setWeek} />}
            {step === 'pick' && plan && <PickStep foodsList={plan.foods} week={week} cooked={cooked} setWeek={setWeek} />}
            {step === 'recipes' && <RecipesStep week={week} foods={foods} cooked={cooked} setWeek={setWeek} />}
            {step === 'containers' && <ContainersStep containers={containers} foods={foods} cooked={cooked} />}
            {step === 'days' && <DaysStep prepped={prepped} />}
            {step === 'shop' && <ShopStep shopping={shopping} breakfasts={breakfasts.length} text={prepText(week.weekStart, week.recipes, containers, shopping, foods)} weekStart={week.weekStart} />}
            {step === 'cook' && <CookStep week={week} containers={containers} cooked={cooked} setWeek={setWeek} />}
            {step === 'breakfast' && <BreakfastStep items={breakfast} foodsList={plan?.foods ?? []} plan={published?.plan ?? null} onSave={async (items) => { await saveBreakfast(items); setBreakfast(items) }} />}
          </>
        )}
        <div className="pb-nav">
          {STEPS.findIndex((s) => s.id === step) > 0 && <button className="btn-ghost mono" onClick={() => setStep(STEPS[STEPS.findIndex((s) => s.id === step) - 1].id)}>BACK</button>}
          {step !== 'breakfast' && plan && <button className="pb-primary mono" onClick={() => setStep(STEPS[STEPS.findIndex((s) => s.id === step) + 1].id)}>NEXT</button>}
        </div>
      </main>

      <aside className="pb-preview">
        <div className="pb-pv">
          <h3 className="pb-section-title mono">WEEK OF {dayName(weekStart).toUpperCase()}</h3>
          <div className="pb-tiles">
            <div className="pb-tile"><span className="pb-label mono">STATUS</span><span>{cooked ? 'Cooked' : week.recipes.length ? 'Planned' : 'Not started'}</span></div>
            <div className="pb-tile"><span className="pb-label mono">CONTAINERS</span><span>{containers.length}</span></div>
            <div className="pb-tile"><span className="pb-label mono">EATEN OUT</span><span>{week.skipped.length}</span></div>
            <div className="pb-tile"><span className="pb-label mono">TO BUY</span><span>{shopping.length} items</span></div>
          </div>
          {week.recipes.map((r) => (
            <div key={r.letter} className="mp-recipe-line">
              <b className="mono">{r.letter}</b> <span className="pb-muted mono">{r.slot}</span> {recipeName(r, foods) || '—'}
            </div>
          ))}
          {containers.length > 0 && (
            <div className="pb-domains">
              <span><b className="mono">WEEK</b> C {g(containers.reduce((a, c) => a + plateCarb(c.plate), 0))} g · P {g(containers.reduce((a, c) => a + plateProtein(c.plate), 0))} g · F {g(containers.reduce((a, c) => a + plateFat(c.plate), 0))} g in the containers</span>
              <span><b className="mono">OFF</b> {containers.filter((c) => c.plate.gaps.length > 0).length} container(s) outside a target band</span>
            </div>
          )}
        </div>
      </aside>
    </div>
  )
}

function summary(s: Step, w: PrepWeek, containers: Container[], shop: number, breakfast: number, cooked: boolean): string {
  switch (s) {
    case 'week': return `${w.skipped.length} eaten out`
    case 'pick': return `${w.picks.proteins.length} · ${w.picks.vegetables.length} · ${w.picks.carbs.length}`
    case 'recipes': return w.recipes.length ? `${w.recipes.length} recipes` : 'pick first'
    case 'containers': return `${containers.length}${cooked ? ' · cooked' : ''}`
    case 'days': return 'breakfast and snacks'
    case 'shop': return `${shop} items`
    case 'cook': return cooked ? 'cooked' : 'not yet'
    case 'breakfast': return breakfast ? `${breakfast} foods` : 'not set'
  }
}

type SetWeek = (f: (w: PrepWeek) => PrepWeek) => void

function Section({ title, info, children }: { title: string; info?: string; children: React.ReactNode }) {
  return <section className="pb-section"><h3 className="pb-section-title mono">{title}</h3>{info && <p className="pb-info">{info}</p>}{children}</section>
}

const targetText = (m: CodedMeal | null | undefined) => m ? `C ${g(m.carb_low_g)}–${g(m.carb_high_g)} · P ${g(m.protein_g)}${m.fat_g != null ? ` · F ${g(m.fat_g)}` : ''}` : '—'

function WeekStep({ plan, week, cooked, setWeek }: { plan: NonNullable<PublishedWeek['plan']>; week: PrepWeek; cooked: boolean; setWeek: SetWeek }) {
  return (
    <>
      <Section title="LUNCH AND DINNER, MONDAY TO FRIDAY" info="A-B-A-B-A at lunch and C-D-C-D-C at dinner. Mark a meal eaten out and it gets no container; its targets stay out of the day's sums. Each container is portioned for its own day.">
        <table className="pb-table">
          <thead><tr><th>DAY</th><th>TRAINING</th>{PREP_SLOTS.map((s) => <th key={s}>{s}</th>)}</tr></thead>
          <tbody>
            {plan.days.slice(0, PREP_DAYS).map((d) => (
              <tr key={d.date}>
                <td>{dayName(d.date)}</td>
                <td className="pb-muted">{d.sessions.map((s) => `${s.title}${s.start ? ` ${s.start}` : ''} · ${Math.round(s.duration_min)} min ${s.intensity.toLowerCase()}`).join('; ') || 'rest'}</td>
                {PREP_SLOTS.map((slot) => {
                  const key = slotKey(d.date, slot)
                  const out = week.skipped.includes(key)
                  const meal = d.meals.find((m) => m.kind === slot)
                  return (
                    <td key={slot}>
                      <div className="mp-cell">
                        <span className="mono">{letterFor(week.weekStart, d.date, slot)} · {meal?.time ?? ''}</span>
                        <span className="pb-muted mono">{targetText(meal)}{meal?.before_key_session ? ' · before a key session' : ''}</span>
                        <button className={`pb-chip small ${out ? 'on' : ''}`} disabled={cooked} onClick={() => setWeek((w) => ({ ...w, skipped: out ? w.skipped.filter((x) => x !== key) : [...w.skipped, key] }))}>
                          {out ? 'EATEN OUT' : 'CONTAINER'}
                        </button>
                      </div>
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
        {cooked && <p className="pb-hint">Cooked: the containers are fixed. Undo it in COOK to change the week.</p>}
      </Section>
      <Section title="THE WHOLE WEEK'S MEALS" info="Every meal the phone planned, with its time and targets (carbohydrate range, protein, fat, in grams).">
        <table className="pb-table">
          <thead><tr><th>DAY</th><th>MEALS</th></tr></thead>
          <tbody>
            {plan.days.map((d) => (
              <tr key={d.date}><td>{dayName(d.date)}</td><td className="pb-muted mono">{d.meals.map((m) => `${m.time} ${m.kind.replace('_', '-')} ${targetText(m)}`).join(' · ')}</td></tr>
            ))}
          </tbody>
        </table>
      </Section>
    </>
  )
}

const per100 = (f: FoodOption) => `C ${g(f.carb_per_g * 100)} · P ${g(f.protein_per_g * 100)} · F ${g(f.fat_per_g * 100)} /100 ${f.unit}`

function PickStep({ foodsList, week, cooked, setWeek }: { foodsList: FoodOption[]; week: PrepWeek; cooked: boolean; setWeek: SetWeek }) {
  const choices = useMemo(() => prepChoices(foodsList), [foodsList])
  const [filter, setFilter] = useState('')
  const groups: { key: keyof PrepPicks; label: string; list: FoodOption[] }[] = [
    { key: 'proteins', label: 'PROTEINS', list: choices.proteins }, { key: 'vegetables', label: 'VEGETABLES', list: choices.vegetables }, { key: 'carbs', label: 'CARBOHYDRATES', list: choices.carbs },
  ]
  const toggle = (key: keyof PrepPicks, id: string) => setWeek((w) => {
    const cur = w.picks[key]
    const next = cur.includes(id) ? cur.filter((x) => x !== id) : cur.length < MAX_PICKS_PER_GROUP ? [...cur, id] : cur
    const picks = { ...w.picks, [key]: next }
    // New picks rebuild the four recipes, as on the phone.
    return { ...w, picks, recipes: picksReady(picks) ? buildRecipes(picks) : [] }
  })
  return (
    <Section title="UP TO FIVE OF EACH" info="Your usual foods first (·). Changing the picks rebuilds the four recipes.">
      <input className="pb-input" placeholder="Filter foods" value={filter} onChange={(e) => setFilter(e.target.value)} style={{ maxWidth: 280 }} />
      <div className="mp-pick">
        {groups.map((gr) => (
          <div key={gr.key} className="mp-pick-col">
            <span className="pb-label mono">{gr.label} · {week.picks[gr.key].length}/{MAX_PICKS_PER_GROUP}</span>
            {gr.list.filter((f) => f.name.toLowerCase().includes(filter.toLowerCase())).map((f) => {
              const on = week.picks[gr.key].includes(f.id)
              const full = !on && week.picks[gr.key].length >= MAX_PICKS_PER_GROUP
              return (
                <button key={f.id} className={`mp-food ${on ? 'on' : ''}`} disabled={cooked || full} onClick={() => toggle(gr.key, f.id)}>
                  <span>{f.name}{f.yours || Object.values(f.uses).some((n) => (n ?? 0) > 0) ? ' ·' : ''}</span>
                  <span className="pb-muted mono">{per100(f)}</span>
                </button>
              )
            })}
          </div>
        ))}
      </div>
    </Section>
  )
}

function RecipesStep({ week, foods, cooked, setWeek }: { week: PrepWeek; foods: Map<string, FoodOption>; cooked: boolean; setWeek: SetWeek }) {
  if (week.recipes.length === 0) return <p className="state-empty">PICK AT LEAST ONE PROTEIN, VEGETABLE AND CARBOHYDRATE FIRST.</p>
  const update = (letter: string, f: (r: Recipe) => Recipe) => setWeek((w) => ({ ...w, recipes: w.recipes.map((r) => (r.letter === letter ? f(r) : r)) }))
  const groups: { key: 'proteins' | 'carbs' | 'vegetables'; label: string }[] = [{ key: 'proteins', label: 'PROTEIN' }, { key: 'carbs', label: 'CARBOHYDRATE' }, { key: 'vegetables', label: 'VEGETABLES' }]
  return (
    <Section title="FOUR RECIPES" info="Each recipe takes any of the picked foods: tap to add or take one out. Two proteins or starches share the solved grams. Oil is added where the meal's fat target needs it.">
      <div className="mp-recipes">
        {week.recipes.map((r) => (
          <div key={r.letter} className="pb-card">
            <span className="pb-card-title">{r.letter} · {r.slot}</span>
            <span className="pb-card-note">{recipeName(r, foods)}</span>
            {groups.map((gr) => (
              <div key={gr.key} className="pb-field">
                <span className="pb-label mono">{gr.label}</span>
                <div className="pb-chips">
                  {week.picks[gr.key === 'carbs' ? 'carbs' : gr.key].map((id) => {
                    const on = r[gr.key].includes(id)
                    return <button key={id} className={`pb-chip small ${on ? 'on' : ''}`} disabled={cooked || (on && r[gr.key].length === 1)}
                      onClick={() => update(r.letter, (x) => ({ ...x, [gr.key]: on ? x[gr.key].filter((y) => y !== id) : [...x[gr.key], id] }))}>{foods.get(id)?.name ?? id}</button>
                  })}
                </div>
              </div>
            ))}
            <label className="pb-check"><input type="checkbox" checked={r.oil} disabled={cooked} onChange={(e) => update(r.letter, (x) => ({ ...x, oil: e.target.checked }))} /> Olive oil to reach the fat target</label>
          </div>
        ))}
      </div>
      <button className="btn-ghost mono" disabled={cooked || !picksReady(week.picks)} onClick={() => setWeek((w) => ({ ...w, recipes: buildRecipes(w.picks) }))}>REBUILD FROM THE PICKS</button>
    </Section>
  )
}

function macroLine(p: Plate, c: Container) {
  return `C ${g(plateCarb(p))}/${g(carbMid(c.target))} · P ${g(plateProtein(p))}/${g(c.target.proteinG)}${c.target.fatG != null ? ` · F ${g(plateFat(p))}/${g(c.target.fatG)}` : ''} · ${g(plateKcal(p))} kcal`
}

function ContainersStep({ containers, foods, cooked }: { containers: Container[]; foods: Map<string, FoodOption>; cooked: boolean }) {
  void foods
  if (containers.length === 0) return <p className="state-empty">NO CONTAINERS: BUILD THE RECIPES FIRST.</p>
  return (
    <Section title={cooked ? 'THE CONTAINERS, AS COOKED' : 'THE CONTAINERS'} info="Each portioned for its own day's targets: vegetables fixed at 150 g, starch, protein and oil solved. Numbers: in the box / the target, in grams.">
      <table className="pb-table">
        <thead><tr><th>DAY</th><th>MEAL</th><th>IN THE CONTAINER</th><th>AGAINST THE TARGET</th></tr></thead>
        <tbody>
          {containers.map((c) => (
            <tr key={`${c.date}${c.slot}`}>
              <td>{dayName(c.date)}</td>
              <td className="mono">{c.slot} · {c.letter}</td>
              <td>{c.plate.portions.map((p) => <div key={p.food.id}>{portionLabel(p)}</div>)}</td>
              <td className="mono">{macroLine(c.plate, c)}{c.plate.gaps.length > 0 && <div className="pb-warn">{c.plate.gaps.join(' · ')}</div>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Section>
  )
}

function DaysStep({ prepped }: { prepped: ReturnType<typeof prepDay>[] }) {
  return (
    <Section title="EACH DAY" info="Breakfast is scaled (half to 1¾ of its usual amounts) to take up what the containers miss; the snacks get what is left.">
      <table className="pb-table">
        <thead><tr><th>DAY</th><th>BREAKFAST</th><th>LUNCH</th><th>DINNER</th><th>SNACKS LEFT WITH</th></tr></thead>
        <tbody>
          {prepped.map((d) => (
            <tr key={d.date}>
              <td>{dayName(d.date)}</td>
              <td>{d.breakfast ? plateLabel(d.breakfast) : <span className="pb-muted">no template</span>}</td>
              <td>{d.lunch ? plateLabel(d.lunch.plate) : <span className="pb-muted">{d.skipped.includes('LUNCH') ? 'eaten out' : '—'}</span>}</td>
              <td>{d.dinner ? plateLabel(d.dinner.plate) : <span className="pb-muted">{d.skipped.includes('DINNER') ? 'eaten out' : '—'}</span>}</td>
              <td className="mono">C {g(d.snack.carbG)} · P {g(d.snack.proteinG)}{d.snack.fatG != null ? ` · F ${g(d.snack.fatG)}` : ''}{d.notes.map((n) => <div key={n} className="pb-hint">{n}</div>)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Section>
  )
}

function ShopStep({ shopping, breakfasts, text, weekStart }: { shopping: ReturnType<typeof shoppingList>; breakfasts: number; text: string; weekStart: string }) {
  const key = `ft.mealPrep.bought.${weekStart}`
  const [bought, setBought] = useState<Set<string>>(() => { try { return new Set(JSON.parse(localStorage.getItem(key) ?? '[]')) } catch { return new Set() } })
  const [copied, setCopied] = useState(false)
  const toggle = (id: string) => setBought((b) => {
    const n = new Set(b); if (n.has(id)) n.delete(id); else n.add(id)
    try { localStorage.setItem(key, JSON.stringify([...n])) } catch { /* ticks last this visit */ }
    return n
  })
  return (
    <Section title={`SHOPPING${breakfasts ? ` · WITH ${breakfasts} BREAKFASTS` : ''}`} info="Raw or dry weights (USDA cooking yields), rounded up to what is bought. Ticks are kept in this browser.">
      <ul className="mp-shop">
        {shopping.map((s) => (
          <li key={s.food.id}>
            <label className="pb-check"><input type="checkbox" checked={bought.has(s.food.id)} onChange={() => toggle(s.food.id)} />
              <span className={bought.has(s.food.id) ? 'pb-muted' : ''}>{s.label}</span></label>
            <span className="pb-hint mono">{g(s.eatenG)} g eaten{rawPerCooked(s.food.id) !== 1 ? ` · ${g(s.rawG)} g ${rawWord(s.food)}` : ''}</span>
          </li>
        ))}
      </ul>
      <div className="pb-row">
        <button className="btn-ghost mono" onClick={async () => { try { await navigator.clipboard.writeText(text); setCopied(true) } catch { setCopied(false) } }}>{copied ? 'COPIED' : 'COPY THE PREP LIST'}</button>
        <button className="btn-ghost mono" onClick={() => {
          const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' })); a.download = `meal-prep-${weekStart}.txt`; a.click(); URL.revokeObjectURL(a.href)
        }}>DOWNLOAD AS TEXT</button>
      </div>
    </Section>
  )
}

function CookStep({ week, containers, cooked, setWeek }: { week: PrepWeek; containers: Container[]; cooked: boolean; setWeek: SetWeek }) {
  const sheet = cookSheet(week.recipes, containers)
  const [confirm, setConfirm] = useState(false)
  return (
    <Section title={cooked ? 'COOKED · CONTAINERS FIXED' : 'COOK'} info="Per recipe and food: how much to cook (raw and cooked) and how to split it across the recipe's containers. MARK AS COOKED fixes the containers: if training changes, breakfast and the snack take up the difference.">
      <table className="pb-table">
        <thead><tr><th>RECIPE</th><th>FOOD</th><th>RAW / DRY</th><th>COOKED</th><th>SPLIT ACROSS THE CONTAINERS</th></tr></thead>
        <tbody>
          {sheet.map((s, i) => (
            <tr key={i}>
              <td className="mono">{s.recipe.letter}</td><td>{s.food.name}</td>
              <td className="mono">{rawPerCooked(s.food.id) !== 1 ? `${g(s.rawG)} g` : '—'}</td><td className="mono">{g(s.cookedG)} g</td>
              <td className="mono">{s.split.map((x) => `${dayName(x.container.date).split(' ')[0]} ${g(x.grams)}`).join(' · ')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {cooked ? (
        confirm ? (
          <div className="pb-row"><span className="pb-warn">Work the containers out again from the current plan instead of what was cooked?</span>
            <button className="pb-primary mono" onClick={() => { setConfirm(false); setWeek((w) => ({ ...w, containers: null })) }}>RE-PORTION</button>
            <button className="btn-ghost mono" onClick={() => setConfirm(false)}>KEEP</button></div>
        ) : <button className="btn-ghost mono" onClick={() => setConfirm(true)}>NOT COOKED YET</button>
      ) : (
        <button className="pb-primary mono" disabled={containers.length === 0} onClick={() => setWeek((w) => ({
          ...w, containers: containers.map((c) => ({ date: c.date, slot: c.slot, letter: c.letter, grams: Object.fromEntries(c.plate.portions.map((p) => [p.food.id, p.grams])) })),
        }))}>MARK AS COOKED</button>
      )}
    </Section>
  )
}

function BreakfastStep({ items, foodsList, plan, onSave }: { items: BreakfastItem[]; foodsList: FoodOption[]; plan: PublishedWeek['plan'] | null; onSave: (i: BreakfastItem[]) => Promise<void> }) {
  const [draft, setDraft] = useState<BreakfastItem[]>(items)
  const [msg, setMsg] = useState<string | null>(null)
  useEffect(() => setDraft(items), [items])
  const foods = new Map(foodsList.map((f) => [f.id, f]))
  const candidates = foodsList.filter((f) => f.slots.includes('BREAKFAST') && !draft.some((d) => d.foodId === f.id))
  const preview = plan ? plan.days.map((d) => {
    const m = d.meals.find((x) => x.kind === 'BREAKFAST')
    return { date: d.date, plate: m ? scaleBreakfast(draft, plateTarget(m), foods) : null, meal: m }
  }) : []
  return (
    <Section title="THE FIXED BREAKFAST" info={`The same foods every morning, each scaled between ${BREAKFAST_MIN_SCALE}× and ${BREAKFAST_MAX_SCALE}× its usual amount to fit the day. Up to eight foods.`}>
      {foodsList.length === 0 && <p className="pb-hint">The food list comes with the week's targets: with none published yet, existing foods can be changed in amount only.</p>}
      <table className="pb-table">
        <thead><tr><th>FOOD</th><th>USUAL AMOUNT</th><th /></tr></thead>
        <tbody>
          {draft.map((d, i) => (
            <tr key={d.foodId}>
              <td>{foods.get(d.foodId)?.name ?? d.foodId}</td>
              <td><input className="pb-input" type="number" style={{ width: 90 }} min={1} value={d.grams} onChange={(e) => setDraft((x) => x.map((y, j) => (j === i ? { ...y, grams: Number(e.target.value) } : y)))} /> <span className="pb-unit">{foods.get(d.foodId)?.unit ?? 'g'}</span></td>
              <td><button className="pb-chip small" onClick={() => setDraft((x) => x.filter((_, j) => j !== i))}>REMOVE</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {draft.length < 8 && candidates.length > 0 && (
        <select className="pb-input" value="" style={{ maxWidth: 320 }} onChange={(e) => { const f = foods.get(e.target.value); if (f) setDraft((x) => [...x, { foodId: f.id, grams: f.typical_g }]) }}>
          <option value="">Add a food…</option>
          {candidates.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
        </select>
      )}
      <div className="pb-row">
        <button className="pb-primary mono" disabled={draft.length === 0 || draft.some((d) => !(d.grams > 0))} onClick={async () => {
          try { await onSave(draft); setMsg('Saved: the phone uses it from now on.') } catch (e) { setMsg(e instanceof Error ? e.message : String(e)) }
        }}>SAVE THE BREAKFAST</button>
        {msg && <span className="pb-hint">{msg}</span>}
      </div>
      {preview.length > 0 && draft.length > 0 && (
        <table className="pb-table">
          <thead><tr><th>DAY</th><th>TARGET</th><th>SCALED</th></tr></thead>
          <tbody>
            {preview.map((p) => (
              <tr key={p.date}><td>{dayName(p.date)}</td><td className="mono pb-muted">{targetText(p.meal)}</td><td>{p.plate ? plateLabel(p.plate) : '—'}{p.plate && p.plate.gaps.length > 0 && <div className="pb-warn">{p.plate.gaps.join(' · ')}</div>}</td></tr>
            ))}
          </tbody>
        </table>
      )}
    </Section>
  )
}
