// Me › PERFORMANCE: the phone's charts read out their numbers on hover (and with the arrow keys). Against a mocked
// Supabase serving a PERFORMANCE me_view in the phone's shape: weekly hours by sport, threshold pace and VO2max. Not part of CI.
//
//   cd console && npm run build && node e2e/performance.mts [screenshot dir]
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

// --- a PERFORMANCE me_view, dated back from today so every range keeps it ---
const today = Date.now()
const dayOf = (ms: number) => new Date(ms).toISOString().slice(0, 10)
const monday = (ms: number) => { const d = new Date(ms); d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7)); return dayOf(d.getTime()) }
const weeks = Array.from({ length: 8 }, (_, i) => monday(today - (7 - i) * 7 * 864e5))
const paceDays = Array.from({ length: 6 }, (_, i) => dayOf(today - (25 - i * 5) * 864e5))
const view = { v: 1, tab: 'PERFORMANCE', sections: [
  { title: 'VO2MAX TILE', items: [{ kind: 'tile', label: 'VO2max', value: '60.0', unit: 'ml/kg/min', hue: 'm-vo2max', series: paceDays.map((d, i) => [d, 55 + i]) }] },
  { title: 'ALL SPORTS', items: [{ kind: 'bars', label: 'HOURS A WEEK BY SPORT', unit: 'h', weeks, last_partial: true, series: [
    { name: 'Running', hue: 'ft-domain-load', values: [3, 4, 5, 2.5, 6, 7.25, 4, 1] },
    { name: 'Strength', hue: 'ok', values: [1, 1.5, 0, 2, 1, 1.5, 1, 0.5] },
  ] }] },
  { title: 'LACTATE THRESHOLD', items: [{ kind: 'chart', label: 'THRESHOLD PACE', unit: '/km', decimals: 2, format: 'pace', caption: 'lower is faster',
    series: [{ name: 'Pace', hue: 'm-pace', points: paceDays.map((d, i) => [d, 6.5 - i * 0.2]) }] }] },
  { title: 'VO2MAX', items: [{ kind: 'chart', label: 'VO2MAX', unit: 'ml/kg/min', decimals: 1,
    series: [{ name: 'VO2max', hue: 'm-vo2max', points: paceDays.map((d, i) => [d, 55 + i]) }] }] },
] }
const T: Record<string, Record<string, unknown>[]> = { me_views: [{ tab: 'PERFORMANCE', built_at: new Date().toISOString(), view }] }

const failures: string[] = []
const check = (ok: boolean, what: string, detail = '') => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${detail ? ` (${detail})` : ''}`); if (!ok) failures.push(what) }

const server = spawn('npx', ['vite', 'preview', '--port', String(PORT), '--strictPort'], { cwd: path.join(here, '..'), stdio: 'ignore' })
const b = await chromium.launch()
try {
  for (let i = 0; i < 60; i++) { try { if ((await fetch(BASE)).ok) break } catch { /* not up */ } await new Promise((r) => setTimeout(r, 250)) }
  const ctx = await b.newContext({ viewport: { width: 1280, height: 1400 }, deviceScaleFactor: 1 })
  const page = await ctx.newPage()
  const errors: string[] = []
  page.on('pageerror', (e: Error) => errors.push(e.message))
  await ctx.route(`https://${REF}.supabase.co/**`, async (route: any) => {
    const u = new URL(route.request().url())
    if (!u.pathname.startsWith('/rest/v1/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    const table = u.pathname.split('/').pop()!
    let rows = [...(T[table] ?? [])]
    for (const [k, v] of u.searchParams) if (v.startsWith('eq.')) rows = rows.filter((r) => String(r[k]) === v.slice(3))
    if ((route.request().headers()['accept'] ?? '').includes('vnd.pgrst.object')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(rows[0] ?? null) })
    return route.fulfill({ status: 200, headers: { 'content-type': 'application/json', 'content-range': `0-${rows.length}/*` }, body: JSON.stringify(rows) })
  })
  const exp = Math.floor(Date.now() / 1000) + 3600
  const jwt = ['{"alg":"HS256","typ":"JWT"}', JSON.stringify({ sub: 'u1', exp, role: 'authenticated' }), 'sig'].map((s, i) => (i < 2 ? Buffer.from(s).toString('base64url') : s)).join('.')
  await ctx.addInitScript(([key, val]: string[]) => localStorage.setItem(key, val), [`sb-${REF}-auth-token`, JSON.stringify({ access_token: jwt, refresh_token: 'r', token_type: 'bearer', expires_in: 3600, expires_at: exp, user: { id: 'u1', aud: 'authenticated', role: 'authenticated' } })])
  await page.goto(`${BASE}#/me?tab=PERFORMANCE`)
  await page.waitForSelector('.me-hoverable', { timeout: 15000 })
  const plots = page.locator('svg.me-hoverable')
  check((await plots.count()) === 4, 'the tile sparkline and the three charts can be hovered', String(await plots.count()))

  // The tile's sparkline reads out too.
  const sp = plots.nth(0), spb = (await sp.boundingBox())!
  await page.mouse.move(spb.x + spb.width * 0.99, spb.y + spb.height / 2)
  check(((await page.locator('.me-readout').first().textContent()) ?? '').includes('60 ml/kg/min'), 'a tile sparkline reads its last value', (await page.locator('.me-readout').first().textContent()) ?? '')
  await page.screenshot({ path: path.join(OUT, 'performance-tile.png'), clip: { x: spb.x - 30, y: spb.y - 90, width: spb.width + 220, height: spb.height + 120 } })
  await page.mouse.move(0, 0)
  check((await page.locator('.me-readout').count()) === 0, 'no readout before hovering')

  // The bars: hover the sixth week (7.25 h running + 1.5 h strength).
  const bars = plots.nth(1), bb = (await bars.boundingBox())!
  const bw = (600 - 40 - 4 * 7) / 8
  await page.mouse.move(bb.x + ((40 + 5 * (bw + 4) + bw / 2) / 600) * bb.width, bb.y + bb.height / 2)
  const r1 = ((await page.locator('.me-readout').first().textContent()) ?? '')
  check(r1.includes('8.8 h') && r1.includes('Running') && r1.includes('7.3 h') && r1.includes('Strength') && r1.includes('1.5 h'), 'a hovered week reads its total and each sport', r1)
  await page.screenshot({ path: path.join(OUT, 'performance-bars.png'), clip: { x: bb.x - 20, y: bb.y - 40, width: bb.width + 40, height: bb.height + 80 } })
  // The last week is partial.
  await bars.focus(); await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowRight'); await page.keyboard.press('ArrowRight')
  check(((await page.locator('.me-readout').first().textContent()) ?? '').includes('SO FAR'), 'the arrow keys move the readout, and the partial week says so')
  await page.mouse.move(0, 0); await page.locator('body').click({ position: { x: 1, y: 1 } })

  // Threshold pace: the first day reads 6:30 /km, the last 5:30 /km.
  const pace = plots.nth(2), pb = (await pace.boundingBox())!
  await page.mouse.move(pb.x + pb.width * 0.11, pb.y + pb.height / 2)
  const r2 = ((await page.locator('.me-readout').first().textContent()) ?? '')
  check(r2.includes('6:30 /km'), 'hovering the start of the pace line reads its pace', r2)
  await page.mouse.move(pb.x + pb.width * 0.99, pb.y + pb.height / 2)
  const r3 = ((await page.locator('.me-readout').first().textContent()) ?? '')
  check(r3.includes('5:30 /km'), 'hovering the end reads the last pace', r3)
  await page.screenshot({ path: path.join(OUT, 'performance-pace.png'), clip: { x: pb.x - 20, y: pb.y - 40, width: pb.width + 40, height: pb.height + 80 } })
  await page.mouse.move(0, 0)
  check((await page.locator('.me-readout').count()) === 0, 'the readout goes when the pointer leaves')

  const vo = plots.nth(3), vb = (await vo.boundingBox())!
  await page.mouse.move(vb.x + vb.width * 0.6, vb.y + vb.height / 2)
  const r4 = ((await page.locator('.me-readout').first().textContent()) ?? '')
  check(/5[5-9]\.0 ml\/kg\/min/.test(r4), 'VO2max reads with its unit', r4)
  check(errors.length === 0, 'no page errors', errors.join(' | '))
  await page.screenshot({ path: path.join(OUT, 'performance.png'), fullPage: true })
} finally {
  await b.close()
  server.kill()
}
process.exit(failures.length ? 1 : 0)
