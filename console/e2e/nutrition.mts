// Nutrition pages (docs/nutrition): the console's new nutrition data on synthetic rows in the live tables' shapes, built by
// the real analysis-core functions. Checks LAB › REPORTS (meal quality, training fuel, gut lines, food triggers), a
// session's FUEL card, the meal profile on the Log, and the digestion score's metric page. Not part of CI.
//
//   cd console && npm run build && node e2e/nutrition.mts [screenshot dir]
//
// Exits 1 if a check fails.
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { derivedRows } from '../../supabase/functions/_shared/analysis-core/runDerived.ts'
import { energyAvailability, sessionFuel, trainingFuelSection } from '../../supabase/functions/_shared/analysis-core/trainingFuel.ts'
import { foodTriggers } from '../../supabase/functions/_shared/analysis-core/foodTriggers.ts'

const here = path.dirname(fileURLToPath(import.meta.url))
const { chromium } = createRequire(import.meta.url)('playwright')
const OUT = path.resolve(process.argv[2] ?? path.join(here, 'out'))
fs.mkdirSync(OUT, { recursive: true })
const PORT = 4175
const BASE = `http://localhost:${PORT}/app.html`
const REF = 'ugfrglbcoivkprjqvjzz'

// --- synthetic inputs: 60 days, a morning run every other day, three meals a day, a stool most mornings ---
const day = (i: number) => new Date(Date.UTC(2026, 8, 27 + i)).toISOString().slice(0, 10)
const meals: { id: number; logged_at: string; calories: number; carbs_g: number; protein_g: number; fat_g: number }[] = []
const sessions: { id: number; start_time: string; type: string; duration_min: number; calories_active: number }[] = []
const stool: { id: number; occurred_at: string; bristol_type: number }[] = []
const profiles: Record<string, unknown>[] = []
let id = 1
for (let i = 0; i < 60; i++) {
  const d = day(i)
  for (const [h, kcal, groups] of [[8, 600, ['eggs', 'refined_grains']], [13, 800, ['poultry', 'vegetables']], [20, 900, i % 3 ? ['meat', 'whole_grains'] : ['legumes', 'dairy']]] as const) {
    const m = { id: id++, logged_at: `${d}T${String(h).padStart(2, '0')}:00:00+00:00`, calories: kcal, carbs_g: kcal / 8, protein_g: kcal / 25, fat_g: kcal / 40 }
    meals.push(m)
    profiles.push({ meal_id: m.id, user_id: 'u1', logged_at: m.logged_at, energy_density_kcal_g: 1.6, protein_per_100kcal: 4, carbs_per_100kcal: 12.5, fiber_per_1000kcal: 11, glycemic_load_est: 24, fullness_factor_est: 2.4, ultra_processed_pct: h === 8 ? 30 : 0, food_groups: groups, tags: groups.includes('dairy') ? ['lactose'] : [], macro_mismatch: false })
  }
  if (i % 2 === 0) sessions.push({ id: 500 + i, start_time: `${d}T06:30:00+00:00`, type: 'run', duration_min: i % 10 === 0 ? 120 : 50, calories_active: i % 10 === 0 ? 1400 : 550 })
  if (i % 7 !== 6) stool.push({ id: id++, occurred_at: `${d}T07:45:00+00:00`, bristol_type: i % 3 === 0 ? 6 : 4 })
}
const body = [{ date: day(0), weightKg: 72, bodyFatPct: 19 }]
const tfMeals = meals.map((m) => ({ at: m.logged_at, kcal: m.calories, carbsG: m.carbs_g, proteinG: m.protein_g }))
const tfSessions = sessions.map((s) => ({ id: s.id, at: s.start_time, type: s.type, durationMin: s.duration_min, activeKcal: s.calories_active }))
const derived = derivedRows('u1', { stool: stool.map((s) => ({ occurred_at: s.occurred_at, bristol_type: s.bristol_type })), checkins: [], meals: tfMeals, sessions: tfSessions, body, fuel: [] }, day(0), day(59))
const start = day(49), end = day(55)
const tf = trainingFuelSection(sessionFuel(tfSessions, tfMeals), energyAvailability(tfMeals, tfSessions, body), start, end, 72)
const ft = foodTriggers(profiles.map((p) => ({ at: String(p.logged_at), groups: p.food_groups as string[], tags: p.tags as string[] })), stool.map((s) => ({ at: s.occurred_at, bristol: s.bristol_type })), day(0), end)
const report = {
  version: '1.0.0', key: `week:${start}`, grain: 'week', label: 'Week of test', start, end, prevStart: day(42), prevEnd: day(48), generatedOn: day(57),
  metrics: [
    { id: 'gut_day_score', ref: 'series:gut_day_score', days: 6, prev: 84, unit: '/100', delta: -10, label: 'Digestion score', value: 74, better: 'up', status: 'worse', perWeek: false, section: 'gut', prevDays: 6, usualSwing: 5 },
    { id: 'stool_loose_pct', ref: null, days: 6, prev: 20, unit: '%', delta: 10, label: 'Loose stools (Bristol 6–7)', value: 30, better: 'down', status: 'steady', perWeek: false, section: 'gut', prevDays: 6, usualSwing: 20 },
  ],
  micro: { days: 7, foodDays: 7, lines: [] }, regimen: [], lab: [], foodStool: [],
  mealQuality: { meals: 21, lines: [{ key: 'protein_dose', label: 'Main meals with a full protein dose (22 g)', value: 63, unit: '%', basis: '13 of 21 meals', prev: 80 }] },
  trainingFuel: tf, foodTriggers: ft,
  summary: { better: [], worse: ['Digestion score'], working: [], wasted: [], notFollowed: [], excess: [], short: [] }, suggestions: [],
}
const T: Record<string, Record<string, unknown>[]> = {
  analysis_results: [{ key: report.key, as_of: end, payload: report, kind: 'period_report' }],
  derived_daily: derived.daily.map((r, i) => ({ id: i + 1, ...r })),
  session_fuel: derived.sessions,
  exercise_sessions: sessions.map((s) => ({ ...s, user_id: 'u1', end_time: null, distance_km: 10, calories_total: null, avg_hr: 145, max_hr: 170, elevation_gain_m: 50, avg_power_w: null, avg_speed_kmh: 12, rpe: null, notes: null, details: {}, source: 'zepp', health_connect_record_id: null })),
  meals: meals.map((m) => ({ ...m, description: 'meal', meal_slot: null, source: 'manual', meal_items: [] })),
  meal_profiles: profiles,
}

function postgrest(table: string, sp: URLSearchParams): Record<string, unknown>[] {
  let rows = [...(T[table] ?? [])]
  for (const [k, v] of sp) {
    if (['select', 'order', 'limit', 'offset'].includes(k)) continue
    const [op, ...rest] = v.split('.'); const val = rest.join('.')
    if (op === 'eq') rows = rows.filter((r) => String(r[k]) === val)
    if (op === 'gte') rows = rows.filter((r) => String(r[k]) >= val)
    if (op === 'lte') rows = rows.filter((r) => String(r[k]) <= val)
    if (op === 'lt') rows = rows.filter((r) => String(r[k]) < val)
    if (op === 'in') { const set = (val.slice(1, -1).match(/"[^"]*"|[^,]+/g) ?? []).map((x) => x.replace(/^"|"$/g, '')); rows = rows.filter((r) => set.includes(String(r[k]))) }
  }
  const [col, dir] = (sp.get('order') ?? '').split(',')[0].split('.')
  if (col) rows.sort((a, b) => (String(a[col]) < String(b[col]) ? -1 : 1) * (dir === 'desc' ? -1 : 1))
  return rows.slice(Number(sp.get('offset') ?? 0), Number(sp.get('offset') ?? 0) + Number(sp.get('limit') ?? 1000))
}

const failures: string[] = []
const check = (ok: boolean, what: string, detail = '') => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${detail ? ` (${detail})` : ''}`); if (!ok) failures.push(what) }
const server = spawn('npx', ['vite', 'preview', '--port', String(PORT), '--strictPort'], { cwd: path.join(here, '..'), stdio: 'ignore' })
const b = await chromium.launch()
try {
  for (let i = 0; i < 60; i++) { try { if ((await fetch(BASE)).ok) break } catch { /* not up */ } await new Promise((r) => setTimeout(r, 250)) }
  const ctx = await b.newContext({ viewport: { width: 1440, height: 2400 }, deviceScaleFactor: 1 })
  const page = await ctx.newPage()
  const errors: string[] = []
  page.on('pageerror', (e: Error) => errors.push(e.message))
  await ctx.route(`https://${REF}.supabase.co/**`, async (route: any) => {
    const u = new URL(route.request().url())
    if (!u.pathname.startsWith('/rest/v1/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    const table = u.pathname.split('/').pop()!
    const rows = postgrest(table, u.searchParams)
    if ((route.request().headers()['accept'] ?? '').includes('vnd.pgrst.object')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows[0] ?? null) })
    return route.fulfill({ status: 200, headers: { 'content-type': 'application/json', 'content-range': `0-${rows.length}/*` }, body: JSON.stringify(rows) })
  })
  const exp = Math.floor(Date.now() / 1000) + 3600
  const jwt = ['{"alg":"HS256","typ":"JWT"}', JSON.stringify({ sub: 'u1', exp, role: 'authenticated' }), 'sig'].map((s, i) => (i < 2 ? Buffer.from(s).toString('base64url') : s)).join('.')
  await ctx.addInitScript(([key, val]: string[]) => localStorage.setItem(key, val), [`sb-${REF}-auth-token`, JSON.stringify({ access_token: jwt, refresh_token: 'r', token_type: 'bearer', expires_in: 3600, expires_at: exp, user: { id: 'u1', aud: 'authenticated', role: 'authenticated' } })])
  const textOf = async () => ((await page.textContent('body')) ?? '').replace(/\s+/g, ' ')

  await page.goto(`${BASE}#/lab/reports?key=${report.key}`)
  await page.getByText('TRAINING FUEL').first().waitFor({ timeout: 15000 })
  let text = await textOf()
  check(text.includes('MEAL QUALITY'), 'report: meal quality')
  check(text.includes('Energy availability') && text.includes('kcal/kg FFM'), 'report: energy availability')
  check(/Started fasted\s*\d+ of \d+/.test(text), 'report: fasted sessions')
  check(text.includes('FOOD TRIGGERS') && text.includes('days with a stool'), 'report: food triggers')
  check(text.includes('Digestion score'), 'report: digestion score line')
  check(await page.locator('a[href*="energy_availability"]').count() > 0, 'report: energy availability links to its history')
  check(await page.locator('a[href*="gut_day_score"]').count() > 0, 'report: digestion score links to its history')
  await page.screenshot({ path: path.join(OUT, 'nutrition-report.png'), fullPage: true })

  const long = sessions.find((s) => s.duration_min >= 90)!
  await page.goto(`${BASE}#/plan/sessions/${long.id}`)
  await page.getByText('FUEL', { exact: true }).first().waitFor({ timeout: 15000 })
  text = await textOf()
  check(text.includes('Since the last meal') && text.includes('Started fasted'), 'session: fuel card, fasted')
  check(text.includes('Carbs during') && text.includes('none logged') && text.includes('30–60 g an hour'), 'session: long session without fuel')
  check(/Protein in the 2 h after\s*\d+ g/.test(text), 'session: protein after')
  await page.locator('section', { hasText: 'FROM THE MEALS LOGGED' }).first().screenshot({ path: path.join(OUT, 'nutrition-session.png') })

  await page.goto(`${BASE}#/log?day=${day(50)}`)
  await page.getByText('FOOD AND DRINK', { exact: false }).first().waitFor({ timeout: 15000 })
  text = await textOf()
  check(text.includes('1.6 kcal/g') && text.includes('fibre 11 g/1,000 kcal') && text.includes('foods: eggs, refined grains'), 'log: meal profile line')
  check(!/meal score/i.test(text), 'log: no combined meal score')
  await page.screenshot({ path: path.join(OUT, 'nutrition-log.png'), fullPage: true })

  await page.goto(`${BASE}#/m/series:gut_day_score`)
  await page.waitForTimeout(2500)
  text = await textOf()
  check(/DIGESTION SCORE/i.test(text), 'metric page: digestion score opens')
  check(text.includes('100 /100') && !text.includes('15.4 /100'), 'metric page: only digestion values (none of energy availability)')
  await page.screenshot({ path: path.join(OUT, 'nutrition-gut-metric.png'), fullPage: true })
  await page.goto(`${BASE}#/m/series:energy_availability`)
  await page.waitForTimeout(2500)
  text = await textOf()
  check(/ENERGY AVAILABILITY/i.test(text), 'metric page: energy availability opens')
  await page.screenshot({ path: path.join(OUT, 'nutrition-ea-metric.png'), fullPage: true })

  for (const width of [390]) {
    await page.setViewportSize({ width, height: 2400 })
    await page.goto(`${BASE}#/lab/reports?key=${report.key}`)
    await page.getByText('TRAINING FUEL').first().waitFor({ timeout: 15000 })
    const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    check(over <= 0, `report: no sideways scroll at ${width} px`, String(over))
    await page.screenshot({ path: path.join(OUT, `nutrition-report-${width}.png`), fullPage: true })
  }
  check(errors.length === 0, 'no page errors', errors.join(' | '))
} finally {
  await b.close()
  server.kill()
}
process.exit(failures.length ? 1 : 0)
