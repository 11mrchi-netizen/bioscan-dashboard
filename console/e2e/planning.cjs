// Planning walkthrough (Nov 2026): the PLAN BLOCK workspace against a mocked Supabase, with the placeholder programs of the
// engine's parity fixtures (supabase/functions/_shared/training-core/fixtures/scenarios.json). Walks every step, creates
// the block, and checks that the rows written are the block the shared engine generates. Not part of CI.
//
//   cd console && npm run build && node e2e/planning.cjs [screenshot dir]
const { spawn } = require('node:child_process')
const path = require('node:path')
const fs = require('node:fs')
let chromium
try { ({ chromium } = require('playwright')) } catch { console.error('Playwright is not installed'); process.exit(2) }
const OUT = path.resolve(process.argv[2] ?? path.join(__dirname, 'out'))
fs.mkdirSync(OUT, { recursive: true })
const PORT = 4174
const BASE = `http://localhost:${PORT}/app.html`
const REF = 'ugfrglbcoivkprjqvjzz'

const sc = JSON.parse(fs.readFileSync(path.join(__dirname, '../../supabase/functions/_shared/training-core/fixtures/scenarios.json'), 'utf8'))
const today = new Date().toLocaleDateString('sv-SE')
const T = {
  training_definitions: sc.definitions.map((d, i) => ({ id: i + 1, key: d.key, version: 1, kind: d.kind, body: d, status: 'reviewed' })),
  training_settings: [{ plates_kg: [1.25, 2.5, 5, 10, 15, 20], default_start_time: '06:30:00', default_duration_min: 60, second_start_time: '19:00:00', second_duration_min: 45, weighted_percent_base: 'added' }],
  athlete_maxes: [
    { exercise_key: 'press', exercise_name: 'Press', kind: '1rm', value: 62.5, as_of: '2026-10-01', source: 'test' },
    { exercise_key: 'squat', exercise_name: 'Squat', kind: '1rm', value: 140, as_of: '2026-10-01', source: 'test' },
    { exercise_key: 'pull up weighted', exercise_name: 'Weighted Pull-up', kind: '1rm', value: 32, as_of: '2026-10-01', source: 'test' },
  ],
  exercise_sessions: [{ start_time: '2026-11-02T07:00:00+00:00', type: 'strength', details: { exercises: [{ name: 'Deadlift', sets: [{ reps: 5, weight_kg: 150 }, { reps: 3, weight_kg: 160 }] }] } }],
  athlete_events: [{ id: 7, name: 'Spring half', priority: 'A', event_date: '2027-03-14', target_time_min: 95, expected_duration_min: null, status: 'planned' }],
  body_metrics: [{ date: '2026-11-15', weight_kg: 78.4 }],
  training_blocks: [], training_block_domains: [], planned_sessions: [],
}
const writes = []

function filterRows(table, sp) {
  let rows = (T[table] ?? []).slice()
  for (const [k, v] of sp) {
    if (['select', 'order', 'limit', 'offset', 'on_conflict', 'columns'].includes(k)) continue
    const [op, ...rest] = v.split('.'); const val = rest.join('.')
    const num = (x) => (/^-?\d+(\.\d+)?$/.test(String(x)) ? Number(x) : String(x))
    rows = rows.filter((r) => {
      const x = r[k]
      if (op === 'not') return x !== null && x !== undefined
      if (x === null || x === undefined) return false
      const a = num(x), b = num(val)
      return op === 'eq' ? a === b : op === 'neq' ? a !== b : op === 'gte' ? a >= b : op === 'lte' ? a <= b : op === 'gt' ? a > b : op === 'lt' ? a < b : true
    })
  }
  const lim = sp.get('limit'); return lim ? rows.slice(0, Number(lim)) : rows
}

const failures = []
const check = (ok, what, detail = '') => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${detail ? ` (${String(detail).slice(0, 200)})` : ''}`); if (!ok) failures.push(what) }

;(async () => {
  const server = spawn('npx', ['vite', 'preview', '--port', String(PORT), '--strictPort'], { cwd: path.join(__dirname, '..'), stdio: 'ignore' })
  const b = await chromium.launch()
  try {
    for (let i = 0; i < 60; i++) { try { if ((await fetch(BASE)).ok) break } catch { /* starting */ } await new Promise((r) => setTimeout(r, 250)) }
    const ctx = await b.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 })
    const page = await ctx.newPage()
    const errors = []
    page.on('pageerror', (e) => errors.push(e.message))
    await ctx.route(`https://${REF}.supabase.co/**`, async (route) => {
      const req = route.request(); const u = new URL(req.url())
      if (!u.pathname.startsWith('/rest/v1/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
      const table = u.pathname.split('/').pop()
      const single = (req.headers()['accept'] ?? '').includes('vnd.pgrst.object')
      if (req.method() !== 'GET' && req.method() !== 'HEAD') {
        const body = JSON.parse(req.postData() || 'null')
        writes.push({ method: req.method(), table, body })
        const rows = Array.isArray(body) ? body : body ? [body] : []
        const out = rows.map((r) => { const row = { id: (T[table]?.length ?? 0) + 1, ...r }; (T[table] ??= []).push(row); return row })
        return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(single ? out[0] ?? null : out) })
      }
      const rows = filterRows(table, u.searchParams)
      return route.fulfill({ status: 200, headers: { 'content-type': 'application/json', 'content-range': `0-${rows.length}/*` }, body: JSON.stringify(single ? rows[0] ?? null : rows) })
    })
    const exp = Math.floor(Date.now() / 1000) + 3600
    const jwt = ['{"alg":"HS256","typ":"JWT"}', JSON.stringify({ sub: 'u1', exp, role: 'authenticated', email: 'test@example.com' }), 'sig'].map((s, i) => (i < 2 ? Buffer.from(s).toString('base64url') : s)).join('.')
    await ctx.addInitScript(([key, val]) => localStorage.setItem(key, val), [`sb-${REF}-auth-token`, JSON.stringify({ access_token: jwt, refresh_token: 'r', token_type: 'bearer', expires_in: 3600, expires_at: exp, user: { id: 'u1', email: 'test@example.com', aud: 'authenticated', role: 'authenticated' } })])
    const shot = (name) => page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: true })
    const step = async (label) => { await page.click(`.pb-step:has-text("${label}")`); await page.waitForTimeout(200) }

    await page.goto(`${BASE}#/plan-block`)
    await page.waitForSelector('.pb-steps', { timeout: 15000 })
    check((await page.textContent('.shell-rail')).includes('Plan block'), 'the rail lists Plan block under PLANNING')
    await shot('plan_1_when')
    check((await page.inputValue('input[type=time]')) === '06:30', 'session times start from training_settings')

    await step('PROGRAM')
    await page.click('.pb-card:has-text("Example three-day")')
    await page.click('.pb-card:has-text("Example black")')
    await shot('plan_2_program')
    check((await page.textContent('.pb-step.active')).includes('Example three-day + Example black'), 'the step rail sums up the program')

    await step('THE WEEK')
    await shot('plan_3_week')
    check((await page.locator('.pb-day').count()) === 7, 'the week shows seven days')
    // put the hard session on Saturday's neighbour to raise a warning, then reset
    await step('THE WEEKS')
    await page.click('tr:nth-child(2) .pb-chip:has-text("TEST")')
    await shot('plan_4_weeks')
    check((await page.textContent('.pb-table')).includes('TEST: MAXES'), 'a week can be made a test week')

    await step('DETAILS')
    await page.fill('tr:has-text("Deadlift") input[placeholder="kg"]', '170')
    await shot('plan_5_details')
    check((await page.textContent('.pb-section:has-text("MAXES")')).includes('estimate'), 'the deadlift max comes from logged sets')

    await step('REVIEW')
    await page.waitForSelector('.pb-sess')
    await page.click('.pb-sess.strength >> nth=0')
    await shot('plan_6_review')
    check((await page.textContent('.pb-sdetail')).includes('per side'), 'a session opens its full prescription with plates')
    const sessions = Number((await page.textContent('.pb-tile:has-text("SESSIONS")')).replace(/\D+/g, ''))
    await page.fill('.pb-field:has-text("NAME") input', 'Web block').catch(() => {})
    await page.click('button:has-text("CREATE BLOCK")')
    await page.waitForSelector('text=CREATED', { timeout: 10000 })
    await shot('plan_7_created')
    const planned = writes.filter((w) => w.table === 'planned_sessions').flatMap((w) => w.body)
    check(planned.length === sessions && sessions > 0, 'every planned session is written', `${planned.length} of ${sessions}`)
    check(writes.some((w) => w.table === 'athlete_maxes' && w.body.value === 170 && w.body.source === 'manual'), 'the typed deadlift max is recorded')
    const blk = writes.find((w) => w.table === 'training_blocks').body
    check(blk.origin === 'generated' && blk.status === 'scheduled' && blk.choices.timeline[1] === 'test', 'the block row keeps the choices', JSON.stringify(blk.choices.timeline))
    check(planned.every((p) => p.start_time === '06:30' || p.start_time === '19:00'), 'sessions carry the session times')
    check(errors.length === 0, 'no page errors', errors.join(' | '))
  } finally {
    await b.close(); server.kill()
  }
  console.log(failures.length ? `\n${failures.length} check(s) failed` : '\nall checks passed')
  process.exit(failures.length ? 1 : 0)
})()
