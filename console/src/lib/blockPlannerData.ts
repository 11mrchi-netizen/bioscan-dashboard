// Loading and saving for the web block planner: the same tables and rows the Android planner reads and writes
// (data/TrainingProgramRepository.kt), so a block planned here is the phone's block, with the same planned sessions.

import { supabase } from './supabase'
import {
  type DatedSet, type Definition, DefinitionIndex, deriveBlockDomains, type GeneratedBlock, localToday, movementKey, parseDefinition, plusDays,
  prescriptionJson, type RecordedMax, resolveMaxes,
} from '../../../supabase/functions/_shared/training-core/index.ts'
import {
  choicesJson, componentsJson, DEFAULT_SETTINGS, definitionsUsed, type PlannerCatalog, type PlannerDraft, type PlannerEvent, templateOf, type TrainingSettings,
} from './blockPlanner'

interface DefinitionRow { key: string; version: number; kind: string; body: unknown; status: string }

export async function loadPlannerCatalog(): Promise<PlannerCatalog> {
  const today = localToday()
  const since = plusDays(today, -120)
  const [defs, settings, maxes, sessions, events, weight] = await Promise.all([
    supabase.from('training_definitions').select('key,version,kind,body,status').neq('status', 'retired'),
    supabase.from('training_settings').select('plates_kg,default_start_time,default_duration_min,second_start_time,second_duration_min,weighted_percent_base').maybeSingle(),
    supabase.from('athlete_maxes').select('exercise_key,exercise_name,kind,value,as_of,source').order('as_of', { ascending: false }),
    supabase.from('exercise_sessions').select('start_time,details').eq('type', 'strength').gte('start_time', since),
    supabase.from('athlete_events').select('id,name,priority,event_date,target_time_min,expected_duration_min,status').gte('event_date', today).eq('status', 'planned').order('event_date'),
    supabase.from('body_metrics').select('date,weight_kg').not('weight_kg', 'is', null).order('date', { ascending: false }).limit(1),
  ])
  if (defs.error) throw new Error(`training_definitions: ${defs.error.message}`)
  const unreadable: string[] = []
  const parsed: Definition[] = []
  const rawByKeyVersion = new Map<string, unknown>()
  for (const r of (defs.data ?? []) as DefinitionRow[]) {
    try {
      const d = parseDefinition(r.body)
      parsed.push(d)
      rawByKeyVersion.set(`${d.key}@${d.version}`, r.body)
    } catch (e) {
      unreadable.push(`${r.key} v${r.version}: ${e instanceof Error ? e.message : String(e)}`)
    }
  }
  const index = new DefinitionIndex(parsed)
  const raw: Record<string, unknown> = {}
  for (const d of index.all) raw[d.key] = rawByKeyVersion.get(`${d.key}@${d.version}`)

  const recorded: RecordedMax[] = ((maxes.data ?? []) as { exercise_key: string; exercise_name: string; kind: string; value: number; as_of: string; source: string }[])
    .map((r) => ({ movementKey: r.exercise_key, name: r.exercise_name, kind: r.kind, value: Number(r.value), asOf: r.as_of, source: r.source }))
  const names: Record<string, string> = {}
  const logged: Record<string, DatedSet[]> = {}
  for (const s of (sessions.data ?? []) as { start_time: string; details: unknown }[]) {
    // Logged times carry the phone's wall clock (CLAUDE.md): their date digits are the local date.
    const date = String(s.start_time).slice(0, 10)
    const exercises = (s.details as { exercises?: unknown[] } | null)?.exercises
    if (!Array.isArray(exercises)) continue
    for (const ex of exercises as { name?: string; sets?: unknown[] }[]) {
      if (!ex?.name) continue
      const key = movementKey(ex.name)
      names[key] ??= ex.name
      for (const set of (ex.sets ?? []) as { reps?: number; weight_kg?: number; percent_1rm?: number }[]) {
        if (typeof set?.reps !== 'number' || typeof set?.weight_kg !== 'number') continue
        ;(logged[key] ??= []).push({ date, reps: Math.trunc(set.reps), weightKg: set.weight_kg, percentOneRm: set.percent_1rm ?? null })
      }
    }
  }
  for (const r of recorded) names[r.movementKey] ??= r.name

  const evs: PlannerEvent[] = ((events.data ?? []) as { id: number; name: string; priority: string | null; event_date: string; target_time_min: number | null; expected_duration_min: number | null }[])
    .map((e) => ({ id: e.id, name: e.name, priority: e.priority ?? 'B', eventDate: e.event_date, plannedDurationMin: e.target_time_min ?? e.expected_duration_min ?? null }))

  return {
    index, raw, unreadable,
    settings: { ...DEFAULT_SETTINGS, ...((settings.data ?? {}) as Partial<TrainingSettings>) },
    maxes: resolveMaxes(recorded, logged, names, today),
    events: evs,
    bodyweightKg: ((weight.data ?? [])[0] as { weight_kg?: number } | undefined)?.weight_kg ?? null,
  }
}

export interface ExistingBlock { id: number; name: string; start_date: string; end_date: string; status: string }

export async function loadExistingBlocks(): Promise<ExistingBlock[]> {
  const r = await supabase.from('training_blocks').select('id,name,start_date,end_date,status').eq('origin', 'generated').neq('status', 'abandoned').order('start_date', { ascending: false })
  if (r.error) throw new Error(`training_blocks: ${r.error.message}`)
  return (r.data ?? []) as ExistingBlock[]
}

/** Saves this block's equipment and session times as the defaults the phone and the web start from. */
export async function saveSettingsAsDefault(d: PlannerDraft): Promise<void> {
  const r = await supabase.from('training_settings').upsert({
    plates_kg: d.equipment.platesKg, default_start_time: `${d.slotTimes[1].start}:00`, default_duration_min: d.slotTimes[1].durationMin,
    second_start_time: `${d.slotTimes[2].start}:00`, second_duration_min: d.slotTimes[2].durationMin, weighted_percent_base: d.equipment.weightedBase,
    updated_at: new Date().toISOString(),
  }, { onConflict: 'user_id' })
  if (r.error) throw new Error(`training_settings: ${r.error.message}`)
}

const kindName = (d: Definition): string => d.kind

/**
 * Creates the block, its domains and every planned session, after recording the maxes typed for it. A failure part-way
 * removes the block (sessions and domains cascade), so a half-built plan never survives.
 */
export async function createBlock(d: PlannerDraft, cat: PlannerCatalog, block: GeneratedBlock): Promise<number> {
  const today = localToday()
  for (const [key, m] of Object.entries(d.manualMaxes)) {
    const name = cat.maxes[key]?.name ?? key
    const rows = [
      ...(m.oneRmKg != null ? [{ kind: '1rm', value: m.oneRmKg, unit: 'kg' }] : []),
      ...(m.maxReps != null ? [{ kind: 'max_reps', value: m.maxReps, unit: 'reps' }] : []),
    ]
    for (const r of rows) {
      const res = await supabase.from('athlete_maxes').insert({ exercise_key: key, exercise_name: name, ...r, as_of: today, source: 'manual', derivation: { entered_in: 'block setup (web)' } })
      if (res.error) throw new Error(`athlete_maxes: ${res.error.message}`)
    }
  }
  const used = definitionsUsed(d, cat.index, block)
  const template = d.route === 'published' ? templateOf(d, cat.index) : null
  const snapshot = {
    ...(template ? { template: cat.raw[template.key] ?? template } : {}),
    definitions: Object.fromEntries(used.map((x) => [x.key, cat.raw[x.key] ?? x])),
  }
  const ins = await supabase.from('training_blocks').insert({
    name: d.name.trim() || (template?.title ?? 'Strength + conditioning block'),
    template_key: template?.key ?? null, template_version: template?.version ?? null,
    components: componentsJson(d), definition_refs: used.map((x) => ({ kind: kindName(x), key: x.key, version: x.version })),
    definition_snapshot: snapshot, choices: choicesJson(d, cat.index), start_date: d.startDate, end_date: block.endDate,
    status: 'scheduled', origin: 'generated',
  }).select('id').single()
  if (ins.error) throw new Error(`training_blocks: ${ins.error.message}`)
  const blockId = (ins.data as { id: number }).id
  try {
    const domains = deriveBlockDomains(block.sessions).slice(0, 6)
    if (domains.length > 0) {
      const r = await supabase.from('training_block_domains').insert(domains.map((x) => ({ block_id: blockId, domain: x.domain, role: x.role })))
      if (r.error) throw new Error(`training_block_domains: ${r.error.message}`)
    }
    for (let i = 0; i < block.sessions.length; i += 100) {
      const chunk = block.sessions.slice(i, i + 100).map((s) => ({
        block_id: blockId, sequence_no: s.sequenceNo, week_index: s.weekIndex, day_slot: s.daySlot, slot_in_day: s.slotInDay,
        domain: s.domain, work_kind: s.workKind, module_ref: s.moduleRef, title: s.title, prescription: prescriptionJson(s),
        original_date: s.date, scheduled_date: s.date, start_time: s.startTime, duration_min: s.durationMin,
      }))
      const r = await supabase.from('planned_sessions').insert(chunk)
      if (r.error) throw new Error(`planned_sessions: ${r.error.message}`)
    }
  } catch (e) {
    await supabase.from('training_blocks').delete().eq('id', blockId)
    throw e
  }
  return blockId
}
