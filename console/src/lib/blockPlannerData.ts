// Loading and saving for the web block planner: the same tables and rows the Android planner reads and writes
// (data/TrainingProgramRepository.kt), so a block planned here is the phone's block, with the same planned sessions.

import { supabase } from './supabase'
import {
  endEarlyPreview, type MaxStep, type ModuleChoice, type PlannedLite, type StrengthModuleDef, topPct,
  type DatedSet, type Definition, DefinitionIndex, deriveBlockDomains, type GeneratedBlock, localToday, movementKey, parseDefinition, plusDays,
  prescriptionJson, type RecordedMax, resolveMaxes, type Accessory, accessoriesFrom, accessoriesJson, type GenEquipment, type MaxEntry, DEFAULT_EQUIPMENT,
} from '../../../supabase/functions/_shared/training-core/index.ts'
import { type AccessorySession, accessoryRevisions } from './accessoryRevisions'
import { type MaxRevision, type Regenerable, regenerable, type StoredBlock, type StoredSession } from './blockMaxes'
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

/** A block of any origin: generated by a planner, or imported / recorded (dates only, mirrored in training_cycles). */
export interface ExistingBlock { id: number; name: string; start_date: string; end_date: string; status: string; origin?: string; cycle_id?: number | null }

export async function loadExistingBlocks(): Promise<ExistingBlock[]> {
  const r = await supabase.from('training_blocks').select('id,name,start_date,end_date,status,origin,cycle_id').neq('status', 'abandoned').order('start_date', { ascending: false })
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

// ---- ending a block early (same rows as the phone's TrainingProgramRepository.endBlockEarly) ----

export interface BlockForEnding {
  row: ExistingBlock; sessions: PlannedLite[]; module: StrengthModuleDef | null; choice: ModuleChoice; review: Record<string, unknown> | null
}

export async function loadBlockForEnding(id: number): Promise<BlockForEnding> {
  const b = await supabase.from('training_blocks').select('id,name,start_date,end_date,status,origin,cycle_id,components,choices,definition_snapshot,review').eq('id', id).single()
  if (b.error) throw new Error(`training_blocks: ${b.error.message}`)
  const s = await supabase.from('planned_sessions').select('id,scheduled_date,week_index,status,prescription').eq('block_id', id).neq('status', 'superseded')
  if (s.error) throw new Error(`planned_sessions: ${s.error.message}`)
  const row = b.data as ExistingBlock & { components: unknown; choices: Record<string, unknown> | null; definition_snapshot: { definitions?: Record<string, unknown> } | null; review: Record<string, unknown> | null }
  const strengthKey = (Array.isArray(row.components) ? row.components : []).map((c) => c as { role?: string; ref?: { key?: string } }).find((c) => c.role === 'strength')?.ref?.key ?? null
  let module: StrengthModuleDef | null = null
  if (strengthKey && row.definition_snapshot?.definitions?.[strengthKey]) {
    try { const d = parseDefinition(row.definition_snapshot.definitions[strengthKey]); module = d.kind === 'strength_module' ? d : null } catch { module = null }
  }
  const cluster = (strengthKey ? (row.choices?.clusters as Record<string, Record<string, unknown>> | undefined)?.[strengthKey] : undefined) ?? {}
  const exercises: Record<string, string[]> = {}
  for (const [k, v] of Object.entries(cluster)) if (k !== 'variant' && k !== 'block_length' && Array.isArray(v)) exercises[k] = v.map(String)
  return {
    row: { id: row.id, name: row.name, start_date: row.start_date, end_date: row.end_date, status: row.status, origin: row.origin, cycle_id: row.cycle_id ?? null },
    sessions: ((s.data ?? []) as { id: number; scheduled_date: string; week_index: number; status: string; prescription: unknown }[])
      .map((x) => ({ id: x.id, date: x.scheduled_date, weekIndex: x.week_index, status: x.status, topPct: topPct(x.prescription) })),
    module, choice: { variant: (cluster.variant as string | undefined) ?? null, blockLength: (cluster.block_length as number | undefined) ?? null, exercises, accessories: accessoriesFrom(cluster.accessories) },
    review: row.review,
  }
}

/** Ends the block on [lastDay]: later planned sessions superseded (kept), the block completed, the stop in its review. */
export async function endBlockEarly(b: BlockForEnding, lastDay: string, steps: MaxStep[]): Promise<void> {
  const dropped = endEarlyPreview(b.sessions, lastDay)
  const u = await supabase.from('planned_sessions').update({ status: 'superseded' }).eq('block_id', b.row.id).eq('status', 'planned').gt('scheduled_date', lastDay)
  if (u.error) throw new Error(`planned_sessions: ${u.error.message}`)
  const review = { ...(b.review ?? {}), ended_early: { last_day: lastDay, original_end_date: b.row.end_date, on: localToday(), superseded_sessions: dropped.dropped, maxes_stepped_up: steps.length > 0 } }
  const t = await supabase.from('training_blocks').update({ end_date: lastDay, status: 'completed', review, updated_at: new Date().toISOString() }).eq('id', b.row.id)
  if (t.error) throw new Error(`training_blocks: ${t.error.message}`)
  // An imported or recorded block lives on as a training_cycles row (the phone's CURRENT BLOCK card reads it): it ends too.
  if (b.row.cycle_id != null) {
    const c = await supabase.from('training_cycles').update({ end_date: lastDay }).eq('id', b.row.cycle_id)
    if (c.error) throw new Error(`training_cycles: ${c.error.message}`)
  }
  for (const st of steps) {
    const r = await supabase.from('athlete_maxes').insert({ exercise_key: movementKey(st.exercise), exercise_name: st.exercise, kind: '1rm', value: st.toKg, unit: 'kg', as_of: localToday(), source: 'progression', derivation: { from_kg: st.fromKg, reason: 'block ended early, stepped up by choice' }, block_id: b.row.id })
    if (r.error) throw new Error(`athlete_maxes: ${r.error.message}`)
  }
}

/**
 * Replaces the accessory work of a block under way: its choices, and the accessory lines of its strength sessions still
 * planned from [from] (earlier and confirmed sessions stay as they were; the program's own lines are never touched).
 * Same as the phone's updateBlockAccessories. Returns how many sessions changed.
 */
export async function updateBlockAccessories(b: BlockForEnding, accessories: Record<string, Accessory[]>, from: string, eq: GenEquipment, maxes: (name: string) => MaxEntry | null | undefined): Promise<number> {
  const m = b.module
  if (!m) throw new Error('This block has no strength program to add accessory work to')
  const r = await supabase.from('training_blocks').select('choices').eq('id', b.row.id).single()
  if (r.error) throw new Error(`training_blocks: ${r.error.message}`)
  const choices = ((r.data as { choices: Record<string, unknown> | null }).choices ?? {}) as Record<string, unknown>
  const clusters = (choices.clusters ?? {}) as Record<string, Record<string, unknown>>
  const { accessories: _old, ...cluster } = clusters[m.key] ?? {}
  void _old
  const acc = accessoriesJson(accessories)
  const updated = { ...choices, clusters: { ...clusters, [m.key]: { ...cluster, ...(Object.keys(acc).length > 0 ? { accessories: acc } : {}) } } }
  const t = await supabase.from('training_blocks').update({ choices: updated, updated_at: new Date().toISOString() }).eq('id', b.row.id)
  if (t.error) throw new Error(`training_blocks: ${t.error.message}`)

  const s = await supabase.from('planned_sessions').select('id,title,work_kind,prescription')
    .eq('block_id', b.row.id).eq('status', 'planned').is('exercise_session_id', null).gte('scheduled_date', from).eq('module_ref', `strength:${m.key}`)
  if (s.error) throw new Error(`planned_sessions: ${s.error.message}`)
  const byTitle = new Map(m.sessions.map((x) => [`${m.title}: ${x.label}`, x.id]))
  const revisions = accessoryRevisions((s.data ?? []) as AccessorySession[], (t) => byTitle.get(t), accessories, eq, maxes)
  // A prescription cannot be updated in place (the trigger planned_sessions_immutable refuses it): each change is a new revision, the old row set aside.
  for (const rev of revisions) {
    const u = await supabase.rpc('revise_planned_session', { p_id: rev.id, p_today: from, p_prescription: rev.after })
    if (u.error) {
      if (u.error.code === 'PGRST202') throw new Error('Changing a session needs the one-time plan-edit database update (revise_planned_session)')
      throw new Error(`planned_sessions: ${u.error.message}`)
    }
  }
  return revisions.length
}

// ---- changing the maxes of a planned block (lib/blockMaxes.ts) ----

export interface BlockMaxesData { name: string; weightedBase: string | null; block: StoredBlock; sessions: StoredSession[]; regen: Regenerable | string }

export async function loadBlockMaxes(id: number, cat: PlannerCatalog): Promise<BlockMaxesData> {
  const b = await supabase.from('training_blocks').select('name,start_date,template_key,components,choices,definition_snapshot').eq('id', id).single()
  if (b.error) throw new Error(`training_blocks: ${b.error.message}`)
  const s = await supabase.from('planned_sessions').select('id,sequence_no,title,status,scheduled_date,exercise_session_id,prescription').eq('block_id', id).neq('status', 'superseded').order('sequence_no')
  if (s.error) throw new Error(`planned_sessions: ${s.error.message}`)
  const row = b.data as StoredBlock & { name: string }
  const fallback = {
    equipment: { ...DEFAULT_EQUIPMENT, platesKg: cat.settings.plates_kg.length > 0 ? cat.settings.plates_kg : DEFAULT_EQUIPMENT.platesKg, weightedBase: cat.settings.weighted_percent_base === 'total' ? 'total' as const : 'added' as const },
    bodyweightKg: cat.bodyweightKg,
  }
  return { name: row.name, weightedBase: (row.choices?.weighted_percent_base as string | undefined) ?? cat.settings.weighted_percent_base, block: row, sessions: (s.data ?? []) as StoredSession[], regen: regenerable(row, fallback) }
}

export interface MaxEdit { name: string; oneRmKg?: number | null; maxReps?: number | null }

/**
 * Records the new maxes (athlete_maxes, source manual, tied to the block) and puts the reworked loads into the block's sessions
 * still to come, each as a new revision. A failure part-way leaves the maxes recorded and the sessions done so far revised:
 * opening MAXES again and applying finishes the rest (the plan is worked out from what is stored).
 */
export async function applyBlockMaxes(blockId: number, edits: Record<string, MaxEdit>, revisions: MaxRevision[], today: string): Promise<number> {
  for (const [key, e] of Object.entries(edits)) {
    const rows = [
      ...(e.oneRmKg != null ? [{ kind: '1rm', value: e.oneRmKg, unit: 'kg' }] : []),
      ...(e.maxReps != null ? [{ kind: 'max_reps', value: e.maxReps, unit: 'reps' }] : []),
    ]
    for (const r of rows) {
      const res = await supabase.from('athlete_maxes').insert({ exercise_key: key, exercise_name: e.name, ...r, as_of: today, source: 'manual', derivation: { entered_in: 'block maxes (web)' }, block_id: blockId })
      if (res.error) throw new Error(`athlete_maxes: ${res.error.message}`)
    }
  }
  let done = 0
  for (const rev of revisions) {
    const u = await supabase.rpc('revise_planned_session', { p_id: rev.id, p_today: today, p_prescription: rev.after })
    if (u.error) {
      if (u.error.code === 'PGRST202') throw new Error('Changing a session needs the one-time plan-edit database update (revise_planned_session)')
      throw new Error(`The maxes are recorded and ${done} of ${revisions.length} sessions are updated; ${rev.title} on ${rev.date} failed (${u.error.message}). Open MAXES again to finish.`)
    }
    done++
  }
  return done
}
