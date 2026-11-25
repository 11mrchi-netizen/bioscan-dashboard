// Me › INTIMACY against a mocked Supabase with INVENTED people and encounters (no real record is ever used here): every view,
// discreet mode, filling in an untagged encounter, editing a profile, logging and treating an STI test, phone width.
// Not part of CI.
//
//   cd console && npm run build && node e2e/intimacy.mts [screenshot dir]
//
// Exits 1 if a check fails.
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const here = path.dirname(fileURLToPath(import.meta.url))
const { chromium } = createRequire(import.meta.url)('playwright')
const OUT = path.resolve(process.argv[2] ?? path.join(here, 'out'))
fs.mkdirSync(OUT, { recursive: true })
const PORT = 4175
const BASE = `http://localhost:${PORT}/app.html`
const REF = 'ugfrglbcoivkprjqvjzz'

// --- invented data, dated back from today ---
const DAY = 864e5
const now = new Date()
const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())
const d = (ago: number) => new Date(today - ago * DAY).toISOString().slice(0, 10)
let seed = 11
const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648
type Row = Record<string, unknown>
const T: Record<string, Row[]> = { encounters: [], people: [], sexual_activity_daily: [], sti_tests: [], places: [], wearable_daily: [], sleep_daily: [], wellbeing_daily: [], score_history: [] }
const NAMES = ['Alex Morgan', 'Sam Rivera', 'Jo Kim', 'Robin Vale', 'Kai Ortega']
NAMES.forEach((name, i) => T.people.push({ id: i + 1, name, period: null, score: 6 + i, where_met: i % 2 ? 'App' : 'Friends', relationship: i === 0 ? 'Regular' : 'Casual', gender: 'F', country: i < 3 ? 'Testland' : 'Elsewhere', age_range: '25-30', body_type: null, activities: null, interested_in: null, first_met_date: d(200 + i * 30), availability: null, notes: i === 0 ? 'An invented note.' : null }))
for (let i = 6; i <= 24; i++) T.people.push({ id: i, name: `Person ${i}`, period: null, score: null, where_met: null, relationship: null, gender: null, country: null, age_range: null, body_type: null, activities: null, interested_in: null, first_met_date: null, availability: null, notes: null })
T.places.push({ id: 'p1', label: 'Home', osm_name: null })
let id = 1
const ACTS = [['Kissing', 'Oral (given)', 'PIV'], ['Kissing', 'PIV'], ['Oral (received)', 'PIV', 'Anal (active)'], ['Kissing', 'Oral (given)', 'Oral (received)', 'PIV']]
for (let ago = 110; ago >= 1; ago -= 3) {
  const who = ago % 4 === 0 ? 2 : ago % 5 === 0 ? 3 : 1
  const copied = ago > 70
  const tags = ACTS[ago % 4]
  T.encounters.push({
    id: id++, date: d(ago), status: 'logged', occurred_at: copied ? null : `${d(ago)}T${String(20 + (ago % 4)).padStart(2, '0')}:15:00+00:00`, encounter_type: null, location_type: null,
    duration_min: copied ? null : 60, activities: copied ? null : tags, my_rating: copied ? null : (tags.includes('Anal (active)') ? 5 : 3 + (ago % 2)), notes: copied ? null : 'invented',
    person_id: who, place_id: copied ? null : 'p1', sexual: true, format: copied ? null : ['1 on 1'], positions: copied ? null : (ago % 2 ? ['Missionary', 'Doggy'] : ['Cowgirl']), toys: null,
    protection: copied ? null : (who === 1 ? ['None'] : ['Condom']), ejaculation_location: null, rounds: 1 + (ago % 2), orgasm_count: 1, orgasm_intensity: 5 + (ago % 5), sheet_encounter_id: copied ? `sa:${ago}:${who}` : null,
  })
}
T.encounters.push({ id: id++, date: d(5), status: 'logged', occurred_at: null, encounter_type: 'Dinner', location_type: null, duration_min: null, activities: null, my_rating: null, notes: null, person_id: 4, place_id: null, sexual: false, format: null, positions: null, toys: null, protection: null, ejaculation_location: null, rounds: null, orgasm_count: null, orgasm_intensity: null, sheet_encounter_id: null })
for (let ago = 100; ago >= 0; ago -= 2) T.sexual_activity_daily.push({ id: id++, date: d(ago), activity_type: 'masturbation', instances: [{ orgasm: true, orgasmIntensity: 4 + (ago % 6), watchedPorn: ago % 3 === 0, loadSize: 2 + (ago % 3) }], notes: null })
T.sti_tests.push({ id: 1, test_date: d(30), infection: 'Chlamydia', result: 'positive', site: 'Urine', lab_name: 'Test lab', treated_on: null, notes: null })
T.sti_tests.push({ id: 2, test_date: d(30), infection: 'HIV', result: 'negative', site: 'Blood', lab_name: 'Test lab', treated_on: null, notes: null })
for (let ago = 200; ago >= 0; ago--) {
  T.wearable_daily.push({ date: d(ago), hrv: 50 + Math.round(rnd() * 15), rhr: 52 + Math.round(rnd() * 6) })
  T.sleep_daily.push({ date: d(ago), hours: 6 + rnd() * 2, score: 70 + Math.round(rnd() * 20), deep_min: 60 + Math.round(rnd() * 40) })
  T.wellbeing_daily.push({ date: d(ago), energy: 5 + (ago % 4), mood: 6, stress: 3, morning_erection_quality: 5 + (ago % 5), arousal_level: 4 + (ago % 6) })
  T.score_history.push({ day: d(ago), charge: 80 + (ago % 15) })
}

// --- a minimal PostgREST imitation ---
function match(rows: Row[], sp: URLSearchParams): Row[] {
  let out = [...rows]
  for (const [k, v] of sp) {
    if (['select', 'order', 'limit', 'offset', 'columns'].includes(k)) continue
    const [op, ...rest] = v.split('.'); const val = rest.join('.')
    if (op === 'eq') out = out.filter((r) => String(r[k]) === val)
    if (op === 'gte') out = out.filter((r) => String(r[k]) >= val)
    if (op === 'lte') out = out.filter((r) => String(r[k]) <= val)
  }
  return out
}
function select(table: string, sp: URLSearchParams): Row[] {
  const rows = match(T[table] ?? [], sp)
  const [col, dir] = (sp.get('order') ?? '').split(',')[0].split('.')
  if (col) rows.sort((a, b) => (String(a[col]) < String(b[col]) ? -1 : String(a[col]) > String(b[col]) ? 1 : 0) * (dir === 'desc' ? -1 : 1))
  const off = Number(sp.get('offset') ?? 0)
  return rows.slice(off, off + Number(sp.get('limit') ?? 1000))
}

const failures: string[] = []
const writes: { method: string; table: string; query: string; body: unknown }[] = []
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
    const req = route.request()
    const u = new URL(req.url())
    if (!u.pathname.startsWith('/rest/v1/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    const table = u.pathname.split('/').pop()!
    const method = req.method()
    if (method !== 'GET' && method !== 'HEAD') {
      const body = req.postData() ? JSON.parse(req.postData()) : null
      writes.push({ method, table, query: u.search, body })
      const hit = match(T[table] ?? [], u.searchParams)
      if (method === 'POST') T[table] = [...(T[table] ?? []), ...(Array.isArray(body) ? body : [body]).map((r: Row, i: number) => ({ id: 500 + writes.length * 10 + i, treated_on: null, site: null, lab_name: null, notes: null, ...r }))]
      if (method === 'PATCH') for (const r of hit) Object.assign(r, body)
      if (method === 'DELETE') T[table] = (T[table] ?? []).filter((r) => !hit.includes(r))
      return route.fulfill({ status: method === 'POST' ? 201 : 204, contentType: 'application/json', body: '' })
    }
    const rows = select(table, u.searchParams)
    if ((req.headers()['accept'] ?? '').includes('vnd.pgrst.object')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows[0] ?? null) })
    return route.fulfill({ status: 200, headers: { 'content-type': 'application/json', 'content-range': `0-${rows.length}/*` }, body: JSON.stringify(rows) })
  })
  const exp = Math.floor(Date.now() / 1000) + 3600
  const jwt = ['{"alg":"HS256","typ":"JWT"}', JSON.stringify({ sub: 'u1', exp, role: 'authenticated' }), 'sig'].map((s, i) => (i < 2 ? Buffer.from(s).toString('base64url') : s)).join('.')
  await ctx.addInitScript(([key, val]: string[]) => localStorage.setItem(key, val), [`sb-${REF}-auth-token`, JSON.stringify({ access_token: jwt, refresh_token: 'r', token_type: 'bearer', expires_in: 3600, expires_at: exp, user: { id: 'u1', aud: 'authenticated', role: 'authenticated' } })])
  const text = async (sel = '.it') => ((await page.textContent(sel)) ?? '').replace(/\s+/g, ' ')
  const go = async (q: string) => { await page.goto(`${BASE}#/me?tab=INTIMACY${q}`); await page.waitForSelector('.it .me-tiles, .it .it-table, .it .it-chips', { timeout: 15000 }); await page.waitForTimeout(700) }

  // overview
  await go('&range=1Y')
  let t = await text()
  const sexual = T.encounters.filter((e) => e.sexual).length
  check(t.includes(`Encounters${T.encounters.length}${sexual} with sex`) || t.includes(`Encounters ${T.encounters.length} ${sexual} with sex`), 'overview counts every encounter in a year', t.slice(0, 200))
  check(t.includes('BY WEEK') && t.includes('RHYTHM') && t.includes('CALENDAR') && t.includes('WHEN'), 'overview sections show')
  check((await page.locator('.it-cal-day').count()) === 53 * 7, 'the calendar is a year of days', String(await page.locator('.it-cal-day').count()))
  check(!t.includes('Alex Morgan'), 'discreet by default: no full name on the page')
  check(((await page.textContent('header, .shell-top, body')) ?? '').includes('Intimacy'), 'the top bar names the tab')
  await page.screenshot({ path: path.join(OUT, 'intimacy-overview.png'), fullPage: true })
  // a day of the calendar
  await page.locator('.it-cal-day').filter({ hasNot: page.locator('[disabled]') }).nth(53 * 7 - 8).click()
  check((await page.locator('.it-day').count()) === 1, 'a chosen day is read below the calendar')

  // encounters, and filling in a copied one
  await go('&view=encounters&range=1Y')
  t = await text()
  const untagged = T.encounters.filter((e) => e.sexual && !e.activities && !e.positions && !e.format && !e.protection).length
  check(t.includes(`NO TAGS · ${untagged}`), 'untagged encounters are counted', String(untagged))
  check((await page.locator('.it-table tbody tr').count()) === T.encounters.length, 'every encounter is a row')
  await page.getByRole('button', { name: `NO TAGS · ${untagged}` }).click()
  await page.waitForTimeout(300)
  check((await page.locator('.it-table tbody tr').count()) === untagged, 'NO TAGS filters to them', String(await page.locator('.it-table tbody tr').count()))
  await page.locator('.it-table tbody tr').first().click()
  await page.getByRole('button', { name: 'FILL IN TAGS AND RATING' }).click()
  await page.locator('.it-panel').getByRole('button', { name: 'PIV', exact: true }).click()
  await page.locator('.it-panel').getByRole('button', { name: 'Condom', exact: true }).click()
  await page.locator('.it-panel').getByRole('button', { name: '4', exact: true }).click()
  await page.locator('.it-panel').getByRole('button', { name: 'SAVE' }).click()
  await page.waitForTimeout(600)
  const patch = writes.find((w) => w.table === 'encounters' && w.method === 'PATCH')
  const keys = Object.keys((patch?.body ?? {}) as object).sort()
  check(!!patch && JSON.stringify(keys) === JSON.stringify(['activities', 'ejaculation_location', 'format', 'my_rating', 'positions', 'protection', 'toys']), 'filling in writes only the tags and rating', keys.join(','))
  check((patch?.body as Row)?.my_rating === 4 && JSON.stringify((patch?.body as Row)?.activities) === '["PIV"]' && /id=eq\.\d+/.test(patch?.query ?? ''), 'the right values to the right row', JSON.stringify(patch))
  await page.screenshot({ path: path.join(OUT, 'intimacy-encounters.png'), fullPage: true })

  // mix
  await go('&view=mix&range=1Y')
  t = await text()
  check(t.includes('HOW OFTEN · ACTIVITIES') && t.includes('Kissing') && t.includes('WHAT GOES TOGETHER'), 'the mix shows counts and co-occurrence')
  check(t.includes('AND YOUR RATING') && /Anal \(active\)\s?\+/.test(t), 'tag and rating: a planted higher rating shows', t.slice(t.indexOf('AND YOUR RATING'), t.indexOf('AND YOUR RATING') + 300))
  await page.screenshot({ path: path.join(OUT, 'intimacy-mix.png'), fullPage: true })

  // solo
  await go('&view=solo&range=1Y')
  t = await text()
  check(t.includes(`Sessions${T.sexual_activity_daily.length}`) || t.includes(`Sessions ${T.sexual_activity_daily.length}`), 'solo sessions counted', t.slice(0, 160))
  check(t.includes('WITH PORN AND WITHOUT') && t.includes('INTENSITY AND THE GAP BEFORE'), 'solo analyses show')

  // partners and a partner page; discreet off shows names
  await go('&view=partners&range=1Y')
  t = await text()
  check(t.includes(`People${T.people.length}`) || t.includes(`People ${T.people.length}`), 'every profile counted')
  check(t.includes('A. M.') && !t.includes('Alex Morgan'), 'partners by initials in discreet mode')
  check(t.includes('Search by name is off in discreet mode'), 'search is off in discreet mode')
  await page.getByRole('button', { name: 'DISCREET · ON' }).click()
  t = await text()
  check(t.includes('Alex Morgan'), 'discreet off shows names')
  await page.locator('.it-table tbody tr', { hasText: 'Alex Morgan' }).click()
  await page.waitForTimeout(300)
  t = await text()
  check(t.includes('WITH YOU') && t.includes('WHAT YOU DO TOGETHER') && t.includes('HISTORY') && t.includes('STI TEST WINDOWS'), 'the partner page', t.slice(0, 200))
  check(page.url().includes('person=1'), 'the partner page is a link of its own')
  await page.getByRole('button', { name: 'EDIT PROFILE' }).click()
  await page.locator('.it-form label', { hasText: 'COUNTRY' }).locator('input').fill('Newland')
  await page.getByRole('button', { name: 'SAVE PROFILE' }).click()
  await page.waitForTimeout(600)
  const pp = writes.find((w) => w.table === 'people')
  check((pp?.body as Row)?.country === 'Newland' && !('activities' in ((pp?.body ?? {}) as object)) && (pp?.query ?? '').includes('id=eq.1'), 'a profile edit writes the profile fields, not the lists', JSON.stringify(pp))
  await page.screenshot({ path: path.join(OUT, 'intimacy-partner.png'), fullPage: true })
  await page.getByRole('button', { name: 'DISCREET · OFF' }).click()

  // health: the open positive, treating it, logging a panel
  await go('&view=health')
  t = await text()
  check(t.includes('Chlamydia · positive') && t.includes('not marked treated'), 'an untreated positive is open')
  check(/Partners in the 90 days before: [A-Z]\./.test(t), 'its partners of the 90 days before, by initials', t.slice(t.indexOf('Partners in'), t.indexOf('Partners in') + 60))
  check((await page.locator('a.it-tile-link[href*="recovery.morning_erection"]').count()) === 1, 'morning erection opens its metric page')
  check(t.includes('Morning erection × sleep duration') && t.includes('r '), 'the phone correlations are read')
  await page.getByRole('button', { name: 'MARK TREATED TODAY' }).click()
  await page.waitForTimeout(500)
  const tr = writes.find((w) => w.table === 'sti_tests' && w.method === 'PATCH')
  check(/^\d{4}-\d{2}-\d{2}$/.test(String((tr?.body as Row)?.treated_on)) && (tr?.query ?? '').includes('id=eq.1'), 'mark treated sets the date', JSON.stringify(tr))
  await page.getByRole('button', { name: '+ LOG A TEST' }).click()
  await page.locator('.it-edit').getByRole('button', { name: 'Syphilis', exact: true }).click()
  await page.locator('.it-edit').getByRole('button', { name: 'HIV', exact: true }).click()
  await page.locator('.it-edit').getByRole('button', { name: 'SAVE 2' }).click()
  await page.waitForTimeout(600)
  const post = writes.find((w) => w.table === 'sti_tests' && w.method === 'POST')
  check(Array.isArray(post?.body) && (post?.body as Row[]).length === 2 && (post?.body as Row[]).every((r) => r.result === 'negative'), 'a panel saves one row per infection', JSON.stringify(post?.body))
  await page.screenshot({ path: path.join(OUT, 'intimacy-health.png'), fullPage: true })

  // effects
  await go('&view=effects&range=1Y')
  t = await text()
  check((await page.locator('.it-effects tbody tr').count()) === 11, 'every measure is a row of the days around it')
  check(t.includes('QUIET DAYS') && t.includes('NEXT DAY'), 'quiet days and offsets')
  await page.screenshot({ path: path.join(OUT, 'intimacy-effects.png'), fullPage: true })

  // phone width: no sideways scroll on any view
  await page.setViewportSize({ width: 390, height: 844 })
  for (const v of ['', 'encounters', 'mix', 'solo', 'partners', 'health', 'effects']) {
    await go(v ? `&view=${v}` : '')
    const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    check(over <= 0, `phone width fits: ${v || 'overview'}`, `${over}px over`)
  }
  await page.screenshot({ path: path.join(OUT, 'intimacy-phone.png'), fullPage: true })
  check(errors.length === 0, 'no page errors', errors.join(' | '))
} finally {
  await b.close()
  server.kill()
}
console.log(failures.length ? `${failures.length} failed` : 'all passed')
process.exit(failures.length ? 1 : 0)
