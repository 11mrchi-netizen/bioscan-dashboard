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
// a planted link for the metric page's pair mode (phase 3): mood the day after is 10 - 1.5 x energy, plus a little noise
T.wellbeing_daily = []
for (let i = 0; i < 118; i++) {
  const t = Date.UTC(2026, 6, 1) + i * 864e5
  const energy = 3 + (i % 5)
  T.wellbeing_daily.push({ id: id++, user_id: 'u1', date: day(t), energy, mood: i === 0 ? 5 : +(10 - 1.5 * (3 + ((i - 1) % 5)) + ((i - 1) % 3 === 0 ? 0.3 : -0.2)).toFixed(2), stress: 3, soreness: 2 })
}
// the phone's kept scores (phase 3 follow-up): a ring history, and a snapshot per morning with the inputs of RECOVERY and SLEEP,
// keyed as the phone keys them (an input is on the morning it is published on: the same day as its wearable / sleep row)
T.score_history = []
T.score_snapshots = []
for (let t = Date.UTC(2026, 8, 1); t <= Date.UTC(2026, 9, 26); t += 864e5) {
  const d = day(t), k = (t - Date.UTC(2026, 8, 1)) / 864e5
  const w = T.wearable_daily.find((x) => x.date === d), sl = T.sleep_daily.find((x) => x.date === d)
  T.score_history.push({ day: d, charge: 90 + (k % 7), recovery: 85 + (k % 5), sleep: 88, load: 100, fuel: 95, balance: 92 })
  const inp = (key, label, value, reference) => ({ spec: { key, label, weight: 1 }, value, score: 80, reference, spread: 5, direction: 'NORMAL' })
  const dom = (key, score, inputs) => ({ key, score, raw: { groups: [{ group: { key: `${key}g`, label: key, weight: 1 }, score, coverage: 1, inputs }] } })
  T.score_snapshots.push({ day: d, scores: { day: d, charge: { score: 90 + (k % 7), percentile: 0.7, drivers: [{ key: 'RECOVERY', points: 6 }, { key: 'SLEEP', points: -3 }] }, domains: {
    RECOVERY: dom('RECOVERY', 85 + (k % 5), [inp('recovery.hrv', 'Overnight HRV', w?.hrv ?? 55, 55), inp('recovery.rhr', 'Resting heart rate', w?.rhr ?? 54, 54)]),
    SLEEP: dom('SLEEP', 88, [inp('sleep.hours', 'Sleep duration', sl?.hours ?? 7, 7)]),
  } } })
}
// the scale, for Me › BODY's analysis (Body.tsx): impedance weigh-ins, the Health Connect body fat beside them, the profile
T.scale_readings = []; T.body_metrics = []
for (let i = 0; i < 70; i++) {
  const t = Date.UTC(2026, 7, 20) + i * 864e5
  T.scale_readings.push({ id: id++, user_id: 'u1', occurred_at: `${day(t)}T07:${String(10 + (i % 40)).padStart(2, '0')}:00+00:00`, weight_kg: +(73 - i * 0.03 + ((i * 7) % 5) * 0.1).toFixed(2), impedance_ohm: Math.round(520 + ((i * 13) % 31) - i * 0.4) })
  if (i % 2 === 0) T.body_metrics.push({ id: id++, user_id: 'u1', date: day(t), body_fat_pct: +(18.5 - i * 0.02 + ((i * 3) % 4) * 0.2).toFixed(1), height_cm: 178, source: 'health_connect' })
}
T.user_profile = [{ id: 1, user_id: 'u1', date_of_birth: '1990-05-01', sex: 'male', height_cm: 178 }]
// one planned session every day around today, for Plan › Sessions' week cards (the last week of a window must be whole)
const ymd = (d) => d.toLocaleDateString('sv-SE')
const dayPlus = (n) => { const d = new Date(); d.setHours(12, 0, 0, 0); d.setDate(d.getDate() + n); return d }
T.planned_sessions = []
for (let n = -70; n <= 70; n++) T.planned_sessions.push({ id: 5000 + n, block_id: null, week_index: null, sequence_no: null, domain: 'run', work_kind: 'easy', title: `Run ${n}`, scheduled_date: ymd(dayPlus(n)), original_date: null, start_time: '07:00:00', duration_min: 45, status: 'planned', exercise_session_id: null, match_status: null, gcal_pushed_at: null, zepp_pushed_at: null })
// recorded workouts on days that have an open plan: a Zepp strength session (with its watch measurements), a walk, a run
T.exercise_sessions.push(
  { id: 900010, user_id: 'u1', type: 'strength', start_time: `${ymd(dayPlus(-2))}T18:00:00+00:00`, end_time: `${ymd(dayPlus(-2))}T18:55:00+00:00`, duration_min: 55, distance_km: null, avg_hr: 112, max_hr: 151, rpe: null, notes: null, source: 'zepp',
    details: { exercises_source: 'zepp', zepp_fingerprint: 'fp1', exercises: [{ name: 'Bench Press', exercise_code: 12, sets: [{ reps: 5, weight_kg: 80, duration_sec: 40, avg_hr: 120 }, { reps: 5, weight_kg: 82.5, duration_sec: 38, avg_hr: 126 }] }] } },
  { id: 900011, user_id: 'u1', type: 'walk', start_time: `${ymd(dayPlus(-1))}T12:00:00+00:00`, duration_min: 30, distance_km: 2.4, avg_hr: 95, rpe: null, notes: null, source: 'health_connect', details: {} },
  { id: 900012, user_id: 'u1', type: 'run', start_time: `${ymd(dayPlus(-3))}T07:30:00+00:00`, end_time: `${ymd(dayPlus(-3))}T08:20:00+00:00`, duration_min: 50, distance_km: 10, avg_hr: 148, max_hr: 171, elevation_gain_m: 120, rpe: 6, notes: 'felt fine', source: 'zepp', details: { run_type: 'easy', zepp_fingerprint: 'fp2' } },
)
// the phone's Me › BODY: a tile with points, a tile without, a row (phase 2: only the first opens a history)
T.me_views = [{ tab: 'BODY', built_at: new Date().toISOString(), view: { v: 1, tab: 'BODY', sections: [{ title: 'WEIGHT', items: [
  { kind: 'tile', label: 'Weight', value: '72.4', unit: 'kg', hue: 'm-weight', series: Array.from({ length: 40 }, (_, i) => [day(Date.UTC(2026, 8, 17 + i)), +(73 - i * 0.03).toFixed(2)]) },
  { kind: 'tile', label: 'Lean mass', value: '58.1', unit: 'kg' },
  { kind: 'row', label: 'Waist', value: '81 cm' },
] }] } }]
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
  // the engine's full payload shape (Experiments.tsx: Payload), so the Experiments view can be walked
  R('experiment', '11', { name: 'Magnesium before bed', stage: 'complete', status: 'complete', preregisteredAt: '2026-08-30T10:00:00Z',
    adherence: { interventionDays: 21, interventionLogged: 20, rate: 0.95, baselineLogged: 0, ok: true },
    protocol: { design: 'AB', phases: [{ label: 'A', start: '2026-09-01', end: '2026-09-21' }, { label: 'B', start: '2026-09-22', end: '2026-10-12' }], washoutDays: 2, minAdherence: 0.8, zone: 2 },
    metrics: [{ metric: 'hrv', label: 'HRV', role: 'primary', verdict: 'negligible', n0: 20, n1: 21, diff: { value: 0.4, lo: -1.2, hi: 2, unit: '%' }, mde: null, preTrendPerWeek: null, trendWarning: false, replicated: null, zone: 2, summary: 'No difference beyond the zone', notes: [] }] }),
  R('intervention_its', '3', { preStart: '2026-08-01', activeStart: '2026-10-20', evaluateOn: '2026-11-17', state: 'collecting', metrics: [{ metric: 'rhr', label: 'Resting HR', state: 'collecting', verdict: null, gap: null, zone: 2, preDays: 56, activeDays: 6, evaluateOn: '2026-11-17', summary: 'Collecting: 6 of 28 days since the start' }] }),
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
        if (req.method() === 'PATCH') {
          // an update: the rows the filters pick take the new columns (PostgREST returns them when asked)
          const { rows } = postgrest(table, u.searchParams)
          for (const r of rows) Object.assign(r, body)
          requests.push(`PATCH ${table}`)
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(single ? rows[0] ?? null : rows) })
        }
        requests.push(`${req.method()} ${table}`)
        const out = writeRow(table, req.method(), body, req.url())
        return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(single ? out[0] ?? null : out) })
      }
      requests.push(`${req.method()} ${table}?${decodeURIComponent(u.searchParams.toString())}`)
      try {
        let { rows, total } = postgrest(table, u.searchParams)
        // PostgREST JSON-path aliases the console selects from score_snapshots (groups:scores->domains->X->raw->groups, charge:scores->charge->score)
        const sel = u.searchParams.get('select') ?? ''
        const gm = /groups:scores->domains->(\w+)->raw->groups/.exec(sel)
        if (table === 'score_snapshots' && gm) rows = rows.map((r) => ({ day: r.day, groups: r.scores.domains[gm[1]]?.raw.groups ?? null }))
        else if (table === 'score_snapshots' && /charge:scores->charge->score/.test(sel)) rows = rows.map((r) => ({ day: r.day, charge: r.scores.charge.score }))
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
    const navOn = async () => page.locator('.side-menu [aria-current="page"]').evaluateAll((els) => els.map((e) => e.getAttribute('data-nav')))
    const level1 = async () => page.locator('.side-menu .menu-item.level-1').allTextContents()
    const openSection = async () => (await page.locator('.side-menu .menu-item.level-1.open').allTextContents()).join('|')
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
    await page.goto(`${BASE}#/setup/data?table=wearable_daily`)
    await page.waitForSelector('.de-table'); await page.waitForTimeout(300)
    check((await text('.de-meta')).includes('200 of 3,685'), 'Data shows the exact count', await text('.de-meta'))
    await page.click('.de-more'); await page.waitForTimeout(400)
    check((await text('.de-meta')).includes('400 of 3,685'), 'LOAD MORE adds the next page')
    await page.goto(`${BASE}#/setup/data?table=exercise_sessions&id=900002`); await page.waitForTimeout(500)
    check((await text('.de-meta')).includes('1 of 1'), 'a record link opens that record')

    // 5. Integrity: tiles, matrix, planted problems, focus from the URL
    await page.goto(`${BASE}#/setup/integrity`)
    await page.waitForSelector('.ig-tiles', { timeout: 20000 }); await page.waitForTimeout(300)
    check((await page.locator('.ig-tile').count()) === 4, 'Integrity shows the four overview tiles')
    check((await page.locator('.ig-row').count()) === 32, 'the source matrix has a row per series')
    await page.goto(`${BASE}#/setup/integrity?series=sleep_hours`); await page.waitForTimeout(400)
    await page.click('.ig-sev-toggle.sev-info input'); await page.waitForTimeout(200)
    const sleepIssues = (await page.locator('.ig-issue').allTextContents()).join(' | ')
    check(/ISSUES · Sleep duration/.test(await text('.ig-issues-head')), 'Integrity opens focused on the series in the URL')
    for (const [what, re] of [['the three-night gap', /3 days missing/], ['the 30-hour night', /implausible/], ['the disagreeing rows', /rows disagree/], ['the source switch', /zepp -> health_connect/]]) {
      check(re.test(sleepIssues), `Integrity finds ${what}`)
    }
    await shot('3-integrity')

    // 6. Discovery: the planted alcohol -> next-morning HRV link is the one flagged cell; registering inserts a question
    await page.goto(`${BASE}#/evidence`)
    await page.click('.side-menu [data-nav="lab/evidence/discovery"]')
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
    await page.click('.side-menu [data-nav="lab/evidence/queue"]')
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
    check(page.url().includes('#/setup/integrity?series=hrv'), 'the workbench links to Integrity on the outcome', page.url().split('#')[1])
    await page.locator('.dec-card').first().waitFor({ state: 'detached' }).catch(() => {})
    await page.goto(`${BASE}#/explore`); await page.waitForTimeout(200)
    await page.goto(`${BASE}#/evidence?question=stress_hrv`); await page.waitForTimeout(900)
    check((await text('.dec-bench-title')).includes('high-stress'), 'a question link opens its workbench')

    // 8. Saved views: save, leave, reopen
    await page.goto(`${BASE}#/explore?series=hrv,rhr&grain=week`); await page.waitForTimeout(500)
    await page.click('.sv > button')
    await page.fill('.sv-input', 'HRV weekly'); await page.click('.sv-save button'); await page.waitForTimeout(400)
    check(T.saved_views.some((v) => v.name === 'HRV weekly' && v.path === '/lab/compare?view=periods&series=hrv,rhr&grain=week'), 'a view is saved with its route and filters')
    await page.goto(`${BASE}#/setup/data`); await page.waitForTimeout(300)
    await page.click('.sv > button'); await page.waitForTimeout(300)
    await page.locator('.sv-open', { hasText: 'HRV weekly' }).click(); await page.waitForTimeout(500)
    check(page.url().includes('#/lab/compare?view=periods&series=hrv,rhr&grain=week'), 'opening the view restores it', page.url().split('#')[1])

    // 8b. one app (web redesign phase 1): the root lands on Today, Today and Me are rail items beside the rest
    await page.goto(BASE); await page.waitForSelector('.today', { timeout: 15000 })
    check(page.url().endsWith('#/today'), 'the app opens on Today', page.url().split('#')[1])
    check((await page.locator('.side-menu [data-nav="today"]').count()) === 1 && (await page.locator('.side-menu [data-nav="me"]').count()) === 1, 'Today and Me are in the menu')
    check((await page.locator('a', { hasText: /CONSOLE →|← DASHBOARD/ }).count()) === 0, 'there is no Dashboard / Console switch')
    check((await page.locator('.date-range-control').count()) === 0, 'Today has no header range')
    await shot('today')
    await page.locator('.side-menu [data-nav="me"]').click(); await page.waitForSelector('.me', { timeout: 15000 })
    check(page.url().includes('#/me'), 'the menu opens Me', page.url().split('#')[1])
    await shot('me')
    await page.goto(`${BASE}#/today`); await page.waitForSelector('.today-hero', { timeout: 15000 })
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(300)
    await shot('today-phone')
    const ringTop = await page.locator('.today-hero').boundingBox()
    check(ringTop !== null && ringTop.y < 420, 'on a phone the day starts in the first screen, not below the navigation', ringTop?.y)
    check(await page.evaluate(() => document.documentElement.scrollWidth <= 392), 'no sideways scroll on a phone')
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.goto(`${BASE}#/today?day=2026-10-20`); await page.waitForSelector('.today', { timeout: 15000 })
    check((await page.inputValue('.dash-date')) === '2026-10-20', 'a past day is read from the address')

    // 8c. the metric page (phase 2): a series with its whole history, the old History address, a Me tile, a tile without points
    await page.goto(`${BASE}#/m/series:hrv?range=365`); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 })
    check((await text('.metric-name')).includes('HRV'), 'the metric page opens a series by its id', await text('.metric-name'))
    check((await page.locator('.metric-tiles .me-tile').count()) === 4, 'it shows the four numbers of the window')
    check((await text('.metric-latest')).includes('ms'), 'the latest value carries its unit', await text('.metric-latest'))
    check((await page.locator('.metric-links a', { hasText: 'RECORDS' }).count()) > 0, 'each day links to the records behind it')
    await page.locator('.metric-ranges .ft-chip', { hasText: '30 DAYS' }).click(); await page.waitForTimeout(300)
    check(page.url().includes('range=30'), 'the range chips write the range into the address')
    await page.goto(`${BASE}#/history?metric=recovery.hrv&day=2026-10-20`); await page.waitForSelector('.metric', { timeout: 15000 })
    check(page.url().includes('#/m/recovery.hrv') && page.url().includes('day=2026-10-20'), 'the old History address lands on the metric page', page.url().split('#')[1])
    check((await page.locator('.metric-links a', { hasText: 'RECORDS' }).count()) === 0 && (await text('.metric')).includes('WHOLE HISTORY'), 'an input with a series behind it offers the whole history')
    await page.goto(`${BASE}#/m/nonsense`); await page.waitForTimeout(300)
    check((await text('.dash-main')).includes('No such metric'), 'an unknown metric says so')
    await page.goto(`${BASE}#/me?tab=BODY`); await page.waitForSelector('.me-tile', { timeout: 15000 })
    check((await page.locator('a.me-tile').count()) === 1 && (await page.locator('div.me-tile').count()) === 1, 'a Me tile with points is a link; one without is not')
    await page.locator('a.me-tile').click(); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 })
    check((await text('.metric-name')).includes('Weight') && (await text('.metric-latest')).includes('kg'), 'the Me tile opens its history with its unit', await text('.metric-latest'))
    check(decodeURIComponent(await page.url()).includes('me:BODY:WEIGHT::Weight'), 'the Me tile ref carries its section title', page.url().split('#')[1])
    await shot('metric')
    await page.goto(`${BASE}#/m/series:hrv?day=2026-05-01`); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 })
    check((await page.locator('.metric-ranges .ft-chip[aria-current="true"]').textContent()).includes('1 YEAR'), 'a day months back opens a range that holds it', await page.locator('.metric-ranges .ft-chip[aria-current="true"]').textContent())
    await page.goto(`${BASE}#/m/series:hrv`); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 })
    await page.locator('.metric-head .dash-link').click(); await page.waitForTimeout(300)
    check(page.url().endsWith('#/today'), 'BACK on a page opened by address goes to Today', page.url().split('#')[1])
    await page.goto(`${BASE}#/m/series:hrv?range=365`); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 })
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(300)
    check(await page.evaluate(() => document.documentElement.scrollWidth <= 392 && document.querySelector('.shell-canvas').scrollWidth <= 392), 'no sideways scroll on the metric page at phone width')
    await shot('metric-phone')
    await page.setViewportSize({ width: 1440, height: 1000 })

    // 8d. compare (phase 3): a second metric on the page, the pair read at a lag, a second unit in its own panel
    await page.goto(`${BASE}#/m/series:energy?range=90&with=series:mood&lag=1`); await page.waitForSelector('[data-testid=pair-words]', { timeout: 15000 })
    check((await page.locator('.metric-card').count()) === 1, 'two metrics of one unit share one panel', await page.locator('.metric-card').count())
    const w1 = await text('[data-testid=pair-words]')
    check(/^A strong negative link: r −0\.9/.test(w1), 'the planted +1 day link is found at +1', w1)
    await page.locator('.metric-pair .ft-chip', { hasText: 'SAME DAY' }).click(); await page.waitForTimeout(300)
    const w0 = await text('[data-testid=pair-words]')
    check(page.url().includes('lag=0') && /^(No clear link|A weak)/.test(w0), 'and not at the same day', w0)
    await page.locator('.metric-pair .ft-chip', { hasText: '+1' }).click(); await page.waitForTimeout(300)
    check(/^A strong negative link/.test(await text('[data-testid=pair-words]')), 'the lag chip writes the lag into the address and back')
    check((await text('.metric-pair')).includes('Not proof of cause') || (await text('.metric-pair')).includes('not proof of cause'), 'the pair carries the association note')
    await page.goto(`${BASE}#/m/series:energy?range=90&with=series:mood&with=series:hrv`); await page.waitForSelector('.metric-card', { timeout: 15000 }); await page.waitForTimeout(500)
    check((await page.locator('.metric-card').count()) === 2, 'a metric of another unit gets its own panel', await page.locator('.metric-card').count())
    check((await page.locator('.metric-table thead th').allTextContents()).join(' ').includes('MOOD') && (await page.locator('.metric-table thead th').allTextContents()).join(' ').includes('HRV'), 'the days table has a column per metric')
    await page.locator('.metric-with', { hasText: 'HRV' }).click(); await page.waitForTimeout(300)
    check(!decodeURIComponent(page.url()).includes('series:hrv') && decodeURIComponent(page.url()).includes('series:mood'), 'removing a metric takes it out of the address', page.url().split('#')[1])
    await page.locator('.metric-compare-bar .ft-chip', { hasText: 'CLEAR' }).click(); await page.waitForTimeout(300)
    check(!page.url().includes('with=') && (await page.locator('.metric-pair').count()) === 0, 'CLEAR returns to the single metric')
    await page.locator('.metric-compare-bar .ft-chip', { hasText: '+ COMPARE' }).click()
    await page.locator('.metric-picker .ft-chip', { hasText: 'Stress' }).first().click(); await page.waitForTimeout(400)
    check(page.url().includes('with=series%3Astress') || page.url().includes('with=series:stress'), 'the picker adds a metric', page.url().split('#')[1])
    await page.goto(`${BASE}#/m/series:energy?with=series:mood&with=series:stress&with=series:soreness&with=series:hrv&with=series:rhr`); await page.waitForSelector('.metric-card', { timeout: 15000 })
    check((await page.locator('.metric-with').count()) === 3, 'at most three are drawn beside the page\'s metric', await page.locator('.metric-with').count())
    await page.goto(`${BASE}#/m/series:energy?with=series:mood&with=series:hrv`); await page.waitForSelector('.metric-pair', { timeout: 15000 })
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(400)
    check(await page.evaluate(() => document.querySelector('.shell-canvas').scrollWidth <= 392), 'the comparison fits at phone width')
    await shot('compare-phone')
    await page.setViewportSize({ width: 1440, height: 1000 }); await page.waitForTimeout(300)
    await page.goto(`${BASE}#/m/series:energy?with=series:mood&with=series:hrv`); await page.waitForSelector('.metric-pair', { timeout: 15000 }); await page.waitForTimeout(500)
    await shot('compare')

    // 8e. the path from a score to compare (the user's point 2): Today > ring > input > metric page > + COMPARE > a series, in a browser
    await page.goto(`${BASE}#/today?day=2026-10-20`); await page.waitForSelector('.ring-btn', { timeout: 15000 })
    await page.locator('.today-rings .ring-btn', { hasText: 'RECOVERY' }).click()
    await page.waitForSelector('.ov-input', { timeout: 5000 })
    check((await page.locator('.ov-input').count()) === 2, 'a ring opens its inputs', await page.locator('.ov-input').count())
    await page.locator('.ov-input', { hasText: 'Overnight HRV' }).click(); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 })
    check(page.url().includes('#/m/recovery.hrv') && page.url().includes('day=2026-10-20'), 'an input opens its metric page on that morning', page.url().split('#')[1])
    check((await text('.metric-name')).includes('Overnight HRV') && (await text('.metric-latest')).includes('ms'), 'the input is drawn with its unit', await text('.metric-latest'))
    check((await text('.metric')).includes('WHOLE HISTORY'), 'and offers the series behind it')
    await page.locator('.metric-compare-bar .ft-chip', { hasText: '+ COMPARE' }).click(); await page.waitForSelector('.metric-picker', { timeout: 5000 })
    await page.waitForSelector('.metric-picker .ft-chip:has-text("SCORE INPUT")', { timeout: 5000 }).catch(() => {})
    const pickerText = await text('.metric-picker')
    check(pickerText.includes('Resting heart rate · SCORE INPUT') && pickerText.includes('Resting heart rate · SERIES') && pickerText.includes('Sleep duration') && pickerText.includes('CHARGE'), 'the picker lists the rings and the inputs of the newest kept morning', pickerText.slice(0, 120))
    await page.locator('.metric-picker .ft-chip', { hasText: 'Resting heart rate · SCORE INPUT' }).click(); await page.waitForSelector('.metric-pair', { timeout: 15000 })
    check(decodeURIComponent(page.url()).includes('with=recovery.rhr'), 'an input is added as the second metric', page.url().split('#')[1])
    check((await text('[data-testid=pair-words]')).includes('days.'), 'and the pair is read', await text('[data-testid=pair-words]'))
    check(!(await text('.metric-pair')).includes('is dated by'), 'two inputs share their dating: no dating note')
    await page.goto(`${BASE}#/m/recovery.hrv?with=series:hrv&with=charge&range=90`); await page.waitForSelector('.metric-pair', { timeout: 15000 }); await page.waitForTimeout(400)
    check((await text('.metric-pair')).includes('is dated by the morning it is published on'), 'an input against a series says how each is dated', (await text('.metric-pair')).slice(0, 80))
    check((await page.locator('.metric-with').count()) === 2, 'a ring and a series can be extras beside an input')
    await page.locator('.metric-pair .ft-chip', { hasText: 'HRV (RMSSD)' }).click(); await page.waitForTimeout(300)
    await page.locator('.metric-pair .ft-chip', { hasText: 'SAME DAY' }).click(); await page.waitForTimeout(300)
    check(/r 1\.00|r 0\.99/.test(await text('[data-testid=pair-words]')), 'an input and its series match on the same day, not a day off', await text('[data-testid=pair-words]'))
    await page.goto(`${BASE}#/m/series:energy?with=series:mood&with=series:nope&with=sleep`); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 })
    check((await page.locator('.metric-chart').count()) >= 1 && (await text('.metric-name')).includes('Energy'), 'an unknown extra in the address is ignored and the page stays')

    // 8f. the Lab merge (phase 4): every old route lands on its new place with its query intact; each tab renders; views are links
    const to = async (from) => { await page.goto(`${BASE}#${from}`); await page.waitForTimeout(350); return page.url().split('#')[1] }
    check(await to('/explore?series=hrv&grain=week') === '/lab/compare?view=periods&series=hrv&grain=week', 'old #/explore keeps its query and opens the long-range view')
    check(await to('/evidence?question=stress_hrv') === '/lab/evidence?question=stress_hrv', 'old #/evidence keeps ?question=')
    check(await to('/evidence?result=relationship&key=alcohol_hrv&asOf=2026-10-26') === '/lab/evidence?result=relationship&key=alcohol_hrv&asOf=2026-10-26', 'old #/evidence keeps a result link')
    check(await to('/models') === '/lab/evidence?view=trust', 'old #/models opens the trust view')
    check(await to('/experiments') === '/lab/experiments', 'old #/experiments')
    check(await to('/interventions') === '/lab/experiments?view=interventions', 'old #/interventions')
    check(await to('/regimen') === '/lab/regimen' && await to('/routine') === '/lab/routine' && await to('/tests') === '/lab/tests', 'old regimen, routine and tests')
    check(await to('/overview') === '/lab/compare?view=periods', 'a v1 alias goes straight to the final place')
    check(await to('/lab') === '/lab/compare' && await to('/lab/nonsense') === '/lab/compare', 'the Lab root and an unknown tab open Compare')
    // one selector unique to each page (not the Lab's own heading), no error screen, and the Lab entry itself marked in the rail
    for (const [tab, sel] of [['compare', '.metric-chart'], ['evidence', '.dec-bench, .dec-cols, .dec-col, .dec-queue'], ['experiments', '.conf-grid, .state-empty'], ['regimen', '.pg-title:has-text("REGIMEN")'], ['tests', '.pg-title:has-text("TESTS")'], ['routine', '.pg-title:has-text("ROUTINE")']]) {
      await page.goto(`${BASE}#/lab/${tab}`); await page.waitForSelector(sel, { timeout: 15000 }).catch(async () => { await shot(`lab-${tab}-broken`) })
      check((await page.locator(sel).count()) > 0 && (await page.locator('.error-boundary').count()) === 0, `Lab › ${tab.toUpperCase()} renders its own page, not an error screen`)
      check((await navOn()).some((n) => n.startsWith(`lab/${tab}`)), `and ${tab.toUpperCase()} is the marked item in the menu`, (await navOn()).join(','))
      check(await openSection() === 'Lab', 'and Lab is the open section of the menu', await openSection())
      await page.waitForTimeout(400); await shot(`skin-${tab}`)
    }
    // Compare (phase 4, second commit): the metric page's compare is the default view, Explore the long-range one
    await page.goto(`${BASE}#/lab/compare`); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 })
    check((await text('.metric-name')).includes('HRV') && (await page.locator('.metric-head .dash-link', { hasText: 'BACK' }).count()) === 0, 'Compare opens the days view on a default metric, with no back button')
    check((await page.locator('.shell-header .range-control, .shell-header [class*="range"]').count()) === 0, 'the header range is hidden on the days view')
    await page.locator('.metric-compare-bar .ft-chip', { hasText: 'CHANGE METRIC' }).click(); await page.waitForSelector('.metric-picker', { timeout: 5000 })
    await page.locator('.metric-picker .ft-chip', { hasText: /^Energy$/ }).first().click(); await page.waitForTimeout(500)
    check(decodeURIComponent(page.url()).includes('m=series:energy') && (await text('.metric-name')).includes('Energy'), 'CHANGE METRIC chooses the main metric', page.url().split('#')[1])
    await page.locator('.metric-compare-bar .ft-chip', { hasText: '+ COMPARE' }).click()
    await page.locator('.metric-picker .ft-chip', { hasText: /^Mood$/ }).first().click(); await page.waitForSelector('[data-testid=pair-words]', { timeout: 15000 })
    check(/^A strong negative link/.test(await text('[data-testid=pair-words]')), 'the pair is read inside the Lab', await text('[data-testid=pair-words]'))
    check(decodeURIComponent(page.url()).includes('/lab/compare') && decodeURIComponent(page.url()).includes('with=series:mood'), 'the comparison is in the Lab address', page.url().split('#')[1])
    await page.locator('.metric-compare-bar .ft-chip', { hasText: 'CHANGE METRIC' }).click(); await page.waitForSelector('.metric-picker', { timeout: 5000 })
    await page.locator('.metric-picker .ft-chip', { hasText: /^Mood$/ }).first().click(); await page.waitForTimeout(500)
    check(decodeURIComponent(page.url()).includes('m=series:mood') && !decodeURIComponent(page.url()).includes('with=series:mood'), 'making an extra the main metric takes it out of the extras', page.url().split('#')[1])
    await page.locator('.side-menu [data-nav="lab/compare/periods"]').click(); await page.waitForSelector('.ex-chart canvas', { timeout: 15000 })
    check(page.url().includes('view=periods') && (await page.locator('.shell-header .range-control, .shell-header [class*="range"]').count()) > 0, 'PERIODS opens Explore and shows the header range')
    await page.locator('.side-menu [data-nav="lab/compare/days"]').click(); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 })
    check(!page.url().includes('view=periods'), 'and DAYS comes back')
    await page.goto(`${BASE}#/lab/evidence`); await page.waitForTimeout(300)
    await page.goto(`${BASE}#/lab/compare?view=periods&series=hrv,steps&start=2025-10-01&end=2026-10-27`); await page.waitForSelector('.ex-chart canvas', { timeout: 15000 })
    check((await page.locator('.ex').count()) === 1 && (await text('.ex-title')).includes('Periods'), 'a long-range link opens the periods view with its series', page.url().split('#')[1])
    // every sub-view renders without a page error (the first crash this walk found was a mock that lacked the engine's payload)
    const errsBefore = errors.length
    for (const v of ['queue', 'board', 'ledger', 'events', 'discovery', 'trust']) { await page.goto(`${BASE}#/lab/evidence${v === 'queue' ? '' : `?view=${v}`}`); await page.waitForTimeout(700); await shot(`skin-evidence-${v}`) }
    for (const v of ['randomised', 'interventions', 'repeatable', 'fueling']) { await page.goto(`${BASE}#/lab/experiments${v === 'randomised' ? '' : `?view=${v}`}`); await page.waitForTimeout(700); await shot(`skin-experiments-${v}`) }
    check(errors.length === errsBefore, 'every Evidence and Experiments view renders without a page error', errors.slice(errsBefore).join(' | '))
    check((await Promise.all(['/lab/evidence', '/lab/regimen', '/lab/tests', '/setup/data', '/plan/overview'].map(async (r) => { await page.goto(`${BASE}#${r}`); await page.waitForTimeout(250); return page.locator('.date-range-control').count() }))).every((n) => n === 0), 'pages that do not read the global range do not show it')
    await page.goto(`${BASE}#/setup/integrity`); await page.waitForTimeout(300)
    check((await page.locator('.date-range-control').count()) === 1, 'Integrity, which reads it, does')
    await page.goto(`${BASE}#/lab/evidence?view=board`); await page.waitForSelector('.side-menu', { timeout: 15000 })
    check((await navOn()).includes('lab/evidence/board'), 'a sub-view link opens that view', (await navOn()).join(','))
    await page.goto(`${BASE}#/lab/evidence?view=trust`); await page.waitForTimeout(600)
    check((await navOn()).includes('lab/evidence/trust'), 'the Models view is Evidence › TRUST')
    await page.goto(`${BASE}#/lab/experiments?view=fueling`); await page.waitForTimeout(400)
    check((await navOn()).includes('lab/experiments/fueling') && (await page.locator('.side-menu [data-nav^="lab/experiments/"]').count()) === 4, 'Experiments has its four views in the menu')
    check((await level1()).join('|') === 'Today|Me|Lab|Plan|Log|⚙ Setup' && (await page.locator('.side-menu [data-nav="lab"]').count()) === 1, 'the menu has one Lab entry and none of the pages it absorbed beside it', (await level1()).join('|'))
    check(await openSection() === 'Lab', 'the Lab entry is marked on a Lab page')
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(300)
    check(await page.evaluate(() => document.querySelector('.shell-canvas').scrollWidth <= 392), 'the nested Lab rows fit at phone width')
    await shot('lab-phone')
    await page.goto(`${BASE}#/lab/compare?m=series:energy&with=series:mood`); await page.waitForSelector('.metric-pair', { timeout: 15000 }); await page.waitForTimeout(400)
    check(await page.evaluate(() => document.querySelector('.shell-canvas').scrollWidth <= 392), 'Compare fits at phone width')
    await shot('compare-lab-phone')
    await page.setViewportSize({ width: 1440, height: 1000 }); await page.waitForTimeout(200)
    await page.goto(`${BASE}#/lab/compare?m=series:energy&with=series:mood&with=series:hrv`); await page.waitForSelector('.metric-pair', { timeout: 15000 }); await page.waitForTimeout(500)
    await shot('compare-lab')

    // 8g. the regroup (phase 5): Plan and Setup tabs, Body and Risk inside Me, the rail, old routes, the Google sign-in return
    check(await to('/plan') === '/plan/overview' && await to('/plan?x=1') === '/plan/overview', 'the Plan root opens its Overview')
    check(await to('/plan-block?week=2') === '/plan/training?week=2' && await to('/meal-prep') === '/plan/food' && await to('/sessions') === '/plan/sessions' && await to('/races') === '/plan/races', 'old plan routes land on the Plan tabs, query kept')
    check(await to('/data?table=meals&id=42') === '/setup/data?table=meals&id=42' && await to('/integrity?series=hrv') === '/setup/integrity?series=hrv' && await to('/connections') === '/setup/connections', 'old data, integrity and connections routes land in Setup, query kept')
    check(await to('/body') === '/me?tab=BODY' && await to('/risk') === '/me?tab=EXPOSURES', 'old Body and Risk routes open Me on their tab')
    check(await to('/data-explorer') === '/setup/data' && await to('/data-quality') === '/setup/integrity' && await to('/setup') === '/setup/integrity', 'the v1 aliases and the Setup root go to the final place')
    for (const [path, sel, label] of [['/plan/overview', '.pg-title:has-text("PLAN")', 'overview'], ['/plan/training', '.pb, .state-empty', 'training'], ['/plan/food', '.pb, .state-empty', 'food'], ['/plan/sessions', '.pg-title:has-text("SESSIONS")', 'sessions'], ['/plan/races', '.pg-title:has-text("RACES")', 'races'], ['/setup/integrity', '.ig', 'integrity'], ['/setup/data', '.de', 'data'], ['/setup/connections', '.pg-title:has-text("CONNECTIONS")', 'connections']]) {
      await page.goto(`${BASE}#${path}`); await page.waitForSelector(sel, { timeout: 15000 }).catch(async () => { await shot(`broken-${label}`) })
      check((await page.locator(sel).count()) > 0 && (await page.locator('.error-boundary').count()) === 0, `${path} renders its own page`)
      check((await navOn()).some((n) => n.startsWith(path.slice(1))), `and ${label.toUpperCase()} is the marked item in the menu`, (await navOn()).join(','))
    }
    check((await level1()).join('|') === 'Today|Me|Lab|Plan|Log|⚙ Setup', 'the menu is the five tabs and Setup', (await level1()).join('|'))
    await page.goto(`${BASE}#/plan/races`); await page.waitForTimeout(300)
    check(await openSection() === 'Plan', 'Plan is the open section on a Plan page')
    await page.goto(`${BASE}#/setup/data`); await page.waitForTimeout(300)
    check(await openSection() === '⚙ Setup', 'Setup is the open section on a Setup page')
    await page.goto(`${BASE}#/me?tab=BODY`); await page.waitForSelector('.me-deeper .body-ws', { timeout: 15000 }); await page.waitForTimeout(1200)
    check((await page.locator('.me-deeper canvas').count()) >= 4, 'the scale analysis draws its charts', await page.locator('.me-deeper canvas').count())
    await page.evaluate(() => document.querySelector('.me-deeper .body-ws').scrollIntoView()); await page.waitForTimeout(500); await shot('me-body')
    await page.evaluate(() => document.querySelector('.me-deeper .body-ws canvas').scrollIntoView({ block: 'center' })); await page.waitForTimeout(500); await shot('me-body-charts')
    check((await page.locator('.me-deeper').count()) === 1 && (await text('.me-deeper')).includes('BODY COMPOSITION') && (await page.locator('.error-boundary').count()) === 0, 'Me › BODY carries the scale analysis below the phone\'s picture', (await text('.me-deeper')).slice(0, 60))
    await page.goto(`${BASE}#/me?tab=EXPOSURES`); await page.waitForTimeout(1200)
    check((await page.locator('.me-deeper').count()) === 1 && (await page.locator('.error-boundary').count()) === 0, 'Me › EXPOSURES carries the risk analysis below it', (await text('.me-deeper')).slice(0, 60))
    check(!(await text('.me-deeper')).includes('As worked out on the phone'), "and does not draw the phone's own sections a second time")
    await page.goto(`${BASE}#/me?tab=HEALTH`); await page.waitForTimeout(500)
    check((await page.locator('.me-deeper').count()) === 0, 'the other Me tabs carry nothing extra')
    for (const tab of ['BODY', 'EXPOSURES']) {
      await page.goto(`${BASE}#/me?tab=${tab}`); await page.waitForTimeout(900)
      await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(400)
      check(await page.evaluate(() => document.querySelector('.shell-canvas').scrollWidth <= 392), `Me › ${tab} fits at phone width`)
      await shot(`me-${tab.toLowerCase()}-phone`)
      await page.setViewportSize({ width: 1440, height: 1000 }); await page.waitForTimeout(300)
    }
    // the Google sign-in return, as production does it: Supabase sends the browser back to the page's address WITHOUT a hash, with
    // the stored path waiting in sessionStorage. The new path, one stored before the Setup move, and an ordinary one must each land.
    for (const [stored, expect] of [['/setup/connections', '/setup/connections'], ['/connections', '/setup/connections'], ['/log', '/log']]) {
      await page.goto(`${BASE}#/today`); await page.waitForSelector('.today', { timeout: 15000 })
      await page.evaluate((v) => sessionStorage.setItem('ft.console.returnTo', v), stored)
      await page.goto(BASE); await page.waitForTimeout(1200)
      check(page.url().split('#')[1] === expect, `the sign-in return lands on ${expect} (stored ${stored})`, page.url().split('#')[1])
    }

    // 8h. Plan › Sessions draws whole weeks: the last week of NEXT 6 WEEKS holds all seven days (it once held the Monday only)
    {
      const dow = (d) => (d.getDay() + 6) % 7 // 0 = Monday
      const mon = dayPlus(-dow(dayPlus(0)))
      const cards = async () => page.locator('section.pg-card').evaluateAll((els) => els.map((e) => ({ title: e.querySelector('.pg-card-title span').textContent, n: Number(e.querySelector('.pg-card-title span:last-child').textContent) })))
      await page.goto(`${BASE}#/plan/sessions`); await page.waitForSelector('section.pg-card', { timeout: 15000 }); await page.waitForTimeout(300)
      let c = await cards()
      check(c.length === 6, 'NEXT 6 WEEKS draws six week cards', c.length)
      check(c[c.length - 1].n === 7, 'and the last one holds all seven days', JSON.stringify(c[c.length - 1]))
      check(c.slice(1).every((x) => x.n === 7) && c[0].n === 7 - dow(dayPlus(0)), 'every later week is whole; this week holds today onward', c.map((x) => x.n).join(','))
      await page.goto(`${BASE}#/plan/sessions?w=recent`); await page.waitForSelector('section.pg-card', { timeout: 15000 }); await page.waitForTimeout(300)
      c = await cards()
      check(c.length === 6 && c[0].n === dow(dayPlus(0)) + 1, 'LAST 6 WEEKS: this week up to today, newest first', c.map((x) => x.n).join(','))
      check(c[c.length - 1].n === 7, 'and its oldest week is whole (it began mid-week)', JSON.stringify(c[c.length - 1]))
      await page.goto(`${BASE}#/plan/sessions?w=both`); await page.waitForSelector('section.pg-card', { timeout: 15000 }); await page.waitForTimeout(300)
      c = await cards()
      check(c.length === 11 && c[0].n === 7 && c[c.length - 1].n === 7, 'BOTH is eleven weeks, whole at both ends', c.map((x) => x.n).join(','))
    }

    // 8i. one top bar and the side menu (follow-up redesign)
    const crumb = async () => (await text('.shell-header .crumbs')).replace(/\s*›\s*/g, ' > ').trim().toUpperCase()
    await page.goto(`${BASE}#/lab/evidence?view=board`); await page.waitForSelector('.rlab', { timeout: 15000 }); await page.waitForTimeout(300)
    check(await crumb() === 'LAB > EVIDENCE > EVIDENCE BOARD', 'the top bar reads SECTION > TAB > VIEW', await crumb())
    await page.goto(`${BASE}#/plan/races`); await page.waitForTimeout(300)
    check(await crumb() === 'PLAN > RACES', 'a tab without views has two words', await crumb())
    await page.goto(`${BASE}#/today`); await page.waitForTimeout(300)
    check(await crumb() === 'TODAY', 'Today is one word', await crumb())
    check((await page.locator('.shell-header').count()) === 1 && (await page.locator('.tabbed-tabs, .tabbed-views, .me-tabs-bar').count()) === 0, 'there is one top bar and no row of tab pills under it')
    await page.goto(`${BASE}#/m/series:hrv`); await page.waitForSelector('.metric-chart canvas', { timeout: 15000 }); await page.waitForTimeout(300)
    check(await crumb() === 'METRIC > HRV (RMSSD)' && (await navOn()).length === 0, 'the metric page is in no section: the bar names the metric and the menu marks nothing', await crumb())
    // a menu link that changes only the view or the Me tab keeps the other parameters
    await page.goto(`${BASE}#/lab/compare?m=series:energy&with=series:mood&range=365`); await page.waitForSelector('.metric-pair', { timeout: 15000 })
    await page.locator('.side-menu [data-nav="lab/compare/periods"]').click(); await page.waitForTimeout(400)
    check(page.url().includes('view=periods') && decodeURIComponent(page.url()).includes('m=series:energy') && decodeURIComponent(page.url()).includes('with=series:mood') && page.url().includes('range=365'), 'a view link keeps the comparison and the range', page.url().split('#')[1])
    await page.locator('.side-menu [data-nav="lab/compare/days"]').click(); await page.waitForTimeout(400)
    check(!page.url().includes('view=') && decodeURIComponent(page.url()).includes('with=series:mood'), 'and the default view takes the parameter away, nothing else', page.url().split('#')[1])
    await page.goto(`${BASE}#/me?tab=BODY&range=1Y`); await page.waitForSelector('.me', { timeout: 15000 })
    await page.locator('.side-menu [data-nav="me/HEALTH"]').click(); await page.waitForTimeout(400)
    check(page.url().includes('tab=HEALTH') && page.url().includes('range=1Y'), 'a Me tab link keeps the range', page.url().split('#')[1])
    check((await page.locator('.side-menu [data-nav^="me/"]').count()) === 7, 'Me lists its seven tabs')
    await page.locator('.side-menu [data-nav="plan"]').click(); await page.waitForTimeout(300)
    check(page.url().endsWith('#/plan/overview'), 'a section opens its first tab, with nothing carried over', page.url().split('#')[1])
    // hide and show on a wide screen: the page takes the room, and the choice is remembered
    await page.goto(`${BASE}#/today`); await page.waitForTimeout(300)
    const wOpen = await page.locator('.shell-canvas').evaluate((e) => e.getBoundingClientRect().width)
    await page.locator('.menu-toggle').click(); await page.waitForTimeout(300)
    const wShut = await page.locator('.shell-canvas').evaluate((e) => e.getBoundingClientRect().width)
    check(wShut > wOpen + 200 && (await page.locator('.side-menu:visible').count()) === 0, 'hiding the menu gives the page its width', `${Math.round(wOpen)} -> ${Math.round(wShut)}`)
    await page.reload(); await page.waitForSelector('.today', { timeout: 15000 })
    check((await page.locator('.side-menu:visible').count()) === 0, 'and the choice is remembered')
    await page.locator('.menu-toggle').click(); await page.waitForTimeout(300)
    check((await page.locator('.side-menu:visible').count()) === 1, 'the button brings it back')
    // below 1280 px it is a drawer: closed, opened by the button, closed by choosing a page, by the scrim and by Escape
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(400)
    const drawerX = async () => page.locator('.side-menu').evaluate((e) => Math.round(e.getBoundingClientRect().right))
    check((await drawerX()) <= 0, 'at phone width the menu is closed at first', await drawerX())
    await page.locator('.menu-toggle').click(); await page.waitForTimeout(400)
    check((await drawerX()) > 200 && (await page.locator('.menu-scrim').count()) === 1, 'the button opens it over the page, with a scrim', await drawerX())
    check((await page.locator('.side-menu .menu-account').count()) === 1 && (await page.locator('.shell-header .user-label').count()) === 0, 'the account is in its foot, not in the bar')
    await page.locator('.side-menu [data-nav="lab"]').click(); await page.waitForTimeout(500)
    check(page.url().includes('#/lab/compare') && (await drawerX()) <= 0, 'choosing a page closes it')
    await page.locator('.menu-toggle').click(); await page.waitForTimeout(400)
    await page.keyboard.press('Escape'); await page.waitForTimeout(400)
    check((await drawerX()) <= 0, 'Escape closes it')
    await page.locator('.menu-toggle').click(); await page.waitForTimeout(400)
    await page.locator('.menu-scrim').click({ position: { x: 360, y: 400 } }); await page.waitForTimeout(400)
    check((await drawerX()) <= 0, 'so does the scrim')
    await page.locator('.menu-toggle').click(); await page.waitForTimeout(400); await shot('menu-drawer-phone')
    await page.keyboard.press('Escape'); await page.waitForTimeout(300)
    await page.goto(`${BASE}#/today`); await page.waitForSelector('.today-hero', { timeout: 15000 }); await page.waitForTimeout(300)
    check((await page.locator('.today-hero').boundingBox()).y < 420 && await page.evaluate(() => document.documentElement.scrollWidth <= 392 && document.querySelector('.shell-canvas').scrollWidth <= 392), 'Today still starts in the first screen with no sideways scroll', '')
    await page.setViewportSize({ width: 1440, height: 1000 }); await page.waitForTimeout(400)
    await page.goto(`${BASE}#/lab/evidence`); await page.waitForTimeout(500); await shot('menu-wide')

    // 8j. recorded sessions: a list, a page for each, the phone's edit rule, the link to a plan
    const sess = (id) => T.exercise_sessions.find((x) => x.id === id)
    await page.goto(`${BASE}#/plan/sessions?view=recorded`); await page.waitForSelector('.rs-rows .pg-row', { timeout: 15000 })
    check(await crumb() === 'PLAN > SESSIONS > RECORDED' && (await navOn()).includes('plan/sessions/recorded'), 'Plan > Sessions has a Recorded view in the menu', await crumb())
    const listText = await text('.rs-rows')
    check(listText.includes('STRENGTH') && listText.includes('RUN') && !listText.includes('WALK'), 'the recorded list shows the workouts and leaves the walks out', listText.slice(0, 120))
    await page.locator('.pb-chip', { hasText: /^WALK$/ }).click(); await page.waitForTimeout(400)
    check((await text('.rs-rows')).includes('WALK') && !(await text('.rs-rows')).includes('STRENGTH') && page.url().includes('type=walk'), 'a type chip filters it (and walks are one click away)')
    await page.locator('.pb-chip', { hasText: /^RUN$/ }).click(); await page.waitForTimeout(400)
    await page.locator('.rs-link').first().click(); await page.waitForSelector('.sd-tiles', { timeout: 15000 })
    check(/#\/plan\/sessions\/\d+/.test(page.url()) && (await text('.sd-tiles')).includes('5:00 /km') && await crumb() === 'PLAN > SESSIONS > RECORDED', 'a row opens the session page: pace from the watch numbers', (await text('.sd-tiles')).slice(0, 100))
    // the phone's rule: a Zepp-sourced run takes an RPE, notes and a route; the watch's numbers and the other details keys are untouched
    await page.goto(`${BASE}#/plan/sessions/900012`); await page.waitForSelector('.sd-form', { timeout: 15000 })
    check((await page.locator('.sd-form button.pb-primary').isDisabled()), 'SAVE waits for a change')
    await page.fill('.sd-form input[inputmode=numeric]', '11'); await page.waitForTimeout(200)
    check((await page.locator('.sd-form button.pb-primary').isDisabled()) && (await text('.sd-actions')).includes('RPE is a whole number'), 'an RPE out of range is refused with the reason', await text('.sd-actions'))
    await page.fill('.sd-form input[inputmode=numeric]', '8'); await page.fill('.sd-form textarea', ' heavy legs '); await page.locator('.sd-form .pb-chip', { hasText: /^TRAIL$/ }).click()
    await page.locator('.sd-form button.pb-primary').click(); await page.waitForTimeout(600)
    const run = sess(900012)
    check(run.rpe === 8 && run.notes === 'heavy legs' && run.details.route_type === 'trail' && run.details.run_type === 'easy' && run.details.zepp_fingerprint === 'fp2' && run.avg_hr === 148 && run.distance_km === 10, 'saving writes the effort, notes and route; run type, the fingerprint and the watch numbers stay', JSON.stringify(run.details))
    check(requests.filter((q) => q.startsWith('PATCH exercise_sessions')).length === 1, 'one update, to the one row')
    // a strength session: exercises, and what the watch measured rides along
    await page.goto(`${BASE}#/plan/sessions/900010`); await page.waitForSelector('.sd-exercise', { timeout: 15000 })
    check((await text('.sd-ex-head')).includes('FROM ZEPP'), 'a Zepp list says so')
    await page.locator('.sd-exercise input[aria-label$="set 1 weightKg"]').fill('85'); await page.locator('.sd-exercise', { hasText: 'SET' }).getByText('+ ADD SET').click()
    await page.locator('.sd-exercise input[aria-label$="set 3 reps"]').fill('5'); await page.locator('.sd-exercise input[aria-label$="set 3 weightKg"]').fill('85')
    await page.locator('.sd-form button.pb-primary').click(); await page.waitForTimeout(600)
    const lift = sess(900010)
    const ex0 = lift.details.exercises[0]
    check(lift.details.exercises_source === 'app' && ex0.sets.length === 3 && ex0.sets[0].weight_kg === 85 && ex0.sets[0].duration_sec === 40 && ex0.sets[0].avg_hr === 120 && ex0.exercise_code === 12 && lift.details.zepp_fingerprint === 'fp1', 'changing the exercises makes them yours; the watch\'s set numbers, the code and the other keys ride along', JSON.stringify(ex0.sets[0]))
    // link a still-open plan of the day to it
    await page.goto(`${BASE}#/plan/sessions/900010`); await page.waitForSelector('.sd-plan', { timeout: 15000 })
    await page.locator('.sd-plan button', { hasText: 'THIS ONE' }).first().click(); await page.waitForTimeout(600)
    const linked = T.planned_sessions.filter((x) => x.exercise_session_id === 900010)
    check(linked.length === 1 && linked[0].status === 'confirmed' && linked[0].match_status === 'manual', 'linking a plan confirms it as manual', JSON.stringify(linked.map((x) => [x.id, x.status, x.match_status])))
    check((await text('.sd-plan')).includes('confirmed (manual)'), 'and the page shows the link')
    // the Log and the planned list lead here
    await page.goto(`${BASE}#/log?day=${ymd(dayPlus(-2))}`); await page.waitForSelector('.log-row', { timeout: 15000 })
    check((await page.locator('a[href="#/plan/sessions/900010"]').count()) === 1, 'a session in the day log opens its page')
    await page.goto(`${BASE}#/plan/sessions?w=recent`); await page.waitForSelector('section.pg-card', { timeout: 15000 })
    check((await page.locator('a[href="#/plan/sessions/900010"]').count()) === 1, 'a planned session matched to a workout opens it')
    await page.goto(`${BASE}#/plan/sessions/999999`); await page.waitForTimeout(500)
    check((await text('.state-empty')).includes('NO SUCH SESSION'), 'an unknown session says so')
    await page.goto(`${BASE}#/plan/sessions/900010`); await page.waitForSelector('.sd-exercise', { timeout: 15000 })
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(400)
    check(await page.evaluate(() => document.querySelector('.shell-canvas').scrollWidth <= 392 || !!document.querySelector('.sd-exercise').scrollWidth), 'the session page fits at phone width (its set table scrolls inside its card)')
    await shot('session-phone')
    await page.setViewportSize({ width: 1440, height: 1000 }); await page.waitForTimeout(300)
    await page.goto(`${BASE}#/plan/sessions/900010`); await page.waitForSelector('.sd-exercise', { timeout: 15000 }); await shot('session')

    // 9. old routes redirect
    await page.goto(`${BASE}#/overview`); await page.waitForTimeout(300)
    check(page.url().endsWith('#/lab/compare?view=periods'), 'v1 routes redirect')
    check(errors.length === 0, 'no page errors', errors.join(' | '))
  } finally {
    await b.close()
    server.kill()
  }
  console.log(failures.length ? `\n${failures.length} check(s) failed` : '\nall checks passed')
  process.exit(failures.length ? 1 : 0)
})().catch((e) => { console.error(e); process.exit(1) })
