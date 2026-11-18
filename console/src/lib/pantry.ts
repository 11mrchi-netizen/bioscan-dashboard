// The pantry's stock rule, as the phone has it (android domain/SupplementPantry.kt, assessPantry's first half): nothing is
// stored; stock is what was recorded (purchase > 0, discard < 0, adjustment either way) minus the servings the log says were
// taken since the first purchase, never below zero. No purchase recorded = no stock number at all.

export interface StockEvent { supplement_product_id: number; kind: string; servings: number; occurred_at: string; expires_on: string | null }

/** Servings left of each product: recorded events minus the servings taken since its first purchase (SupplementPantry.kt). */
export function remainingByProduct(events: StockEvent[], taken: { productId: number; at: number; servings: number }[]): Map<number, { remaining: number; expires: string | null }> {
  const out = new Map<number, { remaining: number; expires: string | null }>()
  const byProduct = new Map<number, StockEvent[]>()
  for (const e of events) byProduct.set(e.supplement_product_id, [...(byProduct.get(e.supplement_product_id) ?? []), e])
  for (const [pid, evs] of byProduct) {
    const purchases = evs.filter((e) => e.kind === 'purchase').map((e) => Date.parse(e.occurred_at))
    if (purchases.length === 0) continue
    const first = Math.min(...purchases)
    const consumed = taken.filter((t) => t.productId === pid && t.at >= first).reduce((a, t) => a + t.servings, 0)
    const recorded = evs.reduce((a, e) => a + Number(e.servings), 0)
    const expires = evs.map((e) => e.expires_on).filter((x): x is string => !!x).sort()[0] ?? null
    out.set(pid, { remaining: Math.max(0, recorded - consumed), expires })
  }
  return out
}

