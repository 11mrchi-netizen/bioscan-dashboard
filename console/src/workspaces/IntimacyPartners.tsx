// Me › INTIMACY › PARTNERS: everyone in `people` as a table, the breakdowns, repeat rate and a timeline, and one partner's page
// (`&person=<id>`): the profile (editable, as the phone's partner sheet), what you did together against your overall mix,
// their encounters and the STI test windows they fall in.
import { useMemo, useState } from 'react'
import type { EChartsCoreOption } from '../lib/echarts'
import {
  barrier, breakdown, browsePartners, daysBetween, type Encounter, inRange, newPartnersByMonth, partnerSummaries, partnersBefore,
  type PartnerSort, type PartnerSummary, type Person, repeatStats, tally,
} from '../lib/intimacy'
import { type PersonPatch, updatePerson } from '../lib/intimacyData'
import { AXES, Card, catAxis, Chart, f1, fullDate, HUE, Note, plural, Rows, ShareBars, Tiles, timeAxis, useDiscreet, useName, valueAxis, ago } from './IntimacyKit'
import type { ViewProps } from './Intimacy'

const SORTS: [PartnerSort, string][] = [['recent', 'RECENT'], ['most', 'MOST'], ['score', 'SCORE'], ['rating', 'RATING'], ['first', 'FIRST MET'], ['name', 'NAME']]
const FACETS: [keyof Person, string][] = [['relationship', 'RELATIONSHIP'], ['where_met', 'WHERE MET'], ['country', 'COUNTRY'], ['age_range', 'AGE RANGE'], ['body_type', 'BODY TYPE'], ['gender', 'GENDER'], ['period', 'PERIOD']]

export function IntimacyPartners(props: ViewProps) {
  const { data, params, set } = props
  const all = useMemo(() => partnerSummaries(data.people, data.encounters), [data])
  const id = params.get('person')
  const one = id ? all.find((p) => String(p.person.id) === id) : null
  if (one) return <PartnerPage p={one} {...props} back={() => set('person', null)} />
  return <PartnerList all={all} {...props} />
}

function PartnerList({ all, data, from, to, params, set }: ViewProps & { all: PartnerSummary[] }) {
  const name = useName()
  const discreet = useDiscreet()
  const [query, setQuery] = useState('')
  const sort = (SORTS.find((s) => s[0] === params.get('sort'))?.[0] ?? 'recent') as PartnerSort
  const facet = FACETS.find((f) => f[0] === params.get('facet')) ?? FACETS[0]
  const [onlyFacet, setOnlyFacet] = useState<string | null>(null)
  const shown = browsePartners(all, discreet ? '' : query, sort).filter((p) => !onlyFacet || ((p.person[facet[0]] as string | null)?.trim() || 'not set') === onlyFacet)
  const seen = all.filter((p) => p.last && inRange(p.last, from, to)).length
  const rep = repeatStats(data.encounters)
  const newByMonth = newPartnersByMonth(data.encounters)
  const monthOption = useMemo<EChartsCoreOption>(() => ({
    ...AXES, legend: undefined, xAxis: catAxis(newByMonth.map((m) => m[0])), yAxis: valueAxis('people'),
    series: [{ type: 'bar', data: newByMonth.map((m) => m[1]), itemStyle: { color: HUE.partnered } }],
  }), [newByMonth])
  // the timeline: partners with an encounter in the range, a dot per encounter
  const lanes = all.filter((p) => p.encounters.some((e) => inRange(e.date, from, to))).sort((a, b) => (b.last ?? '').localeCompare(a.last ?? ''))
  const start = from ?? lanes.flatMap((p) => p.encounters.map((e) => e.date)).sort()[0] ?? to
  const span = Math.max(1, daysBetween(start, to))
  return (
    <div className="it-views-body">
      <Card title="PARTNERS" wide>
        <Tiles tiles={[
          { label: 'People', value: String(all.length), sub: `${all.filter((p) => p.encounters.length).length} with an encounter on record` },
          { label: 'Seen in range', value: String(seen) },
          { label: 'Seen again', value: rep.partners ? `${rep.repeat} of ${rep.partners}` : '–', sub: rep.partners ? `${Math.round((rep.repeat / rep.partners) * 100)}% of partners with sex on record` : null },
          { label: 'Time to a second', value: rep.medianDaysToSecond != null ? `${f1(rep.medianDaysToSecond)} d` : '–', sub: 'median, first to second time' },
        ]} />
      </Card>
      <div className="it-grid">
        <Card title="NEW PARTNERS BY MONTH" info="The month of each partner's first sexual encounter on record. The record starts in September 2026; first-met dates from the profiles are in the table.">
          {newByMonth.length ? <Chart option={monthOption} height={180} label="New partners per month" /> : <p className="it-empty">None on record yet.</p>}
        </Card>
        <Card title={`BY ${facet[1]}`} aside={<select className="it-select" value={facet[0]} onChange={(e) => { setOnlyFacet(null); set('facet', e.target.value) }}>{FACETS.map((f) => <option key={f[0]} value={f[0]}>{f[1].toLowerCase()}</option>)}</select>} info="Every profile, counted by one field. Choose a row to filter the table.">
          <ShareBars rows={breakdown(data.people, (p) => p[facet[0]] as string | null).slice(0, 12)} total={data.people.length} onPick={(l) => setOnlyFacet(onlyFacet === l ? null : l)} />
          {onlyFacet && <button className="it-link mono" onClick={() => setOnlyFacet(null)}>SHOWING {onlyFacet.toUpperCase()} · CLEAR</button>}
        </Card>
      </div>
      {lanes.length > 0 && (
        <Card title="TIMELINE" wide aside={`${lanes.length} IN RANGE`} info="One row per partner seen in the range, a dot per encounter (a hollow one without sex).">
          <div className="it-lanes">
            {lanes.map((p) => (
              <div key={p.person.id} className="it-lane">
                <button className="it-link it-lane-name" onClick={() => set('person', String(p.person.id))}>{name(p.person.id)}</button>
                <div className="it-lane-track">
                  {p.encounters.filter((e) => inRange(e.date, start, to)).map((e) => (
                    <span key={e.id} className={`it-dot${e.sexual ? '' : ' hollow'}`} title={fullDate(e.date)} style={{ left: `${(daysBetween(start, e.date) / span) * 100}%` }} />
                  ))}
                </div>
              </div>
            ))}
          </div>
          <div className="it-lane-axis mono"><span>{fullDate(start)}</span><span>{fullDate(to)}</span></div>
        </Card>
      )}
      <Card title="EVERYONE" wide aside={`${shown.length} SHOWN`}>
        <div className="it-filters">
          {discreet
            ? <p className="it-info">Search by name is off in discreet mode.</p>
            : <label className="it-field it-grow"><span className="me-label">SEARCH</span><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Name, relationship, country, where met" /></label>}
          <div className="it-chips">{SORTS.map(([k, l]) => <button key={k} className="ft-chip" aria-current={sort === k} onClick={() => set('sort', k)}>{l}</button>)}</div>
        </div>
        <div className="it-table-wrap">
          <table className="it-table">
            <thead><tr>{['NAME', 'RELATIONSHIP', 'WHERE MET', 'COUNTRY', 'AGE', 'SCORE', 'FIRST MET', 'ENCOUNTERS', 'LAST', 'RATING'].map((h) => <th key={h} className="mono">{h}</th>)}</tr></thead>
            <tbody>
              {shown.map((p) => (
                <tr key={p.person.id} onClick={() => set('person', String(p.person.id))} tabIndex={0} onKeyDown={(ev) => { if (ev.key === 'Enter') set('person', String(p.person.id)) }}>
                  <td>{name(p.person.id)}</td>
                  <td>{p.person.relationship ?? ''}</td>
                  <td>{p.person.where_met ?? ''}</td>
                  <td>{p.person.country ?? ''}</td>
                  <td>{p.person.age_range ?? ''}</td>
                  <td className="it-num">{p.person.score ?? ''}</td>
                  <td className="it-num">{p.person.first_met_date ? fullDate(p.person.first_met_date) : ''}</td>
                  <td className="it-num">{p.encounters.length || ''}{p.encounters.length ? <span className="it-dim"> · {p.sexual} sex</span> : null}</td>
                  <td className="it-num">{p.last ? ago(p.last, to) : ''}</td>
                  <td className="it-num">{p.avgRating != null ? f1(p.avgRating) : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}

const EDIT_FIELDS: [keyof Person, string][] = [
  ['name', 'Name'], ['relationship', 'Relationship'], ['where_met', 'Where met'], ['first_met_date', 'First met (YYYY-MM-DD)'], ['period', 'Period'],
  ['gender', 'Gender'], ['age_range', 'Age range'], ['country', 'Country'], ['body_type', 'Body type'], ['availability', 'Availability'], ['score', 'Score'],
]

function PartnerPage({ p, data, to, back, onChanged, open }: ViewProps & { p: PartnerSummary; back: () => void }) {
  const name = useName()
  const [editing, setEditing] = useState(false)
  const person = p.person
  const sexual = p.encounters.filter((e) => e.sexual)
  const allSexual = data.encounters.filter((e) => e.sexual)
  const mine = tally(sexual, (e) => e.activities)
  const overall = new Map(tally(allSexual, (e) => e.activities))
  const taggedMine = sexual.filter((e) => e.activities?.length).length
  const taggedAll = allSexual.filter((e) => e.activities?.length).length
  const days = [...new Set(p.encounters.map((e) => e.date))].sort()
  const meetGaps = days.slice(1).map((d, i) => daysBetween(days[i], d))
  const windows = (data.tests ?? []).filter((t) => t.result !== 'pending').map((t) => t.test_date).filter((d, i, a) => a.indexOf(d) === i)
    .filter((d) => partnersBefore(d, data.encounters).includes(person.id))
  const trend = useMemo<EChartsCoreOption>(() => ({
    ...AXES, xAxis: timeAxis(), yAxis: valueAxis(),
    series: [
      { name: 'Your rating /5', type: 'line', data: [...p.encounters].reverse().filter((e) => e.my_rating != null).map((e) => [e.date, e.my_rating]), itemStyle: { color: HUE.partnered }, lineStyle: { color: HUE.partnered } },
      { name: 'Intensity /10', type: 'line', data: [...p.encounters].reverse().filter((e) => e.orgasm_intensity != null).map((e) => [e.date, e.orgasm_intensity]), itemStyle: { color: HUE.partnered }, lineStyle: { color: HUE.partnered, type: 'dashed' } },
    ],
  }), [p])
  const profile: [string, string | null][] = [
    ['Relationship', person.relationship], ['Where met', person.where_met], ['First met', person.first_met_date ? fullDate(person.first_met_date) : null], ['Period', person.period],
    ['Gender', person.gender], ['Age range', person.age_range], ['Country', person.country], ['Body type', person.body_type], ['Availability', person.availability],
    ['Score', person.score != null ? String(person.score) : null],
  ]
  return (
    <div className="it-views-body">
      <div className="it-back"><button className="it-link mono" onClick={back}>‹ PARTNERS</button><h2 className="it-title">{name(person.id)}</h2><button className="ft-chip" onClick={() => setEditing(!editing)}>{editing ? 'CLOSE' : 'EDIT PROFILE'}</button></div>
      {editing && <ProfileForm person={person} onDone={() => { setEditing(false); onChanged() }} />}
      <div className="it-grid">
        <Card title="PROFILE">
          {profile.some((r) => r[1]) ? <Rows rows={profile.filter((r): r is [string, string] => !!r[1]).map(([label, value]) => ({ label, value }))} /> : <p className="it-empty">Nothing on file yet.</p>}
          {person.activities?.length ? <><div className="me-label">ACTIVITIES ON THE PROFILE</div><p className="me-note">{person.activities.join(', ')}</p></> : null}
          {person.interested_in?.length ? <><div className="me-label">INTERESTED IN</div><p className="me-note">{person.interested_in.join(', ')}</p></> : null}
          <Note text={person.notes} />
        </Card>
        <Card title="WITH YOU">
          {p.encounters.length ? <Rows rows={[
            { label: 'Encounters', value: `${p.encounters.length} · ${p.sexual} with sex` },
            { label: 'First', value: fullDate(p.first) },
            { label: 'Last', value: p.last ? `${fullDate(p.last)} · ${ago(p.last, to)}` : '–' },
            { label: 'Usual gap between meetings', value: meetGaps.length ? `${f1([...meetGaps].sort((a, b) => a - b)[Math.floor(meetGaps.length / 2)])} d` : '–', sub: meetGaps.length ? `longest ${Math.max(...meetGaps)} d` : undefined },
            { label: 'Your rating', value: p.avgRating != null ? `${f1(p.avgRating)} / 5` : '–' },
            { label: 'Rounds', value: String(sexual.reduce((s, e) => s + (e.rounds ?? 1), 0)) },
            { label: 'With a barrier', value: p.barrierKnown ? `${p.withBarrier} of ${p.barrierKnown}` : '–', sub: sexual.length - p.barrierKnown ? `${sexual.length - p.barrierKnown} not recorded` : undefined },
          ]} /> : <p className="it-empty">No encounter logged with {name(person.id)}.</p>}
        </Card>
      </div>
      {sexual.length > 0 && (
        <div className="it-grid">
          <Card title="WHAT YOU DO TOGETHER" info={`Share of tagged encounters with ${name(person.id)} (${taggedMine}) against all your tagged encounters (${taggedAll}).`}>
            {mine.length ? (
              <ul className="it-bars">{mine.slice(0, 10).map(([tag, n]) => {
                const a = n / Math.max(1, taggedMine), b = (overall.get(tag) ?? 0) / Math.max(1, taggedAll)
                return (
                  <li key={tag}><span className="it-bar-label">{tag}</span>
                    <span className="it-bar-track"><span className="it-bar-fill" style={{ width: `${a * 100}%`, background: 'var(--m-sex-partnered)' }} /><span className="it-bar-mark" style={{ left: `${b * 100}%` }} title={`overall ${Math.round(b * 100)}%`} /></span>
                    <span className="it-bar-n">{Math.round(a * 100)}%<span className="it-dim"> · {Math.round(b * 100)}% overall</span></span></li>
                )
              })}</ul>
            ) : <p className="it-empty">No activities tagged yet.</p>}
          </Card>
          <Card title="OVER TIME" info="Your rating and the strongest orgasm of each encounter (dashed).">
            <Chart option={trend} height={200} label="Rating and intensity over time" />
          </Card>
        </div>
      )}
      {windows.length > 0 && <Card title="STI TEST WINDOWS" info="Tests with this partner among the sexual encounters of the 90 days before (the phone's rule: a starting list, not a clinical look-back)."><p className="me-note">{windows.map(fullDate).join(' · ')}</p></Card>}
      <Card title="HISTORY" wide aside={plural(p.encounters.length, 'ENCOUNTER')}>
        <ul className="me-rows">
          {p.encounters.map((e: Encounter) => (
            <li key={e.id} className="me-row">
              <span className="me-row-label"><button className="it-link" onClick={() => open(e)}>{fullDate(e.date)} · {e.sexual ? (e.format?.join(' / ') || 'sex') : e.encounter_type ?? 'meetup'} ›</button></span>
              <span className="me-row-value">{[e.sexual && e.rounds != null && plural(e.rounds, 'round'), e.my_rating != null && `${e.my_rating}/5`].filter(Boolean).join(' · ')}</span>
              {e.sexual && <span className="me-row-sub">{[barrier(e) === true ? 'barrier' : barrier(e) === false ? 'no barrier' : 'protection not recorded', e.activities?.slice(0, 4).join(', ')].filter(Boolean).join(' · ')}</span>}
            </li>
          ))}
        </ul>
      </Card>
    </div>
  )
}

function ProfileForm({ person, onDone }: { person: Person; onDone: () => void }) {
  const [draft, setDraft] = useState<Record<string, string>>(() => Object.fromEntries(EDIT_FIELDS.map(([k]) => [k, person[k] == null ? '' : String(person[k])])))
  const [notes, setNotes] = useState(person.notes ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function save() {
    setError(null)
    const patch: PersonPatch = {}
    for (const [k] of EDIT_FIELDS) {
      const v = draft[k].trim()
      if (k === 'name') { if (!v) { setError('A name is needed.'); return } patch.name = v; continue }
      if (k === 'score') { if (v && !/^\d{1,3}$/.test(v)) { setError('Score is a whole number.'); return } patch.score = v ? Number(v) : null; continue }
      if (k === 'first_met_date' && v && !/^\d{4}-\d{2}-\d{2}$/.test(v)) { setError('First met is a date, YYYY-MM-DD.'); return }
      ;(patch as Record<string, string | null>)[k] = v || null
    }
    patch.notes = notes.trim() || null
    setSaving(true)
    try { await updatePerson(person.id, patch); onDone() } catch (e) { setError(e instanceof Error ? e.message : String(e)) } finally { setSaving(false) }
  }
  return (
    <section className="ft-card it-card">
      <div className="it-form">
        {EDIT_FIELDS.map(([k, label]) => <label key={k} className="it-field"><span className="me-label">{label.toUpperCase()}</span><input value={draft[k]} onChange={(e) => setDraft({ ...draft, [k]: e.target.value })} /></label>)}
        <label className="it-field it-wide"><span className="me-label">NOTES</span><textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} /></label>
      </div>
      {error && <p className="it-error">{error}</p>}
      <div className="it-chips"><button className="ft-chip it-primary" disabled={saving} onClick={() => void save()}>{saving ? 'SAVING…' : 'SAVE PROFILE'}</button></div>
      <p className="it-info">The activities and interests lists stay as they are; the phone edits those.</p>
    </section>
  )
}
