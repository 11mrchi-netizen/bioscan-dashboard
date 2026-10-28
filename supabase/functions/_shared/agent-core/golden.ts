// Personal health agent, phase 0: the golden set and how a reply is scored against it.
//
// golden/golden-set.json holds planted cases for the three modes, the symptom triage, the privacy filter and tool-call
// checks. scoreReply is the one scoring rule for both uses: golden.test.ts proves in CI that every reference reply
// passes and every planted bad reply fails for its stated reason, and scripts/agent-golden.ts scores live models with it.

import golden from './golden/golden-set.json' with { type: 'json' }
import { checkReply, type ToolOutput, type ViolationCode } from './guards.ts'
import { triage, type FlagLevel } from './redFlags.ts'
import { resultAllowed, serveRows, type OptInGroup, type ScopeSettings } from './scope.ts'
import { checkCall, toolsFor, type Mode } from './tools.ts'

export interface GoldenCase {
  id: string
  mode: Mode
  journey: 'data' | 'knowledge' | 'advice' | 'symptom' | 'privacy'
  question: string
  context?: Record<string, unknown>
  settings?: { optIn: OptInGroup[] }
  /** Planted tool results by tool name; a live run returns these whatever the arguments. */
  tools: Record<string, unknown>
  expect: {
    calls?: string[]
    include?: string[]
    absent?: string[]
    notOffered?: string[]
    redFlag?: { level: FlagLevel; codes: string[] } | null
  }
  /** A reply that must pass; null when the case ends at the fixed triage text. */
  reference: string | null
  bad: { reply: string; violates: ViolationCode }[]
}

export interface PrivacyCase {
  id: string
  table: string
  settings: { optIn: OptInGroup[] }
  rows: Record<string, unknown>[]
  expect: { refused: boolean; absent?: string[]; keep?: number }
}

export interface CallCase {
  id: string
  mode: Mode
  name: string
  args: unknown
  ok: boolean
}

export const GOLDEN = golden as unknown as { version: number; cases: GoldenCase[]; privacy: PrivacyCase[]; calls: CallCase[] }

export const settingsOf = (c: { settings?: { optIn: OptInGroup[] } }): ScopeSettings => ({ enabled: true, optIn: c.settings?.optIn ?? [] })

export const outputsOf = (c: GoldenCase): ToolOutput[] => Object.entries(c.tools).map(([tool, data]) => ({ tool, data }))

export interface Score {
  pass: boolean
  failures: string[]
  violations: ViolationCode[]
}

/** A reply to a case: the guards, then the case's own expectations. `calls` are the tools the model called (live runs). */
export function scoreReply(c: GoldenCase, reply: string, calls?: string[]): Score {
  const check = checkReply({ reply, question: c.question, context: c.context, outputs: outputsOf(c) })
  const failures = check.violations.map((v) => `${v.code}: ${v.text}`)
  // "isn't shared" says what "not shared" says: expectations are matched with contractions spelt out.
  const lower = reply.toLowerCase().replace(/[’']/g, "'").replace(/n't\b/g, ' not').replace(/\bcan not\b/g, 'cannot')
  for (const s of c.expect.include ?? []) if (!lower.includes(s.toLowerCase())) failures.push(`missing: ${s}`)
  for (const s of c.expect.absent ?? []) if (lower.includes(s.toLowerCase())) failures.push(`leak: ${s}`)
  if (calls) for (const t of c.expect.calls ?? []) if (!calls.includes(t)) failures.push(`missing call: ${t}`)
  return { pass: failures.length === 0, failures, violations: check.violations.map((v) => v.code) }
}

/** The triage a case expects matches what the rules say. */
export function triageMatches(c: GoldenCase): string | null {
  const t = triage(c.question)
  const want = c.expect.redFlag ?? null
  if (!want) return t ? `unexpected red flag ${t.codes.join(', ')}` : null
  if (!t) return `expected red flag ${want.codes.join(', ')}`
  if (t.level !== want.level) return `expected ${want.level}, got ${t.level}`
  if (t.codes.join() !== want.codes.join()) return `expected ${want.codes.join(', ')}, got ${t.codes.join(', ')}`
  return null
}

/** Planted tools exist and are offered in the case's mode and sharing; tools it says must be hidden are. */
export function fixtureProblems(c: GoldenCase): string[] {
  const offered = new Set(toolsFor(c.mode, settingsOf(c)).map((t) => t.name))
  const out: string[] = []
  for (const name of Object.keys(c.tools)) if (!offered.has(name)) out.push(`${name} is not offered in ${c.mode}`)
  for (const name of c.expect.calls ?? []) if (!(name in c.tools)) out.push(`expected call ${name} has no planted result`)
  for (const name of c.expect.notOffered ?? []) if (offered.has(name)) out.push(`${name} should not be offered`)
  return out
}

/** What the model would see of a privacy case's rows. */
export function servePrivacy(p: PrivacyCase): { refused: boolean; rows: Record<string, unknown>[] } {
  const settings = settingsOf(p)
  const rows = p.table === 'analysis_results' ? p.rows.filter((r) => resultAllowed(r as { kind: string; key: string; payload?: unknown }, settings)) : p.rows
  const served = serveRows(p.table, rows, settings)
  return served.ok ? { refused: false, rows: served.rows } : { refused: true, rows: [] }
}

export function callMatches(c: CallCase): boolean {
  return checkCall(c.mode, { enabled: true, optIn: [] }, c.name, c.args).ok === c.ok
}
