// fuel_plans from the web: a compact copy of today's day plan, or of an event's plan, kept when it changes, as the phone keeps them (FuelPlanRepository.remember):
// one row (kind, ref_key, engine_version, inputs_hash, plan), append only. The hash and the engine version are the phone's (fuel-core/snapshot.ts, checked against
// the phone's stored rows), so a plan the phone and the web agree on is stored once.
//
// The web never competes with the phone for a row. It stores a first copy when none exists, and a changed copy only over a row it wrote itself (marked by.web in the
// plan, which the phone ignores). When the latest row is the phone's and differs, nothing is written: the web says what differs instead. Only the phone knows its own
// settings (meal times, tolerance), so two writers that differ would otherwise store a new row on every alternation.
import { supabase } from './supabase'
import {
  describeDayChanges, describeEventChanges, type DaySnapshot, type EventSnapshot, FUEL_ENGINE_VERSION, snapshotHash,
} from '../../../supabase/functions/_shared/fuel-core/index.ts'

export type StoreState = 'stored' | 'unchanged' | 'phone' | 'error'
export interface StoreResult { state: StoreState; /** what changed since the stored plan, in the phone's words */ changes: string[]; message?: string }

import { decide, type Row } from './fuelPlansDecide'

async function remember<S extends DaySnapshot | EventSnapshot>(kind: 'day' | 'event', ref: string, snapshot: S, describe: (old: S, now: S) => string[]): Promise<StoreResult> {
  const hash = snapshotHash(snapshot)
  const got = await supabase.from('fuel_plans').select('engine_version,inputs_hash,plan').eq('kind', kind).eq('ref_key', ref).order('created_at', { ascending: false }).limit(1)
  if (got.error) return { state: 'error', changes: [], message: got.error.message }
  const last = ((got.data ?? [])[0] as Row | undefined) ?? null
  const choice = decide(last, hash)
  const changes = last && last.engine_version === FUEL_ENGINE_VERSION && choice !== 'unchanged' ? describe(last.plan as unknown as S, snapshot) : []
  if (choice === 'unchanged') return { state: 'unchanged', changes: [] }
  if (choice === 'phone') return { state: 'phone', changes }
  const ins = await supabase.from('fuel_plans').insert({ kind, ref_key: ref, engine_version: FUEL_ENGINE_VERSION, inputs_hash: hash, plan: { ...snapshot, by: 'web' } })
  return ins.error ? { state: 'error', changes: [], message: ins.error.message } : { state: 'stored', changes }
}

export const storeDay = (date: string, s: DaySnapshot) => remember('day', date, s, describeDayChanges)
export const storeEvent = (eventId: number, s: EventSnapshot) => remember('event', String(eventId), s, describeEventChanges)
