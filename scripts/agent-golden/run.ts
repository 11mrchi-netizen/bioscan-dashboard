// Runs live models against the personal health agent's golden set (docs/personal-health-agent, Evaluation).
//
//   OPENROUTER_API=... node scripts/agent-golden/run.ts --model <slug> [--model <slug>] [--provider <slug>] \
//       [--allow-logged-endpoints] [--only <case id prefix>] [--out <report.json>] [--base-url <url>]
//
// The key is read from OPENROUTER_API (the name of the Supabase secret) or OPENROUTER_API_KEY. Without
// --allow-logged-endpoints every request requires zero data retention, which OpenRouter's free (":free") endpoints do not
// offer; the flag is safe here because the golden set holds planted data only. The same run is available server-side in
// the agent-eval Edge Function, which reads the key from the secret. Nothing here touches Supabase or the owner's data.

import { writeFileSync } from 'node:fs'
import { runCase, summarise, type CaseRun } from '../../supabase/functions/_shared/agent-core/evalRun.ts'
import { GOLDEN } from '../../supabase/functions/_shared/agent-core/golden.ts'

function args() {
  const a = process.argv.slice(2)
  const all = (flag: string) => a.flatMap((v, i) => (v === flag && a[i + 1] ? [a[i + 1]] : []))
  const one = (flag: string) => all(flag)[0]
  return {
    models: all('--model'),
    providers: all('--provider'),
    only: one('--only'),
    out: one('--out'),
    baseUrl: one('--base-url') ?? 'https://openrouter.ai/api/v1',
    allowLogged: a.includes('--allow-logged-endpoints'),
  }
}

async function main() {
  const cfg = args()
  const apiKey = process.env.OPENROUTER_API ?? process.env.OPENROUTER_API_KEY ?? ''
  if (!cfg.models.length) throw new Error('pass at least one --model <slug>')
  if (!apiKey && /openrouter/.test(cfg.baseUrl)) throw new Error('set OPENROUTER_API (or OPENROUTER_API_KEY)')
  const cases = GOLDEN.cases.filter((c) => !cfg.only || c.id.startsWith(cfg.only))
  const runs: CaseRun[] = []
  for (const model of cfg.models) {
    for (const c of cases) {
      const r = await runCase(c, { baseUrl: cfg.baseUrl, apiKey, model, providers: cfg.providers, allowLogged: cfg.allowLogged })
      runs.push(r)
      console.log(`${model}  ${r.pass ? 'PASS' : 'FAIL'}  ${c.id}${r.failures.length ? '  ' + r.failures.join('; ') : ''}${r.error ? '  ' + r.error : ''}`)
    }
  }
  console.log('\nmodel  pass  errors  regenerated  refused calls  tokens  median ms')
  for (const s of summarise(runs)) console.log(`${s.model}  ${s.pass}/${s.total}  ${s.errors}  ${s.regenerated}  ${s.refusedCalls}  ${s.tokens}  ${s.medianMs}`)
  if (cfg.out) writeFileSync(cfg.out, JSON.stringify({ ranAt: new Date().toISOString(), version: GOLDEN.version, summary: summarise(runs), runs }, null, 2))
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e)
  process.exit(1)
})
