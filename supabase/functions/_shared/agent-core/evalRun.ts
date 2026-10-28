// Personal health agent, phase 0: one golden case run as a live agent turn. Shared by scripts/agent-golden/run.ts (Node)
// and the agent-eval Edge Function (Deno), so both score the same way.
//
// Each case runs with its planted tool results, so only the model varies. A case passes when the model's last draft
// (after at most one regeneration, before cleaning) passes every guard and the case's expectations; an urgent case passes
// when the model was not called at all.

import { scoreReply, settingsOf, type GoldenCase } from './golden.ts'
import { openAiChat, type ModelConfig } from './models.ts'
import { runTurn } from './turn.ts'

export interface CaseRun {
  id: string
  mode: string
  model: string
  pass: boolean
  failures: string[]
  regenerated: boolean
  refusedCalls: string[]
  calls: string[]
  shown: string
  ms: number
  tokens: { prompt: number; completion: number }
  error?: string
}

/** OpenRouter's free endpoints log prompts, so they are allowed only for this synthetic set and only by name. */
export const isFreeSlug = (model: string) => model.endsWith(':free')

export async function runCase(c: GoldenCase, cfg: ModelConfig, today = '2026-10-28'): Promise<CaseRun> {
  const started = Date.now()
  const base = { id: c.id, mode: c.mode, model: cfg.model }
  try {
    const turn = await runTurn({
      mode: c.mode,
      question: c.question,
      context: c.context,
      settings: settingsOf(c),
      today,
      chat: openAiChat(cfg),
      // A tool the case did not plant answers with an empty result, not an error: models read an error as "not shared"
      // and say so, which would be a false statement about the person's sharing (first live run, 2026-10-28).
      executeTool: async (name) => (name in c.tools ? c.tools[name] : { results: [], note: 'nothing recorded for this request' }),
    })
    const calls = turn.calls.filter((k) => k.ok).map((k) => k.name)
    const urgent = turn.triage !== null && !turn.triage.callModel
    const score = urgent ? { pass: turn.steps === 0, failures: turn.steps ? ['model called on an urgent case'] : [] } : scoreReply(c, turn.draft ?? '', calls)
    return {
      ...base,
      pass: score.pass,
      failures: score.failures,
      regenerated: turn.regenerated,
      refusedCalls: turn.calls.filter((k) => !k.ok).map((k) => `${k.name}: ${k.error}`),
      calls,
      shown: turn.shown,
      ms: Date.now() - started,
      tokens: turn.tokens,
    }
  } catch (e) {
    return { ...base, pass: false, failures: ['error'], regenerated: false, refusedCalls: [], calls: [], shown: '', ms: Date.now() - started, tokens: { prompt: 0, completion: 0 }, error: String(e) }
  }
}

export interface ModelSummary {
  model: string
  pass: number
  total: number
  errors: number
  regenerated: number
  refusedCalls: number
  tokens: number
  medianMs: number
}

export function summarise(runs: CaseRun[]): ModelSummary[] {
  const byModel = new Map<string, CaseRun[]>()
  for (const r of runs) byModel.set(r.model, [...(byModel.get(r.model) ?? []), r])
  return [...byModel].map(([model, rs]) => {
    const ms = rs.map((r) => r.ms).sort((a, b) => a - b)
    return {
      model,
      pass: rs.filter((r) => r.pass).length,
      total: rs.length,
      errors: rs.filter((r) => r.error).length,
      regenerated: rs.filter((r) => r.regenerated).length,
      refusedCalls: rs.reduce((n, r) => n + r.refusedCalls.length, 0),
      tokens: rs.reduce((n, r) => n + r.tokens.prompt + r.tokens.completion, 0),
      medianMs: ms.length ? ms[ms.length >> 1] : 0,
    }
  })
}
