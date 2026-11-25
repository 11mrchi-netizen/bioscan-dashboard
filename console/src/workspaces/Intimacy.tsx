// Me › INTIMACY on the web: the phone's tab (android ui/screens/me/IntimacyPage.kt) in more depth. Seven views, each a link of
// its own (`?view=`, lib/nav.ts INTIMACY_VIEWS): the overview, every encounter, the mix, solo, the partners (and one partner,
// `&person=`), health (STI tests, morning signs) and the days around an activity. The rules are in lib/intimacy.ts.
//
// Highly private: discreet mode (on unless turned off on this browser) shows partners by their initials and folds notes.
// Writes: an encounter's tags and rating, a partner's profile, STI tests. Everything else is the phone's.
import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useLoadEffect } from '../lib/useLoadEffect'
import { localDaysAgo, localToday } from '../lib/dates'
import { INTIMACY_VIEWS } from '../lib/nav'
import type { EChartsCoreOption } from '../lib/echarts'
import { PALETTE } from '../lib/palette'
import {
  addDays, completeness, cooccurrence, dayTallies, type Encounter, gapStats, hourGrid, inRange, intimacyStats, isCopied, monthlyTally,
  needsTags, pearson, diffInterval, soloPoints, TAG_FIELDS, type TagKey, tagOutcomes, tally, wallClock, weekly,
} from '../lib/intimacy'
import { type IntimacyData, loadIntimacy } from '../lib/intimacyData'
import {
  AXES, Card, catAxis, Chart, EncounterPanel, f1, fullDate, HUE, NamesContext, Note, plural, Rows, SELF_Q, ShareBars, shortDate, signed, Tiles,
  timeAxis, useName, valueAxis,
} from './IntimacyKit'
import { IntimacyPartners } from './IntimacyPartners'
import { IntimacyEffects, IntimacyHealth } from './IntimacyHealth'
import './Intimacy.css'

const RANGES = [{ id: '28D', days: 28 }, { id: '90D', days: 90 }, { id: '1Y', days: 365 }, { id: 'ALL', days: 0 }]
const DISCREET_KEY = 'bioscan.intimacy.discreet'
const readDiscreet = () => { try { return localStorage.getItem(DISCREET_KEY) !== 'off' } catch { return true } }

export interface ViewProps {
  data: IntimacyData
  from: string | null
  to: string
  rangeId: string
  onChanged: () => void
  open: (e: Encounter) => void
  set: (k: string, v: string | null, also?: Record<string, string | null>) => void
  params: URLSearchParams
}

export default function Intimacy() {
  const [params, setParams] = useSearchParams()
  const view = INTIMACY_VIEWS.find((v) => v.value === params.get('view')) ?? INTIMACY_VIEWS[0]
  const range = RANGES.find((r) => r.id === params.get('range')) ?? RANGES[1]
  const set = (k: string, v: string | null, also: Record<string, string | null> = {}) => {
    const n = new URLSearchParams(params)
    for (const [key, val] of Object.entries({ [k]: v, ...also })) { if (val == null) n.delete(key); else n.set(key, val) }
    setParams(n, { replace: true })
  }
  const [discreet, setDiscreet] = useState(readDiscreet)
  const toggleDiscreet = () => { const next = !discreet; setDiscreet(next); try { localStorage.setItem(DISCREET_KEY, next ? 'on' : 'off') } catch { /* private window: this visit only */ } }
  const [reload, setReload] = useState(0)
  const [data, setData] = useState<IntimacyData | null>(null)
  const [openId, setOpenId] = useState<number | null>(null)
  useEffect(() => { setOpenId(null) }, [view.id]) // the panel belongs to the view it was opened from
  const status = useLoadEffect(async (cur) => { const d = await loadIntimacy(); if (cur()) setData(d) }, [reload])
  const names = useMemo(() => ({ discreet, people: new Map((data?.people ?? []).map((p) => [p.id, p])) }), [discreet, data])
  const to = localToday()
  const from = range.days ? localDaysAgo(range.days - 1) : null
  const opened = data?.encounters.find((e) => e.id === openId) ?? null
  const props: ViewProps | null = data ? { data, from, to, rangeId: range.id, onChanged: () => setReload((n) => n + 1), open: (e) => setOpenId(e.id), set, params } : null

  return (
    <NamesContext.Provider value={names}>
      <div className="it ft-skin">
        <div className="it-head">
          <nav className="it-views" aria-label="Intimacy views">
            {INTIMACY_VIEWS.map((v) => <button key={v.id} className="ft-chip" aria-current={v.id === view.id} onClick={() => { const n = new URLSearchParams(params); n.delete('person'); if (v.value) n.set('view', v.value); else n.delete('view'); setParams(n, { replace: true }) }}>{v.label.toUpperCase()}</button>)}
          </nav>
          <div className="it-controls">
            {view.id !== 'health' && <div className="me-ranges">{RANGES.map((r) => <button key={r.id} className="ft-chip" aria-current={r.id === range.id} onClick={() => set('range', r.id)}>{r.id}</button>)}</div>}
            <button className="ft-chip" aria-pressed={discreet} onClick={toggleDiscreet} title="Names as initials and notes folded, for a shared screen">{discreet ? 'DISCREET · ON' : 'DISCREET · OFF'}</button>
          </div>
        </div>
        <p className="it-info">{SELF_Q}</p>
        {status.error && <p className="it-error">Could not load: {status.error} <button className="it-link" onClick={status.retry}>RETRY</button></p>}
        {status.loading && !data && <p className="it-info">Loading…</p>}
        {props && (
          view.id === 'overview' ? <Overview {...props} />
            : view.id === 'encounters' ? <Encounters {...props} />
            : view.id === 'mix' ? <Mix {...props} />
            : view.id === 'solo' ? <Solo {...props} />
            : view.id === 'partners' ? <IntimacyPartners {...props} />
            : view.id === 'health' ? <IntimacyHealth {...props} />
            : <IntimacyEffects {...props} />
        )}
        {opened && data && <EncounterPanel key={opened.id} e={opened} places={data.places} onClose={() => setOpenId(null)} onSaved={() => setReload((n) => n + 1)} />}
      </div>
    </NamesContext.Provider>
  )
}

// ---- overview ----

type CalFilter = 'all' | 'partnered' | 'solo' | 'meetups'

function Overview({ data, from, to, rangeId, open }: ViewProps) {
  const { encounters, solo } = data
  const stats = useMemo(() => intimacyStats(encounters, solo, from, to), [encounters, solo, from, to])
  const days = useMemo(() => dayTallies(encounters, solo), [encounters, solo])
  const firstDay = useMemo(() => [...encounters.map((e) => e.date), ...solo.map((s) => s.date)].sort()[0] ?? to, [encounters, solo, to])
  const weekFrom = from ?? firstDay
  const weeks = useMemo(() => weekly(days, weekFrom < firstDay ? firstDay : weekFrom, to), [days, weekFrom, firstDay, to])
  const activeIn = useMemo(() => [...days].filter(([d, t]) => inRange(d, from, to) && (t.partnered || t.solo)).map(([d]) => d), [days, from, to])
  const gaps = useMemo(() => gapStats(activeIn, to), [activeIn, to])
  const grid = useMemo(() => hourGrid(encounters.filter((e) => inRange(e.date, from, to))), [encounters, from, to])
  const [cal, setCal] = useState<CalFilter>('all')
  const [day, setDay] = useState<string | null>(null)
  const barrierKnown = stats.withBarrier + stats.withoutBarrier

  const weekOption = useMemo<EChartsCoreOption>(() => ({
    ...AXES, xAxis: catAxis(weeks.map((w) => shortDate(w.week))), yAxis: valueAxis('days'),
    series: [
      { name: 'With a partner', type: 'bar', stack: 'd', data: weeks.map((w) => w.partneredDays), itemStyle: { color: HUE.partnered } },
      { name: 'Solo', type: 'bar', stack: 'd', data: weeks.map((w) => w.soloDays), itemStyle: { color: HUE.solo } },
    ],
  }), [weeks])
  const gapOption = useMemo<EChartsCoreOption>(() => {
    const counts = new Map<number, number>()
    for (const g of gaps.gaps) counts.set(Math.min(g, 14), (counts.get(Math.min(g, 14)) ?? 0) + 1)
    const xs = Array.from({ length: 14 }, (_, i) => i + 1)
    return { ...AXES, legend: undefined, tooltip: { trigger: 'axis' }, xAxis: catAxis(xs.map((x) => (x === 14 ? '14+' : String(x)))), yAxis: valueAxis('times'), series: [{ name: 'Gaps', type: 'bar', data: xs.map((x) => counts.get(x) ?? 0), itemStyle: { color: PALETTE.emerald } }] }
  }, [gaps])

  return (
    <div className="it-views-body">
      <Card title={rangeId === 'ALL' ? 'ALL OF IT' : `LAST ${rangeId.replace('D', ' DAYS').replace('1Y', '1 YEAR')}`} wide>
        <Tiles tiles={[
          { label: 'Encounters', value: String(stats.encounters), sub: `${stats.sexual} with sex`, hue: 'm-sex-partnered' },
          { label: 'Partners', value: String(stats.partners), sub: `${stats.newPartners} new` },
          { label: 'Rounds', value: String(stats.rounds), sub: `${stats.orgasms} orgasms` },
          { label: 'Solo', value: String(stats.soloSessions), sub: `on ${plural(stats.soloDays, 'day')}`, hue: 'm-sex-solo' },
          { label: 'Active days', value: String(stats.activeDays), sub: gaps.current != null ? `last ${gaps.current === 0 ? 'today' : `${gaps.current} d ago`}` : null },
          { label: 'With a barrier', value: barrierKnown ? `${stats.withBarrier} of ${barrierKnown}` : '–', sub: stats.protectionUnknown ? `${stats.protectionUnknown} not recorded` : null },
          { label: 'Your rating', value: stats.avgRating != null ? f1(stats.avgRating) : '–', sub: stats.ratedN ? `average of ${stats.ratedN}, out of 5` : 'none rated' },
        ]} />
      </Card>

      <div className="it-grid">
        <Card title="BY WEEK" aside="ACTIVE DAYS" info="Days with any partnered sex and days with solo, per Monday-to-Sunday week. This week is still filling in.">
          {weeks.length ? <Chart option={weekOption} label="Active days per week" /> : <p className="it-empty">Nothing in this range.</p>}
        </Card>
        <Card title="RHYTHM" info="Gaps are the days between two active days (partnered or solo) in the range.">
          <Rows rows={[
            { label: 'Usual gap (median)', value: gaps.median != null ? `${f1(gaps.median)} d` : '–' },
            { label: 'Longest gap', value: gaps.longest ? `${gaps.longest.days} d` : '–', sub: gaps.longest ? `${fullDate(gaps.longest.from)} to ${fullDate(gaps.longest.to)}` : undefined },
            { label: 'Longest run of active days', value: gaps.longestStreak ? plural(gaps.longestStreak, 'day') : '–' },
            { label: 'Since the last', value: gaps.current != null ? plural(gaps.current, 'day') : '–' },
          ]} />
          {gaps.gaps.length > 1 && <Chart option={gapOption} height={150} label="How long the gaps were" />}
        </Card>
      </div>

      <Card title="CALENDAR" wide aside={<span className="it-chips">{(['all', 'partnered', 'solo', 'meetups'] as CalFilter[]).map((c) => <button key={c} className="ft-chip" aria-current={cal === c} onClick={() => setCal(c)}>{c.toUpperCase()}</button>)}</span>}
        info="A year of days ending this week. A darker square is more that day; a split square had both. Choose a day to read it.">
        <YearCalendar days={days} to={to} filter={cal} selected={day} onPick={setDay} />
        {day && <DayDetail day={day} data={data} open={open} />}
      </Card>

      <Card title="WHEN" aside={`${grid.n} WITH A TIME`} wide info="Partnered sex with a time of day, by weekday and hour (the phone's clock). Only encounters logged with a time count.">
        {grid.n ? <HourGrid grid={grid.grid} /> : <p className="it-empty">No encounter in this range has a time yet.</p>}
      </Card>
    </div>
  )
}

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

function YearCalendar({ days, to, filter, selected, onPick }: { days: Map<string, { partnered: number; solo: number; meetups: number }>; to: string; filter: CalFilter; selected: string | null; onPick: (d: string) => void }) {
  const wd = (new Date(`${to}T00:00:00Z`).getUTCDay() + 6) % 7
  const end = addDays(to, 6 - wd)
  const start = addDays(end, -53 * 7 + 1)
  const cells: string[] = []
  for (let d = start; d <= end; d = addDays(d, 1)) cells.push(d)
  const shade = (n: number) => (n <= 0 ? 0 : Math.min(1, 0.35 + 0.22 * n))
  return (
    <div className="it-cal-wrap">
      <div className="it-cal" role="grid" aria-label="A year of days">
        {cells.map((d) => {
          const t = days.get(d)
          const p = filter === 'all' || filter === 'partnered' ? t?.partnered ?? 0 : 0
          const s = filter === 'all' || filter === 'solo' ? t?.solo ?? 0 : 0
          const m = filter === 'meetups' ? t?.meetups ?? 0 : 0
          const future = d > to
          const bg = p && s
            ? `linear-gradient(135deg, color-mix(in srgb, var(--m-sex-partnered) ${shade(p) * 100}%, transparent) 50%, color-mix(in srgb, var(--m-sex-solo) ${shade(s) * 100}%, transparent) 50%)`
            : p ? `color-mix(in srgb, var(--m-sex-partnered) ${shade(p) * 100}%, transparent)`
            : s ? `color-mix(in srgb, var(--m-sex-solo) ${shade(s) * 100}%, transparent)`
            : m ? `color-mix(in srgb, var(--ft-platinum) ${shade(m) * 100}%, transparent)` : undefined
          const words = [p && `${p} partnered`, s && `${s} solo`, m && `${m} meetup${m > 1 ? 's' : ''}`].filter(Boolean).join(', ') || 'nothing logged'
          return <button key={d} className="it-cal-day" aria-current={selected === d} disabled={future} style={bg ? { background: bg } : undefined} title={`${fullDate(d)}: ${words}`} aria-label={`${fullDate(d)}: ${words}`} onClick={() => onPick(d)} />
        })}
      </div>
      <div className="it-legend mono"><span><i style={{ background: 'var(--m-sex-partnered)' }} />PARTNERED</span><span><i style={{ background: 'var(--m-sex-solo)' }} />SOLO</span><span><i style={{ background: 'var(--ft-platinum)' }} />MEETUP</span></div>
    </div>
  )
}

function DayDetail({ day, data, open }: { day: string; data: IntimacyData; open: (e: Encounter) => void }) {
  const name = useName()
  const es = data.encounters.filter((e) => e.date === day)
  const s = data.solo.find((x) => x.date === day)
  return (
    <div className="it-day">
      <div className="me-label">{fullDate(day).toUpperCase()}</div>
      {!es.length && !s?.instances.length && <p className="it-empty">Nothing logged that day.</p>}
      <ul className="me-rows">
        {es.map((e) => (
          <li key={e.id} className="me-row">
            <span className="me-row-label"><button className="it-link" onClick={() => open(e)}>{name(e.person_id)} · {e.sexual ? (e.format?.join(' / ') || 'sex') : e.encounter_type ?? 'meetup'} ›</button></span>
            <span className="me-row-value">{e.sexual ? plural(e.rounds ?? 1, 'round') : ''}</span>
          </li>
        ))}
        {s?.instances.length ? <li className="me-row"><span className="me-row-label">Solo</span><span className="me-row-value">{plural(s.instances.length, 'session')}</span><span className="me-row-sub">{s.instances.map((i) => [i.orgasmIntensity != null && `intensity ${i.orgasmIntensity}/10`, i.loadSize != null && `load ${i.loadSize}`, i.watchedPorn != null && (i.watchedPorn ? 'porn' : 'no porn')].filter(Boolean).join(' · ')).join('; ')}</span></li> : null}
      </ul>
      <Note text={s?.notes} />
    </div>
  )
}

function HourGrid({ grid }: { grid: number[][] }) {
  const max = Math.max(1, ...grid.flat())
  return (
    <div className="it-hours-wrap">
      <div className="it-hours" role="grid" aria-label="Weekday by hour">
        <span />
        {Array.from({ length: 24 }, (_, h) => <span key={h} className="it-hours-h mono">{h % 3 === 0 ? String(h).padStart(2, '0') : ''}</span>)}
        {grid.map((row, w) => [
          <span key={`l${w}`} className="it-hours-d mono">{WEEKDAYS[w].toUpperCase()}</span>,
          ...row.map((n, h) => <span key={`${w}-${h}`} className="it-hours-c" title={`${WEEKDAYS[w]} ${String(h).padStart(2, '0')}:00 · ${n}`} style={n ? { background: `color-mix(in srgb, var(--m-sex-partnered) ${20 + 80 * (n / max)}%, transparent)` } : undefined}>{n || ''}</span>),
        ])}
      </div>
    </div>
  )
}

// ---- every encounter ----

type SortKey = 'date' | 'rating' | 'intensity' | 'rounds' | 'duration'

function Encounters({ data, from, to, params, set, open }: ViewProps) {
  const name = useName()
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'date', desc: true })
  const partner = params.get('partner')
  const tagField = (params.get('field') ?? '') as TagKey | ''
  const tag = params.get('tag')
  const only = params.get('only') // 'sex' | 'meetups' | 'untagged'
  const inWin = data.encounters.filter((e) => inRange(e.date, from, to))
  const partnersIn = [...new Set(inWin.flatMap((e) => (e.person_id != null ? [e.person_id] : [])))].sort((a, b) => name(a).localeCompare(name(b)))
  const rows = inWin.filter((e) =>
    (!partner || String(e.person_id) === partner)
    && (!tagField || !tag || (e[tagField] ?? []).includes(tag))
    && (only !== 'sex' || e.sexual) && (only !== 'meetups' || !e.sexual) && (only !== 'untagged' || needsTags(e)))
  const val = (e: Encounter): number | string | null => ({ date: `${e.date}${e.occurred_at ?? ''}`, rating: e.my_rating, intensity: e.orgasm_intensity, rounds: e.rounds, duration: e.duration_min })[sort.key]
  const sorted = [...rows].sort((a, b) => {
    const x = val(a), y = val(b)
    if (x == null && y == null) return b.date.localeCompare(a.date)
    if (x == null) return 1
    if (y == null) return -1
    return (x < y ? -1 : x > y ? 1 : 0) * (sort.desc ? -1 : 1)
  })
  const comp = completeness(rows)
  const untagged = inWin.filter(needsTags).length
  const copied = inWin.filter(isCopied).length
  const head = (key: SortKey, label: string) => (
    <th><button className="it-th mono" aria-sort={sort.key === key ? (sort.desc ? 'descending' : 'ascending') : 'none'} onClick={() => setSort((s) => ({ key, desc: s.key === key ? !s.desc : true }))}>{label}{sort.key === key ? (sort.desc ? ' ↓' : ' ↑') : ''}</button></th>
  )
  const tagValues = tagField ? tally(inWin, (e) => e[tagField]).map((x) => x[0]) : []
  return (
    <div className="it-views-body">
      <Card title="ENCOUNTERS" aside={`${rows.length} OF ${inWin.length}`} wide>
        <div className="it-filters">
          <label className="it-field"><span className="me-label">PARTNER</span>
            <select value={partner ?? ''} onChange={(e) => set('partner', e.target.value || null)}><option value="">Everyone</option>{partnersIn.map((id) => <option key={id} value={id}>{name(id)}</option>)}</select></label>
          <label className="it-field"><span className="me-label">TAG LIST</span>
            <select value={tagField} onChange={(e) => set('field', e.target.value || null, { tag: null })}><option value="">Any</option>{TAG_FIELDS.map((f) => <option key={f.key} value={f.key}>{f.label.toLowerCase()}</option>)}</select></label>
          {tagField && <label className="it-field"><span className="me-label">TAG</span>
            <select value={tag ?? ''} onChange={(e) => set('tag', e.target.value || null)}><option value="">Any</option>{tagValues.map((t) => <option key={t} value={t}>{t}</option>)}</select></label>}
          <div className="it-chips">{[['', 'ALL'], ['sex', 'WITH SEX'], ['meetups', 'MEETUPS'], ['untagged', `NO TAGS · ${untagged}`]].map(([k, l]) => <button key={k} className="ft-chip" aria-current={(only ?? '') === k} onClick={() => set('only', k || null)}>{l}</button>)}</div>
        </div>
        {untagged > 0 && <p className="it-info">{plural(untagged, 'encounter')} in this range {untagged === 1 ? 'has' : 'have'} no tags{copied ? ` (${copied} copied from the old day rows)` : ''}. Open one to fill them in: the Mix view reads only tagged encounters.</p>}
        <div className="it-table-wrap">
          <table className="it-table">
            <thead><tr>{head('date', 'DATE')}<th className="mono">PARTNER</th><th className="mono">WHAT</th>{head('rounds', 'ROUNDS')}{head('intensity', 'INTENSITY')}{head('rating', 'RATING')}{head('duration', 'MIN')}<th className="mono">PROTECTION</th><th className="mono">TAGS</th></tr></thead>
            <tbody>
              {sorted.map((e) => {
                const t = wallClock(e.occurred_at)
                const tagN = TAG_FIELDS.filter((f) => f.key !== 'format' && f.key !== 'protection').reduce((s, f) => s + (e[f.key]?.length ?? 0), 0)
                return (
                  <tr key={e.id} onClick={() => open(e)} tabIndex={0} onKeyDown={(ev) => { if (ev.key === 'Enter') open(e) }}>
                    <td className="it-num">{fullDate(e.date)}{t ? <span className="it-dim"> {String(t.hour).padStart(2, '0')}:{String(t.minute).padStart(2, '0')}</span> : null}</td>
                    <td>{name(e.person_id)}</td>
                    <td>{e.sexual ? (e.format?.join(' / ') || 'sex') : <span className="it-dim">{e.encounter_type ?? 'meetup'}</span>}</td>
                    <td className="it-num">{e.sexual ? e.rounds ?? '–' : ''}</td>
                    <td className="it-num">{e.orgasm_intensity ?? '–'}</td>
                    <td className="it-num">{e.my_rating ?? '–'}</td>
                    <td className="it-num">{e.duration_min ?? '–'}</td>
                    <td>{e.protection?.join(', ') ?? <span className="it-dim">–</span>}</td>
                    <td>{tagN ? tagN : e.sexual ? <span className="it-warn mono">NONE</span> : ''}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        {!rows.length && <p className="it-empty">No encounter matches.</p>}
        <div className="it-complete mono" aria-label="How many of these rows carry each field">
          {comp.map((c) => <span key={c.field}>{c.field.toUpperCase()} {c.n}/{c.field === 'activities' || c.field === 'positions' || c.field === 'protection' ? rows.filter((e) => e.sexual).length : rows.length}</span>)}
        </div>
      </Card>
    </div>
  )
}

// ---- the mix ----

function Mix({ data, from, to, params, set }: ViewProps) {
  const field = TAG_FIELDS.find((f) => f.key === params.get('field')) ?? TAG_FIELDS[1]
  const pick = (e: Encounter) => e[field.key]
  const sexual = data.encounters.filter((e) => e.sexual && inRange(e.date, from, to))
  const tagged = sexual.filter((e) => (pick(e)?.length ?? 0) > 0)
  const counts = tally(sexual, pick)
  const co = useMemo(() => cooccurrence(tagged, pick, 12), [tagged, field.key]) // eslint-disable-line react-hooks/exhaustive-deps
  const byRating = useMemo(() => tagOutcomes(sexual, pick, (e) => e.my_rating), [sexual, field.key]) // eslint-disable-line react-hooks/exhaustive-deps
  const byIntensity = useMemo(() => tagOutcomes(sexual, pick, (e) => e.orgasm_intensity), [sexual, field.key]) // eslint-disable-line react-hooks/exhaustive-deps
  const monthly = useMemo(() => monthlyTally(tagged, pick), [tagged, field.key]) // eslint-disable-line react-hooks/exhaustive-deps
  const top = counts.slice(0, 10).map((c) => c[0])
  const monthMax = Math.max(1, ...top.flatMap((t) => monthly.byTag.get(t) ?? []))
  const coMax = Math.max(1, ...co.counts.flat())
  return (
    <div className="it-views-body">
      <div className="it-chips">{TAG_FIELDS.map((f) => <button key={f.key} className="ft-chip" aria-current={f.key === field.key} onClick={() => set('field', f.key)}>{f.label}</button>)}</div>
      <p className="it-info">{tagged.length} of {plural(sexual.length, 'encounter')} with sex in this range carry {field.label.toLowerCase()}. Everything below reads those.</p>
      <div className="it-grid">
        <Card title={`HOW OFTEN · ${field.label}`} aside={`${tagged.length} TAGGED`}>
          <ShareBars rows={counts.slice(0, 15)} hue="m-sex-partnered" total={tagged.length} />
        </Card>
        <Card title="MONTH BY MONTH" info="How many encounters each month carried it. A darker cell is more.">
          {monthly.months.length && top.length ? (
            <div className="it-table-wrap"><table className="it-heat">
              <thead><tr><th />{monthly.months.map((m) => <th key={m} className="mono">{m.slice(2).replace('-', '/')}</th>)}</tr></thead>
              <tbody>{top.map((t) => <tr key={t}><th>{t}</th>{(monthly.byTag.get(t) ?? []).map((n, i) => <td key={i} style={n ? { background: `color-mix(in srgb, var(--m-sex-partnered) ${15 + 85 * (n / monthMax)}%, transparent)` } : undefined}>{n || ''}</td>)}</tr>)}</tbody>
            </table></div>
          ) : <p className="it-empty">Nothing tagged yet.</p>}
        </Card>
      </div>
      <Card title="WHAT GOES TOGETHER" wide info="Encounters on which both were logged. The diagonal is how often each was logged at all.">
        {co.labels.length > 1 ? (
          <div className="it-table-wrap"><table className="it-heat it-co">
            <thead><tr><th />{co.labels.map((l) => <th key={l} className="it-co-h"><span>{l}</span></th>)}</tr></thead>
            <tbody>{co.labels.map((a, i) => <tr key={a}><th>{a}</th>{co.counts[i].map((n, j) => <td key={j} className={i === j ? 'it-co-diag' : ''} title={`${a} + ${co.labels[j]}: ${n}`} style={n && i !== j ? { background: `color-mix(in srgb, var(--ft-platinum) ${10 + 60 * (n / coMax)}%, transparent)` } : undefined}>{n || ''}</td>)}</tr>)}</tbody>
          </table></div>
        ) : <p className="it-empty">Needs at least two different values tagged.</p>}
      </Card>
      <div className="it-grid">
        <OutcomeCard title="AND YOUR RATING" unit="/ 5" rows={byRating} />
        <OutcomeCard title="AND ORGASM INTENSITY" unit="/ 10" rows={byIntensity} />
      </div>
    </div>
  )
}

function OutcomeCard({ title, unit, rows }: { title: string; unit: string; rows: ReturnType<typeof tagOutcomes> }) {
  return (
    <Card title={title} info="The average with it against the tagged encounters without it, with a 95% interval. Many tags are compared at once, so one clear row can be chance; an association, never a cause.">
      {rows.length ? (
        <Rows rows={rows.slice(0, 10).map((r) => ({
          label: r.tag,
          value: <span className={r.clear ? '' : 'it-dim'}>{signed(r.diff)}</span>,
          sub: `${f1(r.meanWith)} ${unit} with (${r.nWith}) · ${f1(r.meanWithout)} without (${r.nWithout}) · interval ${signed(r.lo)} to ${signed(r.hi)}${r.clear ? '' : ' · includes zero'}`,
        }))} />
      ) : <p className="it-empty">Not enough yet: each side needs at least 3 tagged encounters that also carry this number.</p>}
    </Card>
  )
}

// ---- solo ----

function Solo({ data, from, to }: ViewProps) {
  const pts = useMemo(() => soloPoints(data.solo, data.encounters).filter((p) => inRange(p.date, from, to)), [data, from, to])
  const days = useMemo(() => dayTallies(data.encounters, data.solo), [data])
  const firstDay = data.solo.map((s) => s.date).sort()[0] ?? to
  const weeks = useMemo(() => weekly(days, from && from > firstDay ? from : firstDay, to), [days, from, firstDay, to])
  const soloDays = new Set(pts.map((p) => p.date)).size
  const spanWeeks = Math.max(1, weeks.length)
  const intensities = pts.flatMap((p) => (p.intensity != null ? [p.intensity] : []))
  const loads = pts.flatMap((p) => (p.load != null ? [p.load] : []))
  const pornKnown = pts.filter((p) => p.porn != null)
  const withPorn = pornKnown.filter((p) => p.porn)
  const avg = (xs: number[]) => (xs.length ? xs.reduce((s, x) => s + x, 0) / xs.length : null)
  const gapPairs = pts.flatMap((p) => (p.gap != null && p.intensity != null && p.gap > 0 ? [[p.gap, p.intensity] as [number, number]] : []))
  const gapR = pearson(gapPairs)
  const weekR = pearson(weeks.slice(0, -1).map((w) => [w.partneredDays, w.soloDays] as [number, number]), 8)
  const pornSplit = (k: 'intensity' | 'load') => {
    const a = withPorn.flatMap((p) => (p[k] != null ? [p[k]!] : [])), b = pornKnown.filter((p) => !p.porn).flatMap((p) => (p[k] != null ? [p[k]!] : []))
    return { a, b, d: a.length >= 3 && b.length >= 3 ? diffInterval(a, b) : null }
  }
  const pi = pornSplit('intensity'), pl = pornSplit('load')
  const overTime = useMemo<EChartsCoreOption>(() => ({
    ...AXES, xAxis: timeAxis(), yAxis: valueAxis(),
    series: [
      { name: 'Intensity /10', type: 'scatter', symbolSize: 8, data: pts.filter((p) => p.intensity != null).map((p) => [p.date, p.intensity]), itemStyle: { color: HUE.solo } },
      { name: 'Load size', type: 'scatter', symbol: 'diamond', symbolSize: 8, data: pts.filter((p) => p.load != null).map((p) => [p.date, p.load]), itemStyle: { color: HUE.solo, opacity: 0.55 } },
    ],
  }), [pts])
  const gapOption = useMemo<EChartsCoreOption>(() => ({
    ...AXES, legend: undefined, tooltip: { trigger: 'item', formatter: (p: { value: [number, number] }) => `${p.value[0]} d since the last · intensity ${p.value[1]}` },
    xAxis: { ...valueAxis('days since the last activity'), nameLocation: 'middle', nameGap: 22 }, yAxis: valueAxis('intensity'),
    series: [{ type: 'scatter', symbolSize: 8, data: gapPairs, itemStyle: { color: HUE.solo } }],
  }), [gapPairs])
  const weekOption = useMemo<EChartsCoreOption>(() => ({
    ...AXES, xAxis: catAxis(weeks.map((w) => shortDate(w.week))), yAxis: valueAxis('days'),
    series: [
      { name: 'Solo', type: 'bar', data: weeks.map((w) => w.soloDays), itemStyle: { color: HUE.solo } },
      { name: 'With a partner', type: 'line', data: weeks.map((w) => w.partneredDays), itemStyle: { color: HUE.partnered }, lineStyle: { color: HUE.partnered } },
    ],
  }), [weeks])
  return (
    <div className="it-views-body">
      <Card title="SOLO" wide>
        <Tiles tiles={[
          { label: 'Sessions', value: String(pts.length), sub: `on ${plural(soloDays, 'day')}`, hue: 'm-sex-solo' },
          { label: 'Per week', value: f1(soloDays / spanWeeks), sub: 'days with solo, average' },
          { label: 'Intensity', value: f1(avg(intensities)), sub: intensities.length ? `average of ${intensities.length}, out of 10` : 'none recorded' },
          { label: 'Load size', value: f1(avg(loads)), sub: loads.length ? `average of ${loads.length}` : 'none recorded' },
          { label: 'With porn', value: pornKnown.length ? `${withPorn.length} of ${pornKnown.length}` : '–', sub: pornKnown.length ? `${Math.round((withPorn.length / pornKnown.length) * 100)}%` : null },
        ]} />
      </Card>
      <div className="it-grid">
        <Card title="OVER TIME" info="Each session's orgasm intensity (dots) and load size (diamonds).">
          {pts.length ? <Chart option={overTime} label="Solo intensity and load over time" /> : <p className="it-empty">No solo session in this range.</p>}
        </Card>
        <Card title="SOLO AND PARTNERED, BY WEEK" info="Do weeks with more partnered sex have less solo? The last, unfinished week is left out of the r.">
          {weeks.length ? <Chart option={weekOption} label="Solo and partnered days per week" /> : null}
          <p className="me-note">{weekR ? `r ${signed(weekR.r).replace('±', '')} (95% interval ${f1(weekR.lo)} to ${f1(weekR.hi)}), ${weekR.n} weeks${weekR.clear ? '.' : '. The interval includes zero.'}` : 'Needs at least 8 whole weeks.'}</p>
        </Card>
        <Card title="INTENSITY AND THE GAP BEFORE" info="Days since the last activity of any kind against the session's intensity; second sessions of a day are left out.">
          {gapPairs.length ? <Chart option={gapOption} label="Intensity by days since the last activity" /> : null}
          <p className="me-note">{gapR ? `r ${f1(gapR.r)} (95% interval ${f1(gapR.lo)} to ${f1(gapR.hi)}), ${gapR.n} sessions${gapR.clear ? '.' : '. The interval includes zero.'}` : `Needs at least 10 sessions with a gap and an intensity (${gapPairs.length} so far).`}</p>
        </Card>
        <Card title="WITH PORN AND WITHOUT" info="Averages with a 95% interval for the difference. Descriptive only.">
          <Rows rows={[
            { label: 'Intensity', value: pi.d ? signed(pi.d.diff) : '–', sub: pi.d ? `${f1(avg(pi.a))} with (${pi.a.length}) · ${f1(avg(pi.b))} without (${pi.b.length}) · interval ${signed(pi.d.lo)} to ${signed(pi.d.hi)}` : `Needs 3 sessions on each side (${pi.a.length} with, ${pi.b.length} without).` },
            { label: 'Load size', value: pl.d ? signed(pl.d.diff) : '–', sub: pl.d ? `${f1(avg(pl.a))} with (${pl.a.length}) · ${f1(avg(pl.b))} without (${pl.b.length}) · interval ${signed(pl.d.lo)} to ${signed(pl.d.hi)}` : `Needs 3 sessions on each side (${pl.a.length} with, ${pl.b.length} without).` },
          ]} />
        </Card>
      </div>
    </div>
  )
}
