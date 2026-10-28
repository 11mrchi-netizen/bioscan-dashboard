// Personal health agent, phase 0: the tools each mode may call, their argument schemas, and the tables they read.
//
// Read tools serve published results and records through scope.ts; they never compute statistics (that is
// analysis-run's job). Propose tools return a proposal for the app to show with a confirm button; none writes a row.
// `reads` lists every table a tool may touch, so tools.test.ts can prove each is classified and the mode list can drop a
// tool whose tables the person has not shared.

import seriesFile from '../metric-registry/metric-series.json' with { type: 'json' }
import { tableAccess, type ScopeSettings } from './scope.ts'

export type Mode = 'explain' | 'ask' | 'coach'
export const MODES: Mode[] = ['explain', 'ask', 'coach']

// deno-lint-ignore no-explicit-any
export type Schema = { type: string; [k: string]: any }

export interface ToolDef {
  name: string
  kind: 'read' | 'propose' | 'external'
  description: string
  parameters: Schema
  reads: string[]
  modes: Mode[]
}

export const SERIES_IDS: string[] = (seriesFile as { series: { id: string }[] }).series.map((s) => s.id)
export const SERIES_TABLES: string[] = [...new Set((seriesFile as { series: { table: string }[] }).series.map((s) => s.table))]

/** What `registered_questions` accepts today (20261016_evidence_ledger.sql); tools.test.ts compares with the migration. */
export const REGISTERED_SPACE = {
  exposures: ['alcohol_any', 'caffeine_pm_any', 'late_meal', 'stress_high', 'evening_training', 'training_day'],
  outcomes: ['hrv', 'rhr', 'sleep_hours'],
  lag: { min: 0, max: 3 },
}

export const RESULT_KINDS = [
  'series_break', 'relationship', 'typicality', 'expected_band', 'event_curve', 'case_crossover', 'lag_curve',
  'experiment', 'intervention_response', 'intervention_its', 'fitness_fatigue', 'integrity', 'registered_question',
  'health_overview', 'health_concern_state', 'health_finding', 'health_trial_read',
]

const date = { type: 'string', pattern: '^\\d{4}-\\d{2}-\\d{2}$' }
const all: Mode[] = ['explain', 'ask', 'coach']

export const TOOLS: ToolDef[] = [
  {
    name: 'get_results',
    kind: 'read',
    description: 'Published analysis results of one kind (newest first), each with its state, have/need and display values.',
    parameters: { type: 'object', properties: { kind: { type: 'string', enum: RESULT_KINDS }, key: { type: 'string' }, from: date, to: date }, required: ['kind'] },
    reads: ['analysis_results'],
    modes: all,
  },
  {
    name: 'get_evidence_items',
    kind: 'read',
    description: 'Evidence items on one verdict scale with their class, effect, no-effect zone, readiness and queue place.',
    parameters: { type: 'object', properties: { outcome: { type: 'string' } } },
    reads: ['analysis_results', 'decisions'],
    modes: all,
  },
  {
    name: 'get_decisions',
    kind: 'read',
    description: "The person's recorded decisions with the evidence snapshot each rested on.",
    parameters: { type: 'object', properties: { item_id: { type: 'string' } } },
    reads: ['decisions'],
    modes: all,
  },
  {
    name: 'get_integrity',
    kind: 'read',
    description: "The newest nightly data-quality report: coverage, freshness and issues per series. Read before interpreting a series.",
    parameters: { type: 'object', properties: { series: { type: 'string', enum: SERIES_IDS } } },
    reads: ['analysis_results'],
    modes: all,
  },
  {
    name: 'get_series',
    kind: 'read',
    description: 'Values of one registered series by day, week or month, with sample counts and display forms. No statistics.',
    parameters: {
      type: 'object',
      properties: { series: { type: 'string', enum: SERIES_IDS }, from: date, to: date, grain: { type: 'string', enum: ['day', 'week', 'month'] } },
      required: ['series', 'from', 'to'],
    },
    reads: SERIES_TABLES,
    modes: all,
  },
  {
    name: 'get_metric_info',
    kind: 'read',
    description: "A metric's methodology, units and known limitations from the metric registry.",
    parameters: { type: 'object', properties: { series: { type: 'string', enum: SERIES_IDS } }, required: ['series'] },
    reads: [],
    modes: all,
  },
  {
    name: 'get_context_events',
    kind: 'read',
    description: 'Illness, injuries, rest days, sessions and supplements taken in a date span: the context for a change.',
    parameters: { type: 'object', properties: { from: date, to: date }, required: ['from', 'to'] },
    reads: ['illnesses', 'injuries', 'rest_days', 'exercise_sessions', 'supplement_log', 'supplements'],
    modes: all,
  },
  {
    name: 'get_labs',
    kind: 'read',
    description: 'Blood test draws and markers with their reference ranges as the lab printed them.',
    parameters: { type: 'object', properties: { marker: { type: 'string' }, from: date } },
    reads: ['lab_draws', 'lab_results'],
    modes: ['ask', 'coach'],
  },
  {
    name: 'get_experiments',
    kind: 'read',
    description: 'Running and finished experiments and interventions with adherence and their published reads.',
    parameters: { type: 'object', properties: { state: { type: 'string', enum: ['running', 'finished', 'all'] } } },
    reads: ['experiments', 'interventions', 'intervention_adherence', 'analysis_results'],
    modes: ['ask', 'coach'],
  },
  {
    name: 'get_health_concerns',
    kind: 'read',
    description: 'Tracked health concerns and their current state (only when the person shared health concerns).',
    parameters: { type: 'object', properties: {} },
    reads: ['health_concerns', 'health_observations', 'analysis_results'],
    modes: ['ask', 'coach'],
  },
  {
    name: 'get_profile',
    kind: 'read',
    description: "Age and sex for reference ranges; nothing else of the profile is served.",
    parameters: { type: 'object', properties: {} },
    reads: ['user_profile'],
    modes: ['ask', 'coach'],
  },
  {
    name: 'get_memory',
    kind: 'read',
    description: "The person's confirmed goals, preferences and obstacles.",
    parameters: { type: 'object', properties: {} },
    reads: ['agent_memory'],
    modes: ['coach'],
  },
  {
    name: 'search_literature',
    kind: 'external',
    description: 'Search PubMed for general evidence. The query leaves the system: general terms only, no personal values or dates.',
    parameters: { type: 'object', properties: { query: { type: 'string', maxLength: 120 } }, required: ['query'] },
    reads: [],
    modes: ['ask'],
  },
  {
    name: 'propose_registered_question',
    kind: 'propose',
    description: 'Propose registering an exposure-outcome question; only days after registration will count.',
    parameters: {
      type: 'object',
      properties: {
        exposure: { type: 'string', enum: REGISTERED_SPACE.exposures },
        outcome: { type: 'string', enum: REGISTERED_SPACE.outcomes },
        lag: { type: 'integer', minimum: REGISTERED_SPACE.lag.min, maximum: REGISTERED_SPACE.lag.max },
        no_effect: { type: 'number', exclusiveMinimum: 0 },
        why: { type: 'string', maxLength: 300 },
      },
      required: ['exposure', 'outcome', 'lag', 'no_effect', 'why'],
    },
    reads: [],
    modes: ['ask'],
  },
  {
    name: 'propose_experiment',
    kind: 'propose',
    description: "Propose an experiment; the app shows analysis-run's preview (smallest detectable effect) before the person starts it.",
    parameters: {
      type: 'object',
      properties: { exposure: { type: 'string' }, outcome: { type: 'string', enum: SERIES_IDS }, weeks: { type: 'integer', minimum: 2, maximum: 12 }, why: { type: 'string', maxLength: 300 } },
      required: ['exposure', 'outcome', 'weeks', 'why'],
    },
    reads: [],
    modes: ['ask', 'coach'],
  },
  {
    name: 'propose_goal',
    kind: 'propose',
    description: 'Propose a goal for the person to confirm, with an optional series to follow and a review date.',
    parameters: {
      type: 'object',
      properties: { text: { type: 'string', maxLength: 200 }, series: { type: 'string', enum: SERIES_IDS }, review_on: date },
      required: ['text'],
    },
    reads: [],
    modes: ['coach'],
  },
  {
    name: 'propose_log',
    kind: 'propose',
    description: "Hand a sentence the person said about what they did to log-agent, which proposes the entries.",
    parameters: { type: 'object', properties: { text: { type: 'string', maxLength: 1000 } }, required: ['text'] },
    reads: [],
    modes: ['coach'],
  },
]

export const TOOL_BY_NAME = new Map(TOOLS.map((t) => [t.name, t]))

/** The tools of a mode whose every table the person shares. */
export function toolsFor(mode: Mode, settings: ScopeSettings): ToolDef[] {
  return TOOLS.filter((t) => t.modes.includes(mode) && t.reads.every((table) => tableAccess(table, settings).ok))
}

/** OpenAI-compatible tool declarations (OpenRouter, vLLM). */
export function toOpenAiTools(tools: ToolDef[]) {
  return tools.map((t) => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.parameters } }))
}

// ---- argument validation (the subset of JSON Schema the declarations use) -------------------------------------------

export function validate(schema: Schema, value: unknown, path = '$'): string[] {
  const errs: string[] = []
  const t = schema.type
  if (t === 'object') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return [`${path}: expected an object`]
    const o = value as Record<string, unknown>
    for (const r of schema.required ?? []) if (o[r] === undefined) errs.push(`${path}.${r}: required`)
    for (const [k, v] of Object.entries(o)) {
      const sub = schema.properties?.[k]
      if (!sub) errs.push(`${path}.${k}: not a parameter`)
      else errs.push(...validate(sub, v, `${path}.${k}`))
    }
    return errs
  }
  if (t === 'string') {
    if (typeof value !== 'string') return [`${path}: expected a string`]
    if (schema.enum && !schema.enum.includes(value)) errs.push(`${path}: not one of ${schema.enum.join(', ')}`)
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) errs.push(`${path}: bad format`)
    if (schema.maxLength !== undefined && value.length > schema.maxLength) errs.push(`${path}: too long`)
    return errs
  }
  if (t === 'number' || t === 'integer') {
    if (typeof value !== 'number' || !Number.isFinite(value)) return [`${path}: expected a number`]
    if (t === 'integer' && !Number.isInteger(value)) errs.push(`${path}: expected an integer`)
    if (schema.minimum !== undefined && value < schema.minimum) errs.push(`${path}: below ${schema.minimum}`)
    if (schema.maximum !== undefined && value > schema.maximum) errs.push(`${path}: above ${schema.maximum}`)
    if (schema.exclusiveMinimum !== undefined && value <= schema.exclusiveMinimum) errs.push(`${path}: must be above ${schema.exclusiveMinimum}`)
    return errs
  }
  if (t === 'boolean') return typeof value === 'boolean' ? [] : [`${path}: expected true or false`]
  return [`${path}: unsupported schema type ${t}`]
}

export type CallCheck = { ok: true } | { ok: false; error: string }

/** A model's tool call: a known tool, allowed in this mode and scope, with valid arguments. */
export function checkCall(mode: Mode, settings: ScopeSettings, name: string, args: unknown): CallCheck {
  const tool = TOOL_BY_NAME.get(name)
  if (!tool) return { ok: false, error: `unknown tool ${name}` }
  if (!toolsFor(mode, settings).includes(tool)) return { ok: false, error: `${name} is not available in ${mode} with the current sharing` }
  const errs = validate(tool.parameters, args)
  if (errs.length) return { ok: false, error: errs.join('; ') }
  if (name === 'search_literature' && !literatureQueryOk((args as { query: string }).query)) {
    return { ok: false, error: 'search_literature: general terms only, no numbers or dates' }
  }
  return { ok: true }
}

/** A literature query leaves the system, so it carries no number, date or personal pronoun ("my HRV of 38"). */
export function literatureQueryOk(query: string): boolean {
  return !/\d/.test(query) && !/\b(my|me|i|mine|our)\b/i.test(query) && query.trim().split(/\s+/).length <= 12
}
