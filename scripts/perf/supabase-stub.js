// Measurement harness for the original web dashboard (index.html). Replaces the supabase-js CDN script so the page can run
// without a Google sign-in and without touching the real database: a fake session and deterministic synthetic rows for
// every table the page reads. Build the page with scripts/perf/build-web-harness.py, serve the repo root, open
// /perf-harness/index.html.
//
// Query-string switches (so error paths can be exercised on purpose):
//   ?fail=meals,sleep_daily   those tables return an error
//   ?hang=meals               those tables never answer (tests the request timeout)
//   ?nosession=1              no session (the page must send you to login.html)
//   ?latency=80               milliseconds added to every answer
// window.__stubLog collects one entry per query: { table, columns, rows, bytes }.
(function () {
  const params = new URLSearchParams(location.search)
  const list = (k) => (params.get(k) || '').split(',').filter(Boolean)
  const failTables = new Set(list('fail'))
  const hangTables = new Set(list('hang'))
  const latency = Number(params.get('latency') || 0)
  window.__stubLog = []

  // Deterministic pseudo-random numbers (mulberry32) so two runs of the harness see identical rows.
  let seed = 20261013
  const rnd = () => { seed |= 0; seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296 }

  const DAYS = 90
  const today = new Date()
  const iso = (d) => d.toISOString().slice(0, 10)
  const dates = Array.from({ length: DAYS }, (_, i) => { const d = new Date(today); d.setDate(d.getDate() - (DAYS - 1 - i)); return iso(d) })

  const hrSeries = () => Array.from({ length: 1440 / 5 }, (_, i) => ({ offsetMinutes: i * 5, bpm: Math.round(55 + 30 * rnd()) }))

  const ROWS = {
    wearable_daily: dates.map((date, i) => ({
      id: i + 1, user_id: 'stub', date, steps: Math.round(9000 + 6000 * rnd()), rhr: Math.round(50 + 6 * rnd()), hrv: Math.round(55 + 30 * rnd()),
      vo2max: 58 + rnd() * 2, zone_minutes: Math.round(20 + 60 * rnd()), spo2_avg: 95 + rnd() * 3, source: 'health_connect',
      hr_timeseries: i % 2 === 0 ? hrSeries() : null,
    })),
    sleep_daily: dates.map((date, i) => ({ id: i + 1, user_id: 'stub', date, hours: 6 + 2 * rnd(), score: Math.round(60 + 30 * rnd()), respiratory_rate: 13 + 3 * rnd(), source: 'health_connect' })),
    wellbeing_daily: dates.filter((_, i) => i % 2 === 0).map((date, i) => ({ id: i + 1, date, energy: Math.round(4 + 5 * rnd()), mood: Math.round(4 + 5 * rnd()), stress: Math.round(2 + 5 * rnd()), soreness: Math.round(1 + 6 * rnd()) })),
    hydration_daily: dates.map((date, i) => ({ id: i + 1, date, ml: Math.round(1800 + 1500 * rnd()) })),
    body_metrics: dates.filter((_, i) => i % 3 === 0).map((date, i) => ({ id: i + 1, date, weight_kg: 74 + rnd() * 2, body_fat_pct: 14 + rnd(), lean_mass_kg: 62 + rnd() })),
    meals: dates.flatMap((date, i) => [0, 1, 2].map((k) => ({ id: i * 3 + k + 1, logged_at: `${date}T${String(8 + k * 5).padStart(2, '0')}:00:00+00:00`, description: `meal ${k}`, calories: Math.round(500 + 300 * rnd()), protein_g: Math.round(30 + 20 * rnd()), fat_g: Math.round(15 + 10 * rnd()), carbs_g: Math.round(50 + 40 * rnd()) }))),
    recovery_sessions: dates.filter((_, i) => i % 9 === 0).map((date, i) => ({ id: i + 1, name: 'sauna', category: 'heat', date, duration_min: 20, notes: null })),
    rest_days: dates.filter((_, i) => i % 7 === 0).map((date) => ({ date })),
    injuries: [{ id: 1, part: 'knee', type: 'strain', severity: 3, status: 'resolved', start_date: dates[10], end_date: dates[20], notes: null }],
    supplements: [{ id: 1, name: 'Magnesium', dose: '300 mg', time_of_day: 'night', status: 'active', start_date: dates[0], end_date: null, target: null, category: 'sleep' }],
    lab_draws: [{ id: 1, draw_date: dates[5], lab_name: 'Lab' }],
    lab_results: Array.from({ length: 60 }, (_, i) => ({ id: i + 1, draw_id: 1, marker_name: `marker ${i}`, value: i, unit: 'u', ref_low: 0, ref_high: 100, flag: null })),
    runs: dates.filter((_, i) => i % 4 === 0).map((date, i) => ({ id: i + 1, date, distance_km: 8, duration_min: 45, pace_min_per_km: 5.6, route_type: 'road', avg_hr: 150, rpe: 6, notes: null })),
    stool_log: [{ id: 1, occurred_at: `${dates[80]}T08:00:00+00:00`, bristol_type: 4, discomfort: 0, notes: null }],
    arousal_daily: dates.filter((_, i) => i % 3 === 0).map((date, i) => ({ id: i + 1, date, morning_erection_quality: 7, arousal_level: 6 })),
    illnesses: [],
    user_google_tokens: [],
  }

  // The chainable query builder. Only the part of the contract the page uses: it records select(), ignores filters, and
  // answers from ROWS (projected to the selected columns, like PostgREST would).
  function builder(table) {
    let columns = '*'
    const q = new Proxy({}, {
      get(_, prop) {
        if (prop === 'then') {
          return (resolve) => {
            const finish = () => {
              if (failTables.has(table)) { window.__stubLog.push({ table, columns, rows: 0, bytes: 0, error: true }); resolve({ data: null, error: { message: `stub failure for ${table}` } }); return }
              const all = ROWS[table] || []
              const cols = columns === '*' ? null : columns.split(',').map((c) => c.trim())
              const data = cols ? all.map((r) => Object.fromEntries(cols.filter((c) => c in r).map((c) => [c, r[c]]))) : all
              window.__stubLog.push({ table, columns, rows: data.length, bytes: JSON.stringify(data).length })
              resolve({ data, error: null })
            }
            if (hangTables.has(table)) return // never answers
            latency ? setTimeout(finish, latency) : finish()
          }
        }
        if (prop === 'select') return (c) => { columns = c || '*'; return q }
        if (prop === 'upsert' || prop === 'insert' || prop === 'update' || prop === 'delete') return () => Promise.resolve({ data: null, error: null })
        return () => q // gte, lte, order, eq, in, limit, ...
      },
    })
    return q
  }

  const session = params.get('nosession') ? null : { user: { id: 'stub-user' }, provider_refresh_token: null }
  const client = {
    auth: {
      getSession: async () => ({ data: { session } }),
      signOut: async () => ({ error: null }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
    },
    from: builder,
    functions: { invoke: async () => ({ data: null, error: { message: 'stub: no edge functions' } }) },
    channel: () => ({ on() { return this }, subscribe() { return this } }),
  }
  window.supabase = { createClient: (_url, _key, options) => { window.__stubClientOptions = options || null; return client } }
})()
