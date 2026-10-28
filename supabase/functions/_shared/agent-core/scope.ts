// Personal health agent, phase 0 (docs/personal-health-agent/README.md): what the agent may read.
//
// The scope is enforced in the tool layer, before anything reaches a model request: a table the person has not shared
// is refused, free-text columns are dropped unless notes are shared, and location-like columns are always dropped. A
// table missing from TABLE_SCOPE reads as `never` (fail closed), and scope.test.ts fails on any table in the migrations
// or the code that is not classified here, so a new table cannot fall through.
//
// Classes
//   shared    readable while the assistant is on
//   opt_in    readable only when the person turned on its group in Setup
//   never     not readable at all: routine and location (docs/routine-intelligence rule 3), other people, the calendar
//   internal  system bookkeeping with no health content; never served

import { routineSeries } from '../analysis-core/routineExposures.ts'

export type ScopeClass = 'shared' | 'opt_in' | 'never' | 'internal'
export type OptInGroup = 'notes' | 'sexual_health' | 'health_concerns' | 'digestive'
export const OPT_IN_GROUPS: OptInGroup[] = ['notes', 'sexual_health', 'health_concerns', 'digestive']

export interface ScopeSettings {
  enabled: boolean
  optIn: OptInGroup[]
}

export interface TableScope {
  scope: ScopeClass
  group?: Exclude<OptInGroup, 'notes'>
  /** When set, only these columns are served (user_profile: no date of birth, no home location). */
  columns?: string[]
}

const shared = (...tables: string[]) => Object.fromEntries(tables.map((t) => [t, { scope: 'shared' } as TableScope]))
const optIn = (group: Exclude<OptInGroup, 'notes'>, ...tables: string[]) =>
  Object.fromEntries(tables.map((t) => [t, { scope: 'opt_in', group } as TableScope]))
const never = (...tables: string[]) => Object.fromEntries(tables.map((t) => [t, { scope: 'never' } as TableScope]))
const internal = (...tables: string[]) => Object.fromEntries(tables.map((t) => [t, { scope: 'internal' } as TableScope]))

export const TABLE_SCOPE: Record<string, TableScope> = {
  ...shared(
    // physiology, sleep, body, wellbeing ratings
    'wearable_daily', 'wearable_hr_daily', 'wearable_hr_daily_range', 'sleep_daily', 'wellbeing_daily', 'evening_checkins', 'hydration_daily',
    'hydration_factor_models', 'body_metrics', 'body_measurements', 'body_checkins', 'scale_readings', 'grip_strength_log',
    'illnesses', 'injuries', 'ostrc_checkins', 'rest_days', 'recovery_sessions', 'sweat_tests',
    // training
    'exercise_sessions', 'zepp_workout_detail', 'runs', 'running_form_analyses', 'athlete_events', 'athlete_maxes',
    'exercise_library', 'planned_sessions', 'training_blocks', 'training_block_domains', 'training_cycles',
    'training_definitions', 'training_settings', 'v_training_block_metrics',
    // nutrition, supplements, fuel
    'meals', 'meal_items', 'nutrient_intake', 'foods', 'food_nutrients', 'food_servings', 'food_sources',
    'breakfast_templates', 'meal_prep_weeks', 'fuel_plans', 'fuel_plan_reviews', 'fuel_schedules', 'fueling_intake_log',
    'fueling_products', 'fueling_recipe_ingredients', 'fueling_recipe_versions', 'supplements', 'supplement_log',
    'supplement_products', 'supplement_ingredients', 'supplement_product_ingredients', 'supplement_nutrients',
    'supplement_sources', 'supplement_source_snapshots', 'supplement_stock_events', 'interaction_records',
    // labs
    'lab_draws', 'lab_results',
    // analysis and evidence
    'analysis_results', 'evidence_ledger', 'evidence_records', 'registered_questions', 'experiments',
    'interventions', 'intervention_adherence', 'intervention_state_history', 'protocols', 'protocol_interventions',
    'protocol_state_history', 'protocol_templates', 'protocol_adjustment_proposals', 'decisions', 'series_break_decisions',
    'health_vocab', 'grip_strength_norms',
    // the agent's own memory (written only from confirmed proposals)
    'agent_memory',
  ),
  user_profile: { scope: 'shared', columns: ['sex'] },
  ...optIn('sexual_health', 'encounters', 'sexual_activity_daily', 'arousal_daily', 'masturbation_log'),
  ...optIn('health_concerns', 'health_concerns', 'health_observations', 'health_signals', 'health_episode_marks', 'intervention_concerns'),
  // v_health_events unions injuries, illnesses and health concerns, so it is as private as its most private part.
  ...optIn('health_concerns', 'v_health_events'),
  ...optIn('digestive', 'stool_log'),
  ...never(
    // routine and location
    'location_fixes', 'place_visits', 'places', 'trips', 'mobility_daily', 'activity_transitions', 'phone_usage_daily',
    'phone_app_usage_daily', 'phone_app_categories', 'daily_context', 'planned_routes',
    // other people and the calendar
    'people', 'calendar_events', 'calendar_event_overrides',
    // free text by nature
    'notes',
  ),
  ...internal(
    'sync_log', 'zepp_raw_extracts', 'zepp_history_pull', 'zepp_sync_state', 'calendar_sync_state', 'notification_log',
    'notification_prefs', 'notification_rules', 'log_agent_runs', 'saved_views', 'benchmark_artifacts',
    'collector_heartbeats', 'daily_field_provenance', 'ai_estimates', 'nutrition_backfill_review',
    'exercise_session_tombstones', 'experiment_exclusion_snapshots', 'food_match_memory', 'clothing_items', 'laundry_loads',
    'agent_threads', 'agent_turns', 'agent_hints', 'agent_digests', 'agent_settings',
  ),
}

/** Free-text columns (what the person typed: notes, symptoms, a decision's rationale): served only when notes are shared. */
export const FREE_TEXT_COLUMN = /^(note|notes|comment|comments|text|utterance|description|detail|details|details_text|symptoms|diagnosis|medication|medications|rationale|threshold|review_condition|free_text|rationale_text|journal)$/
/** Location-like columns: never served, whatever the table. */
export const LOCATION_COLUMN = /(^|_)(lat|lon|lng|latitude|longitude|coords?|coordinates|polyline|gps|geo|geom|track|route|address|place)(_|$)/

export type Access = { ok: true; table: string; columns: string[] | null } | { ok: false; table: string; reason: string }

export function tableAccess(table: string, settings: ScopeSettings): Access {
  if (!settings.enabled) return { ok: false, table, reason: 'the assistant is off' }
  const s = TABLE_SCOPE[table]
  if (!s) return { ok: false, table, reason: 'not classified for the assistant' }
  if (s.scope === 'never') return { ok: false, table, reason: 'never shared with the assistant' }
  if (s.scope === 'internal') return { ok: false, table, reason: 'not health data' }
  if (s.scope === 'opt_in' && !settings.optIn.includes(s.group!)) return { ok: false, table, reason: `not shared (${s.group})` }
  return { ok: true, table, columns: s.columns ?? null }
}

/** One row as the model may see it: allowed columns only, no location, free text only when notes are shared. */
export function filterRow(row: Record<string, unknown>, access: Access & { ok: true }, settings: ScopeSettings): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(row)) {
    if (access.columns && !access.columns.includes(k)) continue
    if (LOCATION_COLUMN.test(k)) continue
    if (FREE_TEXT_COLUMN.test(k) && !settings.optIn.includes('notes')) continue
    out[k] = v
  }
  return out
}

export type Served = { ok: true; rows: Record<string, unknown>[] } | { ok: false; reason: string }

/** The only way rows reach a model: through the scope of their table. */
export function serveRows(table: string, rows: Record<string, unknown>[], settings: ScopeSettings): Served {
  const access = tableAccess(table, settings)
  if (!access.ok) return { ok: false, reason: access.reason }
  return { ok: true, rows: rows.map((r) => filterRow(r, access, settings)) }
}

// ---- results derived from never-shared streams -----------------------------------------------------------------------

/** Every series the routine pipeline produces, read from the engine itself so a new one is covered without an edit here. */
export const ROUTINE_SERIES: string[] = Object.keys(routineSeries([], {}, () => ({ date: '', hour: 0 })))

// Result kinds about health concerns (docs/health-concerns), served only with that group.
const CONCERN_KINDS = new Set(['health_overview', 'health_concern_state', 'health_finding', 'health_trial_read'])

/** Result keys are like 'hrv', 'alcohol_any->hrv@1' or 'exp:12'; a key naming a routine series is routine data. */
export function resultAllowed(row: { kind: string; key: string; payload?: unknown }, settings: ScopeSettings): boolean {
  if (!tableAccess('analysis_results', settings).ok) return false
  if (CONCERN_KINDS.has(row.kind) && !settings.optIn.includes('health_concerns')) return false
  const tokens = new Set(row.key.split(/[^a-z0-9_]+/))
  if (ROUTINE_SERIES.some((s) => tokens.has(s))) return false
  const p = row.payload as Record<string, unknown> | undefined
  for (const field of ['exposure', 'metric', 'outcome', 'adherence_series']) {
    const v = p?.[field]
    if (typeof v === 'string' && ROUTINE_SERIES.includes(v)) return false
  }
  return true
}
