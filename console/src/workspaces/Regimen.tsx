// LAB › REGIMEN (web transition plan, phase 4): what is taken, how, and what is left of it. The roster (supplements), the
// pantry's stock per product and the open vials, read-only: the regimen is changed on the phone (Lab › SUPPLEMENTS).
// Stock is the phone's own rule (domain/SupplementPantry.kt): what was recorded (purchases, adjustments, discards) minus
// the servings the log says were taken since the first purchase; with no purchase recorded there is no stock number.
import { useState } from 'react'
import { supabase } from '../lib/supabase'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localToday } from '../lib/dates'
import { remainingByProduct, type StockEvent } from '../lib/pantry'
import './PlanBlock.css'
import './Pages.css'
import { Board } from '../components/layout/Board'

interface Supp {
  id: number; name: string; kind: string | null; status: string | null; dose: string | null; dose_amount: number | null; dose_unit: string | null
  time_of_day: string | null; slots: string[] | null; every_n_days: number | null; start_date: string | null; end_date: string | null
  category: string | null; target: string | null; product_id: number | null; servings_per_dose: number | null; form: string | null; route: string | null
}
interface Product { id: number; name: string; brand: string | null; servings_per_container: number | null; serving_unit: string | null }
interface Taken { supplement_id: number | null; product_id: number | null; taken_at: string; serving_count: number | null }
interface Vial { id: number; supplement_id: number; amount: number | null; amount_unit: string | null; volume_ml: number | null; opened_on: string | null; discard_after_days: number | null; spares: number | null; finished_on: string | null }

interface Data { supps: Supp[]; products: Map<number, Product>; stock: Map<number, { remaining: number; expires: string | null }>; vials: Vial[] }

async function load(): Promise<Data> {
  const [s, p, e, v] = await Promise.all([
    supabase.from('supplements').select('id,name,kind,status,dose,dose_amount,dose_unit,time_of_day,slots,every_n_days,start_date,end_date,category,target,product_id,servings_per_dose,form,route').order('name'),
    supabase.from('supplement_products').select('id,name,brand,servings_per_container,serving_unit'),
    supabase.from('supplement_stock_events').select('supplement_product_id,kind,servings,occurred_at,expires_on'),
    supabase.from('regimen_vials').select('id,supplement_id,amount,amount_unit,volume_ml,opened_on,discard_after_days,spares,finished_on').is('finished_on', null),
  ])
  if (s.error) throw new Error(`supplements: ${s.error.message}`)
  const supps = (s.data ?? []) as Supp[]
  const events = (e.data ?? []) as StockEvent[]
  const firstPurchase = events.filter((x) => x.kind === 'purchase').map((x) => x.occurred_at).sort()[0]
  let taken: Taken[] = []
  if (firstPurchase) {
    const t = await supabase.from('supplement_log').select('supplement_id,product_id,taken_at,serving_count').gte('taken_at', firstPurchase).limit(5000)
    taken = (t.data ?? []) as Taken[]
  }
  const productOf = new Map(supps.filter((x) => x.product_id != null).map((x) => [x.id, x.product_id!]))
  const takenRows = taken.flatMap((t) => {
    const pid = t.product_id ?? (t.supplement_id != null ? productOf.get(t.supplement_id) : undefined)
    return pid == null ? [] : [{ productId: pid, at: Date.parse(t.taken_at), servings: t.serving_count ?? 1 }]
  })
  return {
    supps, products: new Map(((p.data ?? []) as Product[]).map((x) => [x.id, x])),
    stock: remainingByProduct(events, takenRows), vials: (v.data ?? []) as Vial[],
  }
}

const doseText = (s: Supp) => s.dose_amount != null ? `${s.dose_amount}${s.dose_unit ? ` ${s.dose_unit}` : ''}` : (s.dose ?? '')
const whenText = (s: Supp) => [
  s.slots?.length ? s.slots.join(', ').toLowerCase() : s.time_of_day?.toLowerCase(),
  s.every_n_days && s.every_n_days > 1 ? `every ${s.every_n_days} days` : null,
].filter(Boolean).join(' · ')

export default function Regimen() {
  const [d, setD] = useState<Data | null>(null)
  const [showEnded, setShowEnded] = useState(false)
  const st = useLoadEffect(async (cur) => { const x = await load(); if (cur()) setD(x) }, [])
  const today = localToday()
  const isActive = (s: Supp) => s.status === 'active' && (!s.end_date || s.end_date >= today)
  const list = (d?.supps ?? []).filter((s) => showEnded || isActive(s))
  const groups = [['medication', 'MEDICATION'], ['supplement', 'SUPPLEMENTS']] as const

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">REGIMEN AND PANTRY</h2>
          <p className="pg-sub">What you take, when, and how much is left. Changes are made on the phone (Lab › SUPPLEMENTS); a wrongly ticked dose is fixed in the Day log.</p>
        </div>
        <div className="pb-chips"><button className={`pb-chip ${showEnded ? 'on' : ''}`} onClick={() => setShowEnded((v) => !v)}>{showEnded ? 'HIDE ENDED' : 'SHOW ENDED'}</button></div>
      </header>
      {st.error && <p className="pb-warn">Could not load: {st.error}</p>}
      {st.loading && !d && <p className="state-loading">LOADING…</p>}
      {d && (
        <Board className="pg-grid">
          {groups.map(([kind, title]) => {
            const rows = list.filter((s) => (s.kind ?? 'supplement') === kind)
            if (rows.length === 0) return null
            return (
              <section key={kind} className="pg-card">
                <h3 className="pg-card-title mono"><span>{title}</span><span>{rows.length}</span></h3>
                <ul className="pg-rows">{rows.map((s) => {
                  const prod = s.product_id != null ? d.products.get(s.product_id) : undefined
                  const stock = s.product_id != null ? d.stock.get(s.product_id) : undefined
                  const perDose = s.servings_per_dose ?? 1
                  return (
                    <li key={s.id} className="pg-row">
                      <span className="pg-row-title">{s.name}{!isActive(s) && <span className="pg-tag mono">ENDED</span>}</span>
                      <span className="pg-row-value">{doseText(s)}</span>
                      <span className="pg-row-sub">
                        {[whenText(s), s.category, s.target, prod ? `${prod.brand ? `${prod.brand} ` : ''}${prod.name}` : null].filter(Boolean).join(' · ')}
                        {stock && <><br /><span className={stock.remaining / perDose < 7 ? 'pg-warn' : ''}>{Math.floor(stock.remaining)} serving{Math.floor(stock.remaining) === 1 ? '' : 's'} left (about {Math.floor(stock.remaining / perDose)} doses){stock.expires ? ` · expires ${stock.expires}` : ''}</span></>}
                        {prod && !stock && <><br />No purchase recorded, so what is left is unknown.</>}
                      </span>
                    </li>
                  )
                })}</ul>
              </section>
            )
          })}
          {d.vials.length > 0 && (
            <section className="pg-card">
              <h3 className="pg-card-title mono">OPEN VIALS</h3>
              <ul className="pg-rows">{d.vials.map((v) => {
                const s = d.supps.find((x) => x.id === v.supplement_id)
                const discard = v.opened_on && v.discard_after_days ? new Date(new Date(`${v.opened_on}T12:00:00`).getTime() + v.discard_after_days * 86400000).toLocaleDateString('sv-SE') : null
                return (
                  <li key={v.id} className="pg-row">
                    <span className="pg-row-title">{s?.name ?? 'Vial'}</span>
                    <span className="pg-row-value">{v.amount != null ? `${v.amount} ${v.amount_unit ?? ''}` : ''}</span>
                    <span className="pg-row-sub">{[v.opened_on && `opened ${v.opened_on}`, discard && `discard by ${discard}`, v.spares ? `${v.spares} spare${v.spares === 1 ? '' : 's'}` : null].filter(Boolean).join(' · ')}</span>
                  </li>
                )
              })}</ul>
            </section>
          )}
        </Board>
      )}
    </div>
  )
}
