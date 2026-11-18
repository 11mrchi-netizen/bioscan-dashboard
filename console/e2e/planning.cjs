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
const addDays = (d, n) => { const t = new Date(`${d}T12:00:00Z`); t.setUTCDate(t.getUTCDate() + n); return t.toISOString().slice(0, 10) }
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
  // A block under way (Velocity): three-day program, Mondays/Wednesdays/Fridays, ending in five weeks.
  training_blocks: [{
    id: 99, name: 'Velocity', start_date: addDays(today, -14), end_date: addDays(today, 34), status: 'scheduled', origin: 'generated',
    components: [{ role: 'strength', ref: { kind: 'strength_module', key: 'example.three_day' } }],
    choices: { clusters: { 'example.three_day': { H: ['Deadlift'] } } },
    definition_snapshot: { definitions: { 'example.three_day': sc.definitions.find((d) => d.key === 'example.three_day') } }, review: null,
  }, {
    // The block under way recorded outside the planner (a Notion import): dates only, mirrored in training_cycles.
    id: 98, name: 'Recorded base', start_date: addDays(today, -30), end_date: addDays(today, 40), status: 'active', origin: 'imported', cycle_id: 5,
    components: [], choices: { imported_focus: { template: 'Recorded base' } }, definition_snapshot: null, review: null,
  }],
  training_block_domains: [],
  planned_sessions: Array.from({ length: 21 }, (_, i) => ({ id: 1000 + i, block_id: 99, scheduled_date: addDays(today, -14 + Math.floor(i / 3) * 7 + (i % 3) * 2), week_index: Math.floor(i / 3) + 1, status: i < 6 ? 'confirmed' : 'planned',
    module_ref: 'strength:example.three_day', title: `Example three-day: ${['One', 'Three', 'Five'][i % 3]}`, work_kind: 'progression',
    prescription: [{ type: 'lift', exercise: 'Press', load: { kind: 'pct_1rm', pct: 70 + (Math.floor(i / 3) % 3) * 5 + (i >= 15 ? 15 : 0) } }, { type: 'note', text: 'keep it crisp' }] })),
  meal_prep_weeks: [], breakfast_templates: [{ items: [{ food_id: 'staple:oats', grams: 70 }, { food_id: 'staple:greek_yoghurt', grams: 200 }, { food_id: 'staple:banana', grams: 118 }] }],
}
// The meal prep fixture week (fuel-core/fixtures/week.json), published for next Monday as the phone would.
const prepWeek = JSON.parse(fs.readFileSync(path.join(__dirname, '../../supabase/functions/_shared/fuel-core/fixtures/week.json'), 'utf8'))
const nextMonday = (() => { const d = new Date(`${today}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + ((8 - (d.getUTCDay() || 7)) % 7 || 7)); return d.toISOString().slice(0, 10) })()
const shift = (Date.parse(`${nextMonday}T00:00:00Z`) - Date.parse(`${prepWeek.week_start}T00:00:00Z`)) / 864e5
prepWeek.plan.days.forEach((d) => { const t = new Date(`${d.date}T12:00:00Z`); t.setUTCDate(t.getUTCDate() + shift); d.date = t.toISOString().slice(0, 10) })
T.fuel_week_plans = [{ week_start: nextMonday, plan: prepWeek.plan, engine_version: '4', computed_at: new Date().toISOString() }]
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
        writes.push({ method: req.method(), table, body, url: decodeURIComponent(req.url()) })
        if (req.method() === 'PATCH') return route.fulfill({ status: 204, body: '' })
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
    await page.click('.pb-section:has-text("EXAMPLE THREE-DAY") .pb-acc >> nth=0 >> button:has-text("ADD AN EXERCISE")')
    await page.fill('.pb-section:has-text("EXAMPLE THREE-DAY") .pb-acc >> nth=0 >> input[placeholder="Exercise"]', 'Face Pull')
    await page.click('.pb-section:has-text("EXAMPLE THREE-DAY") .pb-acc >> nth=1 >> button:has-text("ADD AN EXERCISE")')
    await page.fill('.pb-section:has-text("EXAMPLE THREE-DAY") .pb-acc >> nth=1 >> input[placeholder="Exercise"]', 'Curl')
    await page.selectOption('.pb-section:has-text("EXAMPLE THREE-DAY") .pb-acc >> nth=1 >> select', 'fixed')
    await page.fill('tr:has-text("Deadlift") input[placeholder="kg"]', '170')
    await shot('plan_5_details')
    check((await page.textContent('.pb-section:has-text("OVERRIDE 1RM")')).includes('estimate'), 'the deadlift max comes from logged sets')

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
    const planned = writes.filter((w) => w.table === 'planned_sessions' && w.method === 'POST').flatMap((w) => w.body)
    check(planned.length === sessions && sessions > 0, 'every planned session is written', `${planned.length} of ${sessions}`)
    check(writes.some((w) => w.table === 'athlete_maxes' && w.body.value === 170 && w.body.source === 'manual'), 'the typed deadlift max is recorded')
    const blk = writes.find((w) => w.table === 'training_blocks' && w.method === 'POST').body
    check(blk.origin === 'generated' && blk.status === 'scheduled' && blk.choices.timeline[1] === 'test', 'the block row keeps the choices', JSON.stringify(blk.choices.timeline))
    check(planned.every((p) => p.start_time === '06:30' || p.start_time === '19:00'), 'sessions carry the session times')
    const acc = blk.choices.clusters['example.three_day']?.accessories
    check(acc && acc['*'][0].exercise === 'Face Pull' && acc.D1[0].load.kg === 10, 'the accessory work is saved with the block', JSON.stringify(acc))
    const lifts = planned.filter((p) => p.module_ref === 'strength:example.three_day' && p.title.endsWith(': One')).map((p) => p.prescription.filter((e) => e.role === 'accessory').map((e) => e.exercise).join('+'))
    check(lifts.length > 0 && lifts.every((x) => x === 'Face Pull+Curl'), 'every session One gets the every-session work, then its own', lifts.slice(0, 3).join(' | '))
    const tests = planned.filter((p) => p.work_kind === 'test')
    check(tests.length > 0 && tests.every((p) => !p.prescription.some((e) => e.role === 'accessory')), 'test weeks leave accessory work out')

    // ---- ACCESSORY WORK ON THE BLOCK UNDER WAY ----
    await page.goto(`${BASE}#/plan-block`)
    await page.waitForSelector('.pb-steps', { timeout: 15000 })
    await page.click('.pb-step:has-text("WHEN")')
    await page.click('.pb-detail:has-text("Velocity") button:has-text("ACCESSORIES")')
    await page.click('.pb-acc >> nth=2 >> button:has-text("ADD AN EXERCISE")')
    await page.fill('.pb-acc >> nth=2 >> input[placeholder="Exercise"]', 'Hanging Leg Raise')
    await page.selectOption('.pb-acc >> nth=2 >> select', 'bodyweight')
    await shot('plan_7b_accessories_running')
    check(!(await page.textContent('.pb-detail:has-text("Recorded base")')).includes('ACCESSORIES'), 'a recorded block has no accessory action (no program behind it)')
    await page.click('button:has-text("APPLY FROM TODAY")')
    await page.waitForSelector('text=accessory work applied')
    const accPatches = writes.filter((w) => w.method === 'PATCH' && w.table === 'planned_sessions' && Array.isArray(w.body.prescription))
    const futureThree = T.planned_sessions.filter((p) => p.block_id === 99 && p.status === 'planned' && p.scheduled_date >= today && p.title.endsWith(': Three')).length
    check(accPatches.length === futureThree && futureThree > 0, 'only the Three sessions still planned from today change', `${accPatches.length} of ${futureThree}`)
    const pr = accPatches[0]?.body.prescription ?? []
    check(pr.map((e) => e.type + ':' + (e.exercise ?? '')).join(',') === 'lift:Press,lift:Hanging Leg Raise,note:', 'the accessory goes after the program\'s lifts, before the notes', JSON.stringify(pr))
    check(writes.some((w) => w.method === 'PATCH' && w.table === 'training_blocks' && w.body.choices?.clusters?.['example.three_day']?.accessories?.D3?.[0]?.exercise === 'Hanging Leg Raise'), 'the block\'s choices keep the accessory work')

    // ---- END A BLOCK EARLY, THEN BUILD TOWARD THE RACE ----
    await page.goto(`${BASE}#/plan-block`)
    await page.waitForSelector('.pb-steps', { timeout: 15000 })
    await page.click('.pb-step:has-text("WHEN")')
    await page.click('.pb-detail:has-text("Velocity") button:has-text("END EARLY")')
    await page.click('.pb-chip:has-text("END OF NEXT WEEK")')
    await page.waitForSelector('text=sets aside')
    await shot('plan_8_end_early')
    check((await page.textContent('.pb-section:has-text("BLOCKS UNDER WAY")')).includes('% of the 1RM'), 'ending early shows what is set aside')
    await page.click('button:has-text("END ON")')
    await page.waitForTimeout(800)
    const sup = writes.find((w) => w.method === 'PATCH' && w.table === 'planned_sessions' && w.body.status === 'superseded')
    check(sup && sup.body.status === 'superseded' && sup.url.includes('status=eq.planned') && sup.url.includes('scheduled_date=gt.'), 'later planned sessions are superseded, not deleted', sup && sup.url)
    const ended = writes.find((w) => w.method === 'PATCH' && w.table === 'training_blocks' && w.body.status === 'completed')
    check(ended && ended.body.status === 'completed' && ended.body.review.ended_early.maxes_stepped_up === false, 'the block ends with its maxes kept by default')
    check(!writes.some((w) => w.table === 'athlete_maxes' && w.body.source === 'progression'), 'no max is stepped up unless asked')
    const lastDay = ended.body.end_date
    check((await page.inputValue('.pb-field:has-text("START") input[type=date]')) === addDays(lastDay, 1), 'the new block starts the day after the stop', await page.inputValue('.pb-field:has-text("START") input[type=date]'))
    await page.selectOption('.pb-field:has-text("BUILD TOWARD") select', '7')
    await page.click('.pb-step:has-text("THE WEEKS")')
    await shot('plan_9_race_weeks')
    const kinds = await page.$$eval('.pb-table tbody tr', (rows) => rows.map((r) => r.className))
    check(kinds[0] === 'pb-week-lead' && kinds[kinds.length - 1] === 'pb-week-deload', 'toward the race: early weeks first, taper last', kinds.join(','))

    // ---- END A RECORDED (IMPORTED) BLOCK EARLY ----
    await page.click('.pb-step:has-text("WHEN")')
    await page.click('.pb-detail:has-text("Recorded base") button:has-text("END EARLY")')
    await page.waitForSelector('text=only its end date moves')
    await shot('plan_9b_end_recorded')
    check(!(await page.textContent('.pb-section:has-text("BLOCKS UNDER WAY")')).includes('MAXES FOR THE NEXT BLOCK'), 'a recorded block asks no maxes question')
    await page.click('.pb-chip:has-text("END OF NEXT WEEK")')
    await page.click('button:has-text("END ON")')
    await page.waitForTimeout(800)
    const rec = writes.find((w) => w.method === 'PATCH' && w.table === 'training_blocks' && w.url.includes('id=eq.98'))
    const cyc = writes.find((w) => w.method === 'PATCH' && w.table === 'training_cycles')
    check(rec && rec.body.status === 'completed' && cyc && cyc.url.includes('id=eq.5') && cyc.body.end_date === rec.body.end_date, 'a recorded block ends, and its training cycle with it', cyc && JSON.stringify(cyc.body))

    // ---- MEAL PREP ----
    await page.goto(`${BASE}#/meal-prep`)
    await page.waitForSelector('.pb-steps', { timeout: 15000 })
    await page.click('.pb-chip:has-text("NEXT WEEK")')
    await page.waitForSelector('text=LUNCH AND DINNER')
    await shot('prep_1_week')
    await page.click('tr:nth-child(3) .pb-chip:has-text("CONTAINER") >> nth=1')
    await page.click('.pb-step:has-text("PICK")')
    for (const name of ['chicken breast', 'salmon', 'broccoli', 'leafy greens', 'cooked white rice', 'baked sweet potato']) {
      await page.click(`.mp-food:has-text("${name}") >> nth=0`)
    }
    await shot('prep_2_pick')
    await page.click('.pb-step:has-text("RECIPES")')
    check((await page.locator('.mp-recipes .pb-card').count()) === 4, 'four recipes are built from the picks')
    await shot('prep_3_recipes')
    await page.click('.pb-step:has-text("CONTAINERS")')
    const rows = await page.locator('.pb-section:has-text("THE CONTAINERS") tbody tr').count()
    check(rows === 9, 'nine containers when one dinner is eaten out', rows)
    await shot('prep_4_containers')
    await page.click('.pb-step:has-text("EACH DAY")'); await shot('prep_5_days')
    await page.click('.pb-step:has-text("SHOPPING")')
    check((await page.textContent('.mp-shop')).includes('rice, dry'), 'shopping is in raw weights')
    await shot('prep_6_shop')
    await page.click('.pb-step:has-text("COOK")')
    await page.click('button:has-text("MARK AS COOKED")')
    await page.waitForSelector('text=COOKED · CONTAINERS FIXED')
    await page.waitForTimeout(900)
    await shot('prep_7_cook')
    const saved = writes.filter((w) => w.table === 'meal_prep_weeks').pop()?.body
    check(saved && saved.status === 'cooked' && saved.containers.length === 9 && saved.skipped.length === 1 && saved.recipes.length === 4, 'the cooked week is saved in the phone\'s row shape', JSON.stringify(saved && { s: saved.status, c: saved.containers?.length, k: saved.skipped }))
    await page.click('.pb-step:has-text("BREAKFAST")'); await shot('prep_8_breakfast')
    check(errors.length === 0, 'no page errors', errors.join(' | '))
  } finally {
    await b.close(); server.kill()
  }
  console.log(failures.length ? `\n${failures.length} check(s) failed` : '\nall checks passed')
  process.exit(failures.length ? 1 : 0)
})()
