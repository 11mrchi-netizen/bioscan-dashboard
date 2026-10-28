// Analysis hub phase E: a browser walkthrough of the console's whole workflow against a mocked Supabase (synthetic data in
// the live tables' shapes, served by a small PostgREST imitation: filters, keyset `or`, ordering, offsets, exact counts).
// It needs Playwright with Chromium installed (the Claude cloud environment has both; elsewhere `npm i -D playwright` and
// `npx playwright install chromium`). It is not part of CI.
//
//   cd console && npm run build && node e2e/walkthrough.cjs [screenshot dir]
//
// Starts `vite preview` on port 4173, walks Explore, the Inspector, Data, Integrity, Discovery, Decisions and saved views,
// checks what each step must show, and exits with 1 if any check fails.
const { spawn } = require('node:child_process')
const path = require('node:path')
const fs = require('node:fs')
let chromium
try { ({ chromium } = require('playwright')) } catch { console.error('Playwright is not installed: npm i -D playwright && npx playwright install chromium'); process.exit(2) }
const OUT = path.resolve(process.argv[2] ?? path.join(__dirname, 'out'))
fs.mkdirSync(OUT, { recursive: true })
const PORT = 4173
const BASE = `http://localhost:${PORT}/app.html`
const REF = 'ugfrglbcoivkprjqvjzz'

// --- synthetic tables ---
const day = (ms) => new Date(ms).toISOString().slice(0, 10)
const D0 = Date.UTC(2016, 8, 25), D1 = Date.UTC(2026, 9, 27)
let rnd = 7; const r = () => (rnd = (rnd * 1103515245 + 12345) % 2147483648) / 2147483648
const T = { wearable_daily: [], meals: [], exercise_sessions: [] }
let id = 1
for (let t = D0; t <= D1; t += 864e5) {
  const d = day(t), recent = t >= Date.UTC(2025, 3, 26)
  T.wearable_daily.push({ id: id++, user_id: 'u1', hr_timeseries: recent && d !== '2026-10-20' ? [{ bpm: 60 }] : null, date: d, steps: Math.round(4000 + r() * 9000), rhr: recent ? +(50 + r() * 8).toFixed(1) : null, hrv: recent ? +(40 + r() * 30).toFixed(1) : null, calories_total: Math.round(2000 + r() * 800), source: 'zepp' })
  if (t >= Date.UTC(2026, 7, 27)) for (const h of [8, 13, 20]) {
    const utcBackfill = d < '2026-09-17'
    const hh = utcBackfill ? h - 8 : h
    T.meals.push({ id: id++, user_id: 'u1', logged_at: `${d}T${String(hh).padStart(2, '0')}:00:00+00:00`, description: 'meal', calories: String(400 + Math.round(r() * 400)), protein_g: 30, source: 'manual' })
  }
}
// two runs at the same instant to exercise keyset ties
T.exercise_sessions.push({ id: 900001, user_id: 'u1', type: 'run', start_time: '2026-10-20T07:00:00+00:00', duration_min: 40, distance_km: 8, source: 'zepp' })
T.exercise_sessions.push({ id: 900002, user_id: 'u1', type: 'run', start_time: '2026-10-20T07:00:00+00:00', duration_min: 20, distance_km: 4, source: 'health_connect' })

// sleep: a gap of three worn nights, one implausible night, two rows on one night that disagree
T.sleep_daily = []
for (let t = Date.UTC(2026, 6, 1); t <= Date.UTC(2026, 9, 25); t += 864e5) {
  const d = day(t)
  if (['2026-09-10', '2026-09-11', '2026-09-12'].includes(d)) continue
  T.sleep_daily.push({ id: id++, user_id: 'u1', date: d, hours: d === '2026-10-01' ? 30 : 7, source: d < '2026-09-01' ? 'zepp' : 'health_connect' })
}
T.sleep_daily.push({ id: id++, user_id: 'u1', date: '2026-10-05', hours: 5.5, source: 'zepp' })
// a year of evening check-ins; HRV is 10% lower the morning after a drinking evening (planted for the discovery screen)
T.evening_checkins = []
T.registered_questions = []
const drinkDays = new Set()
for (let t = Date.UTC(2025, 9, 1); t <= Date.UTC(2026, 9, 26); t += 864e5) {
  const d = day(t)
  const dow = new Date(t).getUTCDay()
  const drinks = r() < (dow === 5 || dow === 6 ? 0.5 : 0.12) ? 2 : 0
  if (drinks) drinkDays.add(day(t + 864e5))
  T.evening_checkins.push({ id: id++, user_id: 'u1', date: d, drinks, caffeine_after_14: r() < 0.3, late_meal: r() < 0.25, stress: 1 + Math.floor(r() * 5) })
}
for (const w of T.wearable_daily) if (w.hrv !== null && drinkDays.has(w.date)) w.hrv = +(w.hrv * 0.9).toFixed(1)
T.collector_heartbeats = [{ stream: 'location', ts: new Date(Date.now() - 3600e3).toISOString() }]
T.daily_field_provenance = [{ writer: 'zepp-extract', origin: 'zepp', written_at: new Date(Date.now() - 7200e3).toISOString() }]
T.analysis_results = [{ kind: 'series_break', key: 'rhr', as_of: '2026-10-26', payload: { deviceSensitive: false, breaks: [{ date: '2026-09-01', kind: 'level', score: 4.2, status: 'candidate', reason: null, before: { n: 60, median: 52, robustSd: 2, integerShare: 0.1 }, after: { n: 50, median: 55, robustSd: 2, integerShare: 0.1 } }] } }]
const relP = (label, outcome, state, effect, extra = {}) => ({ label, outcome, state, n: 210, noEffect: outcome === 'hrv' ? 2 : 1, effect, readiness: { have: 41, need: 60, unit: 'days with it' }, summary: `${label}: ${state}`, ...extra })
const R = (kind, key, payload, state = null) => ({ user_id: 'u1', kind, key, as_of: '2026-10-26', engine_version: '3.0.0-alpha.9', inputs_hash: 'abc', state, payload, calculated_at: '2026-10-26T20:30:00Z' })
T.analysis_results.push(
  R('relationship', 'alcohol_hrv', relP('Alcohol and next-morning HRV', 'hrv', 'clear', { value: -6.1, lo: -9.8, hi: -2.3, unit: '%' })),
  R('relationship', 'caffeine_pm_sleep', relP('Caffeine after 14:00 and that night\'s sleep', 'sleep', 'unclear', { value: -1.5, lo: -4.2, hi: 1.1, unit: '%' })),
  R('relationship', 'stress_hrv', relP('A high-stress day and next-morning HRV', 'hrv', 'unclear', { value: -2.0, lo: -5, hi: 1, unit: '%' })),
  R('relationship', 'late_meal_sleep', relP('A meal after 21:00 and that night\'s sleep', 'sleep', 'not_yet', null)),
  R('experiment', '11', { name: 'Magnesium before bed', stage: 'complete', metrics: [{ metric: 'hrv', verdict: 'negligible', n0: 20, n1: 21, diff: { value: 0.4, lo: -1.2, hi: 2, unit: '%' }, zone: 2, summary: 'No difference beyond the zone' }] }),
  R('intervention_its', '3', { state: 'collecting', metrics: [{ metric: 'rhr', label: 'Resting HR', activeDays: 6 }] }),
)
T.evidence_ledger = [['2026-10-01', 'not_yet'], ['2026-10-10', 'unclear'], ['2026-10-20', 'clear'], ['2026-10-26', 'clear']].map(([d, v], i) => ({ id: i + 1, user_id: 'u1', question_id: 'alcohol_hrv', as_of: d, verdict: v, estimate: -6, lo: -9, hi: -2, unit: '%', engine_version: '3.0.0-alpha.9' }))
// the newest nightly integrity report, for the Decisions workbench
T.analysis_results.push(R('integrity', 'report', { issues: [{ key: 'stale|hrv|2026-10-24', kind: 'stale', severity: 'warning', seriesId: 'hrv', title: 'HRV (RMSSD): nothing new for 3 days', detail: 'd', firstDate: '2026-10-24', lastDate: '2026-10-27', days: 3, refs: [] }] }))
T.interventions = [{ id: '3', user_id: 'u1', name: 'Earlier bedtime' }]
T.decisions = []
T.saved_views = []
const requests = []
function cmp(a, b) { return a < b ? -1 : a > b ? 1 : 0 }
function unq(v) { return v.startsWith('"') ? v.slice(1, -1) : v }
function test(row, col, op, val) {
  const x = row[col]
  if (op === 'is') return val === 'null' ? x === null || x === undefined : true
  if (x === null || x === undefined) return false
  const a = String(x), b = unq(val)
  const c = /^-?\d+(\.\d+)?$/.test(a) && /^-?\d+(\.\d+)?$/.test(b) ? Number(a) - Number(b) : cmp(a, b)
  return op === 'gt' ? c > 0 : op === 'gte' ? c >= 0 : op === 'lt' ? c < 0 : op === 'lte' ? c <= 0 : op === 'eq' ? c === 0 : op === 'neq' ? c !== 0 : true
}
function postgrest(table, sp) {
  let rows = (T[table] ?? []).slice()
  for (const [k, v] of sp) {
    if (['select', 'order', 'limit', 'offset'].includes(k)) continue
    if (k === 'or') {
      // col.op."v",and(col.eq."v",id.op."n")
      const m = v.match(/^\((\w+)\.(gt|lt)\.("[^"]*"|[^,]+),and\(\1\.eq\.("[^"]*"|[^,]+),id\.(gt|lt)\.("[^"]*"|[^)]+)\)\)$/)
      if (!m) throw new Error('unparsed or: ' + v)
      rows = rows.filter((x) => test(x, m[1], m[2], m[3]) || (test(x, m[1], 'eq', m[4]) && test(x, 'id', m[5], m[6])))
      continue
    }
    const [op, ...rest] = v.split('.'); const val = rest.join('.')
    if (op === 'in') { const set = val.slice(1, -1).split(',').map(unq); rows = rows.filter((x) => set.includes(String(x[k]))); continue }
    if (op === 'not') { const [op2, ...v2] = val.split('.'); rows = rows.filter((x) => !test(x, k, op2, v2.join('.'))); continue }
    rows = rows.filter((x) => test(x, k, op, val))
  }
  const order = (sp.get('order') ?? '').split(',').filter(Boolean).map((o) => { const [c, d] = o.split('.'); return [c, d === 'desc' ? -1 : 1] })
  rows.sort((a, b) => { for (const [c, d] of order) { const x = test(a, c, 'gt', String(b[c])) ? 1 : test(a, c, 'lt', String(b[c])) ? -1 : 0; if (x) return x * d } return 0 })
  const total = rows.length
  const limit = Number(sp.get('limit') ?? 1000)
  const offset = Number(sp.get('offset') ?? 0)
  return { rows: rows.slice(offset, offset + Math.min(limit, 1000)), total }
}


function writeRow(table, method, body, url) {
  const rows = Array.isArray(body) ? body : [body]
  if (method === 'DELETE') {
    const id = new URL(url).searchParams.get('id')?.replace(/^eq\./, '')
    T[table] = (T[table] ?? []).filter((r) => String(r.id) !== id)
    return []
  }
  T[table] = T[table] ?? []
  const out = rows.map((r) => {
    if (table === 'saved_views') {
      const old = T[table].find((x) => x.name === r.name)
      if (old) { Object.assign(old, r); return old }
    }
    const row = { id: T[table].length + 1, user_id: 'u1', decided_at: new Date().toISOString(), ...r }
    T[table].push(row)
    return row
  })
  return out
}

const failures = []
const check = (ok, what, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${detail ? ` (${String(detail).replace(/\s+/g, ' ').slice(0, 160)})` : ''}`)
  if (!ok) failures.push(what)
}

async function waitForServer() {
  for (let i = 0; i < 60; i++) {
    try { const res = await fetch(BASE); if (res.ok) return } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error('vite preview did not start')
}

;(async () => {
  const server = spawn('npx', ['vite', 'preview', '--port', String(PORT), '--strictPort'], { cwd: path.join(__dirname, '..'), stdio: 'ignore' })
  const b = await chromium.launch()
  try {
    await waitForServer()
    const ctx = await b.newContext({ viewport: { width: 1440, height: 1000 } })
    const page = await ctx.newPage()
    const errors = []
    page.on('pageerror', (e) => errors.push(e.message))
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
    await ctx.route(`https://${REF}.supabase.co/**`, async (route) => {
      const req = route.request()
      const u = new URL(req.url())
      if (!u.pathname.startsWith('/rest/v1/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
      const table = u.pathname.split('/').pop()
      const single = (req.headers()['accept'] ?? '').includes('vnd.pgrst.object')
      if (req.method() === 'POST' || req.method() === 'DELETE' || req.method() === 'PATCH') {
        const body = JSON.parse(req.postData() || '{}')
        if (table === 'decisions') {
          const d = Array.isArray(body) ? body[0] : body
          if ((d.decision === 'adopt' || d.decision === 'reject') && !['clear', 'negligible'].includes(d.evidence?.verdict)) {
            return route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ message: 'adopt and reject need a settled verdict' }) })
          }
        }
        requests.push(`${req.method()} ${table}`)
        const out = writeRow(table, req.method(), body, req.url())
        return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(single ? out[0] ?? null : out) })
      }
      requests.push(`${req.method()} ${table}?${decodeURIComponent(u.searchParams.toString())}`)
      try {
        const { rows, total } = postgrest(table, u.searchParams)
        const prefer = req.headers()['prefer'] ?? ''
        const headers = { 'content-type': 'application/json', 'content-range': `0-${Math.max(rows.length - 1, 0)}/${prefer.includes('count=exact') ? total : '*'}`, 'access-control-expose-headers': 'content-range' }
        if (req.method() === 'HEAD') return route.fulfill({ status: 200, headers, body: '' })
        return route.fulfill({ status: 200, headers, body: JSON.stringify(single ? rows[0] ?? null : rows) })
      } catch (e) { return route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ message: String(e) }) }) }
    })
    const exp = Math.floor(Date.now() / 1000) + 3600
    const jwt = ['{"alg":"HS256","typ":"JWT"}', JSON.stringify({ sub: 'u1', exp, role: 'authenticated', email: 'test@example.com' }), 'sig'].map((s, i) => (i < 2 ? Buffer.from(s).toString('base64url') : s)).join('.')
    await ctx.addInitScript(([key, val]) => localStorage.setItem(key, val), [`sb-${REF}-auth-token`, JSON.stringify({ access_token: jwt, refresh_token: 'r', token_type: 'bearer', expires_in: 3600, expires_at: exp, user: { id: 'u1', email: 'test@example.com', aud: 'authenticated', role: 'authenticated' } })])
    const text = async (sel) => ((await page.textContent(sel).catch(() => '')) ?? '').replace(/\s+/g, ' ')
    const shot = (name) => page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: true })
    const clearRequests = () => { requests.length = 0 }

    // 1. Explore: ten years at monthly grain, paged past the 1,000-row cap, same-table series in one read
    clearRequests()
    await page.goto(`${BASE}#/explore?series=hrv,steps,meal_energy&start=2016-09-25&end=2026-10-27`)
    await page.waitForSelector('.ex-chart canvas', { timeout: 15000 }); await page.waitForTimeout(600)
    check((await text('.ex-grains')).includes('AUTO · MONTH'), 'Explore picks monthly resolution for ten years')
    const wearableReads = requests.filter((q) => q.startsWith('GET wearable_daily'))
    check(wearableReads.length === 4 && wearableReads.every((q) => q.includes('hrv') && q.includes('steps')), 'HRV and steps share one paged read (4 pages for 3,685 rows)', wearableReads.length)
    check(wearableReads.slice(1).every((q) => q.includes('or=(date.gt.')), 'later pages use keyset paging')
    await shot('1-explore-all')

    // 2. a narrower range comes from the cache
    clearRequests()
    await page.goto(`${BASE}#/explore?series=hrv,steps&start=2025-10-01&end=2026-10-27`)
    await page.waitForSelector('.ex-chart canvas'); await page.waitForTimeout(500)
    check(requests.filter((q) => q.startsWith('GET ')).length === 0, 'a narrower range needs no request', await text('.ex-perf'))

    // 3. month -> its days -> the Inspector with the records behind a day
    await page.goto(`${BASE}#/explore?series=meal_energy&start=2026-08-01&end=2026-10-27&grain=month`)
    await page.waitForSelector('.ex-chart canvas'); await page.waitForTimeout(500)
    let box = await page.locator('.ex-chart').boundingBox()
    await page.mouse.click(box.x + box.width * 0.5, box.y + box.height * 0.6)
    await page.waitForTimeout(500)
    check(page.url().includes('start=2026-09-01') && page.url().includes('end=2026-09-30'), 'clicking a month opens its days', page.url().split('#')[1])
    box = await page.locator('.ex-chart').boundingBox()
    await page.mouse.click(box.x + 52 + (box.width - 68) * (9.5 / 30), box.y + box.height * 0.75)
    await page.waitForSelector('.inspector-record', { timeout: 5000 }).catch(() => {})
    const insp = await text('.shell-inspector')
    check(insp.includes('2026-09-10') && insp.includes('08:00') && insp.includes('offset assumed'), 'a day opens the Inspector with its records at local time', insp.slice(0, 160))
    await shot('2-inspector')

    // 4. Data: exact count, keyset LOAD MORE, filters from the URL
    await page.goto(`${BASE}#/data?table=wearable_daily`)
    await page.waitForSelector('.de-table'); await page.waitForTimeout(300)
    check((await text('.de-meta')).includes('200 of 3,685'), 'Data shows the exact count', await text('.de-meta'))
    await page.click('.de-more'); await page.waitForTimeout(400)
    check((await text('.de-meta')).includes('400 of 3,685'), 'LOAD MORE adds the next page')
    await page.goto(`${BASE}#/data?table=exercise_sessions&id=900002`); await page.waitForTimeout(500)
    check((await text('.de-meta')).includes('1 of 1'), 'a record link opens that record')

    // 5. Integrity: tiles, matrix, planted problems, focus from the URL
    await page.goto(`${BASE}#/integrity`)
    await page.waitForSelector('.ig-tiles', { timeout: 20000 }); await page.waitForTimeout(300)
    check((await page.locator('.ig-tile').count()) === 4, 'Integrity shows the four overview tiles')
    check((await page.locator('.ig-row').count()) === 32, 'the source matrix has a row per series')
    await page.goto(`${BASE}#/integrity?series=sleep_hours`); await page.waitForTimeout(400)
    await page.click('.ig-sev-toggle.sev-info input'); await page.waitForTimeout(200)
    const sleepIssues = (await page.locator('.ig-issue').allTextContents()).join(' | ')
    check(/ISSUES · Sleep duration/.test(await text('.ig-issues-head')), 'Integrity opens focused on the series in the URL')
    for (const [what, re] of [['the three-night gap', /3 days missing/], ['the 30-hour night', /implausible/], ['the disagreeing rows', /rows disagree/], ['the source switch', /zepp -> health_connect/]]) {
      check(re.test(sleepIssues), `Integrity finds ${what}`)
    }
    await shot('3-integrity')

    // 6. Discovery: the planted alcohol -> next-morning HRV link is the one flagged cell; registering inserts a question
    await page.goto(`${BASE}#/evidence`)
    await page.click('.rlab-tab >> text=DISCOVERY')
    await page.waitForSelector('.disc-grid', { timeout: 20000 }); await page.waitForTimeout(300)
    const alc = page.locator('tr', { hasText: 'Alcohol' }).filter({ hasText: 'HRV' }).locator('.disc-cell').nth(1)
    check((await alc.getAttribute('class')).includes('flagged'), 'Discovery flags the planted link at lag 1')
    check((await page.locator('.disc-cell.flagged').count()) === 1, 'and nothing else', await page.locator('.disc-cell.flagged').count())
    clearRequests()
    await page.locator('.disc-cell.chance').first().click(); await page.waitForTimeout(150)
    await page.locator('.disc-detail button').click(); await page.waitForTimeout(400)
    check(requests.includes('POST registered_questions'), 'REGISTER inserts a registered question')
    await shot('4-discovery')

    // 7. Decisions: queue, workbench, a recorded decision, the link to Integrity
    await page.click('.rlab-tab >> text=DECISIONS')
    await page.waitForSelector('.dec-board', { timeout: 10000 })
    const col = async (i) => text(`.dec-col:nth-child(${i})`)
    check((await col(1)).includes('Alcohol and next-morning HRV') && (await col(1)).includes('Magnesium'), 'settled items wait for a decision')
    check((await col(2)).includes('Caffeine after 14:00'), 'an unclear, controllable question is ready to test')
    check((await col(3)).includes('high-stress'), 'an unclear question outside your control is under observation')
    check((await col(4)).includes('41 of about 60'), 'a question without enough data is blocked with its readiness')
    await page.locator('.dec-card', { hasText: 'Alcohol and next-morning HRV' }).click(); await page.waitForTimeout(400)
    check((await text('.dec-bench')).includes('nothing new for 3 days'), 'the workbench shows the integrity of the outcome')
    await page.fill('.dec-text', 'short'); await page.click('.dec-row button'); await page.waitForTimeout(150)
    check((await text('.dec-message')).includes('say why'), 'a decision needs a rationale')
    await page.fill('.dec-text', 'No alcohol on weekdays: HRV is about 6% lower the morning after, beyond my 3% threshold.')
    await page.click('.dec-row button'); await page.waitForTimeout(400)
    check((await col(5)).includes('Alcohol and next-morning HRV'), 'the decided item moves to Decided')
    check(T.decisions[0]?.evidence?.verdict === 'clear' && T.decisions[0]?.evidence?.result?.key === 'alcohol_hrv', 'the decision keeps its evidence snapshot')
    await shot('5-decisions')
    await page.click('text=OPEN IN INTEGRITY')
    await page.waitForSelector('.ig-tiles', { timeout: 20000 })
    check(page.url().includes('#/integrity?series=hrv'), 'the workbench links to Integrity on the outcome', page.url().split('#')[1])
    await page.locator('.dec-card').first().waitFor({ state: 'detached' }).catch(() => {})
    await page.goto(`${BASE}#/explore`); await page.waitForTimeout(200)
    await page.goto(`${BASE}#/evidence?question=stress_hrv`); await page.waitForTimeout(900)
    check((await text('.dec-bench-title')).includes('high-stress'), 'a question link opens its workbench')

    // 8. Saved views: save, leave, reopen
    await page.goto(`${BASE}#/explore?series=hrv,rhr&grain=week`); await page.waitForTimeout(500)
    await page.click('.sv > button')
    await page.fill('.sv-input', 'HRV weekly'); await page.click('.sv-save button'); await page.waitForTimeout(400)
    check(T.saved_views.some((v) => v.name === 'HRV weekly' && v.path === '/explore?series=hrv,rhr&grain=week'), 'a view is saved with its route and filters')
    await page.goto(`${BASE}#/data`); await page.waitForTimeout(300)
    await page.click('.sv > button'); await page.waitForTimeout(300)
    await page.locator('.sv-open', { hasText: 'HRV weekly' }).click(); await page.waitForTimeout(500)
    check(page.url().includes('#/explore?series=hrv,rhr&grain=week'), 'opening the view restores it', page.url().split('#')[1])

    // 9. old routes redirect
    await page.goto(`${BASE}#/overview`); await page.waitForTimeout(300)
    check(page.url().endsWith('#/explore'), 'v1 routes redirect')
    check(errors.length === 0, 'no page errors', errors.join(' | '))
  } finally {
    await b.close()
    server.kill()
  }
  console.log(failures.length ? `\n${failures.length} check(s) failed` : '\nall checks passed')
  process.exit(failures.length ? 1 : 0)
})().catch((e) => { console.error(e); process.exit(1) })
