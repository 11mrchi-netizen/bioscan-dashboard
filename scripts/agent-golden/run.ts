// Runs live models against the personal health agent's golden set (docs/personal-health-agent, Evaluation).
//
//   OPENROUTER_API_KEY=... node scripts/agent-golden/run.ts --model <slug> [--model <slug>] [--provider <slug>] \
//       [--only <case id prefix>] [--out <report.json>] [--base-url https://openrouter.ai/api/v1]
//
// Each case runs as one agent turn (agent-core/turn.ts) with the case's planted tool results, so only the model varies.
// A case passes when the reply the person would see passed every guard on the first or second draft and meets the
// case's expectations (scoreReply). Nothing here touches Supabase or the owner's data.

import { writeFileSync } from 'node:fs'
import { GOLDEN, scoreReply, settingsOf, type GoldenCase } from '../../supabase/functions/_shared/agent-core/golden.ts'
import { openAiChat } from '../../supabase/functions/_shared/agent-core/models.ts'
import { runTurn } from '../../supabase/functions/_shared/agent-core/turn.ts'

function args() {
  const a = process.argv.slice(2)
  const all = (flag: string) => a.flatMap((v, i) => (v === flag && a[i + 1] ? [a[i + 1]] : []))
  const one = (flag: string) => all(flag)[0]
  return { models: all('--model'), providers: all('--provider'), only: one('--only'), out: one('--out'), baseUrl: one('--base-url') ?? 'https://openrouter.ai/api/v1' }
}

interface CaseRun {
  id: string
  mode: string
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

async function runCase(c: GoldenCase, model: string, cfg: ReturnType<typeof args>): Promise<CaseRun> {
  const started = Date.now()
  const chat = openAiChat({ baseUrl: cfg.baseUrl, apiKey: process.env.OPENROUTER_API_KEY ?? '', model, providers: cfg.providers })
  try {
    const turn = await runTurn({
      mode: c.mode,
      question: c.question,
      context: c.context,
      settings: settingsOf(c),
      today: '2026-10-28',
      chat,
      executeTool: async (name) => (name in c.tools ? c.tools[name] : { error: 'no data for this request' }),
    })
    const calls = turn.calls.filter((k) => k.ok).map((k) => k.name)
    // Urgent cases end at the fixed text; the score is that the model was not called.
    const urgent = turn.triage && !turn.triage.callModel
    const score = urgent ? { pass: turn.steps === 0, failures: turn.steps ? ['model called on an urgent case'] : [] } : scoreReply(c, turn.draft ?? '', calls)
    return {
      id: c.id,
      mode: c.mode,
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
    return { id: c.id, mode: c.mode, pass: false, failures: ['error'], regenerated: false, refusedCalls: [], calls: [], shown: '', ms: Date.now() - started, tokens: { prompt: 0, completion: 0 }, error: String(e) }
  }
}

async function main() {
  const cfg = args()
  if (!cfg.models.length) throw new Error('pass at least one --model <slug>')
  if (!process.env.OPENROUTER_API_KEY && /openrouter/.test(cfg.baseUrl)) throw new Error('set OPENROUTER_API_KEY')
  const cases = GOLDEN.cases.filter((c) => !cfg.only || c.id.startsWith(cfg.only))
  const report: Record<string, { pass: number; total: number; regenerated: number; refusedCalls: number; tokens: number; runs: CaseRun[] }> = {}
  for (const model of cfg.models) {
    const runs: CaseRun[] = []
    for (const c of cases) {
      const r = await runCase(c, model, cfg)
      runs.push(r)
      console.log(`${model}  ${r.pass ? 'PASS' : 'FAIL'}  ${c.id}${r.failures.length ? '  ' + r.failures.join('; ') : ''}${r.error ? '  ' + r.error : ''}`)
    }
    report[model] = {
      pass: runs.filter((r) => r.pass).length,
      total: runs.length,
      regenerated: runs.filter((r) => r.regenerated).length,
      refusedCalls: runs.reduce((n, r) => n + r.refusedCalls.length, 0),
      tokens: runs.reduce((n, r) => n + r.tokens.prompt + r.tokens.completion, 0),
      runs,
    }
  }
  console.log('\nmodel  pass  regenerated  refused calls  tokens')
  for (const [m, s] of Object.entries(report)) console.log(`${m}  ${s.pass}/${s.total}  ${s.regenerated}  ${s.refusedCalls}  ${s.tokens}`)
  if (cfg.out) writeFileSync(cfg.out, JSON.stringify({ ranAt: new Date().toISOString(), version: GOLDEN.version, report }, null, 2))
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e)
  process.exit(1)
})
