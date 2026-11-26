import test from 'node:test'
import assert from 'node:assert/strict'
import { crumbs, locate, NAV, navTo, pathOf, ME_TAB_IDS } from './nav.ts'

const sec = (id: string) => NAV.find((s) => s.id === id)!
const tab = (s: string, t: string) => sec(s).tabs!.find((x) => x.id === t)!

test('the tree is Today, Me, then the places by job (Plan, Analyse, Review) and the Data gear', () => {
  assert.deepEqual(NAV.map((s) => s.id), ['today', 'me', 'plan', 'analyse', 'review', 'data'])
  assert.deepEqual(sec('me').tabs!.map((t) => t.id), [...ME_TAB_IDS])
  assert.deepEqual(sec('analyse').tabs!.map((t) => t.id), ['compare', 'evidence', 'experiments', 'regimen', 'tests', 'routine'])
  assert.deepEqual(sec('review').tabs!.map((t) => t.id), ['reports', 'sessions', 'log'])
  assert.deepEqual(sec('data').tabs!.map((t) => t.id), ['integrity', 'rows', 'connections'])
})

test('an address with no tab or view is on the page\'s own default, so the menu marks something', () => {
  assert.equal(locate('/me', '').tab?.id, 'BODY') // Me opens on BODY
  assert.equal(locate('/analyse/evidence', '').view?.id, 'queue')
  assert.equal(locate('/analyse/compare', '').view?.id, 'days')
  assert.equal(locate('/analyse/experiments', '').view?.id, 'randomised')
  assert.equal(locate('/analyse/evidence', '?view=trust').view?.id, 'trust')
  assert.equal(locate('/analyse/compare', '?view=periods&series=hrv').view?.id, 'periods')
  assert.equal(locate('/analyse/evidence', '?view=nonsense').view?.id, 'queue')
  assert.equal(locate('/me', '?tab=exposures').tab?.id, 'EXPOSURES')
  assert.equal(locate('/me', '?tab=junk').tab?.id, 'BODY')
})

test('every section and tab is found, a metric page and an unknown route belong to none', () => {
  assert.equal(locate('/today', '').section?.id, 'today')
  assert.equal(locate('/plan/sessions', '').tab?.id, 'sessions')
  assert.equal(locate('/data/rows', '').tab?.id, 'rows')
  assert.equal(locate('/review/log', '').tab?.id, 'log')
  assert.equal(locate('/log', '').section, null) // the old address redirects, it is not a place
  assert.equal(locate('/m/series%3Ahrv', '').section, null)
  assert.equal(locate('/nowhere', '').section, null)
  assert.equal(locate('/analyse/nonsense', '').tab, null)
})

test('the breadcrumb reads SECTION > TAB > VIEW', () => {
  assert.deepEqual(crumbs(locate('/analyse/evidence', '?view=board')), ['Analyse', 'Evidence', 'Evidence board'])
  assert.deepEqual(crumbs(locate('/analyse/regimen', '')), ['Analyse', 'Regimen'])
  assert.deepEqual(crumbs(locate('/me', '?tab=HEALTH')), ['Me', 'Health'])
  assert.deepEqual(crumbs(locate('/today', '')), ['Today'])
  assert.deepEqual(crumbs(locate('/m/charge', '')), [])
})

test('a menu link keeps the parameters that are not navigation when only the view or the Me tab changes', () => {
  const evidence = { section: sec('analyse'), tab: tab('analyse', 'evidence') }
  assert.equal(navTo({ ...evidence, view: tab('analyse', 'evidence').views![1] }, '/analyse/evidence', '?view=ledger&x=1'), '/analyse/evidence?view=board&x=1')
  assert.equal(navTo({ ...evidence, view: tab('analyse', 'evidence').views![0] }, '/analyse/evidence', '?view=ledger&x=1'), '/analyse/evidence?x=1') // the default: no parameter
  const compare = { section: sec('analyse'), tab: tab('analyse', 'compare') }
  assert.equal(navTo({ ...compare, view: tab('analyse', 'compare').views![1] }, '/analyse/compare', '?m=series%3Ahrv&with=sleep&range=365'), '/analyse/compare?m=series%3Ahrv&with=sleep&range=365&view=periods')
  assert.equal(navTo({ section: sec('me'), tab: tab('me', 'HEALTH') }, '/me', '?tab=BODY&range=1Y'), '/me?tab=HEALTH&range=1Y')
})

test('going to another page starts clean; a section opens its first tab and a tab its default view', () => {
  assert.equal(navTo({ section: sec('plan') }, '/analyse/compare', '?m=x'), '/plan/overview')
  assert.equal(navTo({ section: sec('analyse'), tab: tab('analyse', 'evidence') }, '/analyse/compare', '?view=periods&series=hrv'), '/analyse/evidence')
  assert.equal(navTo({ section: sec('analyse') }, '/today', ''), '/analyse/compare')
  assert.equal(navTo({ section: sec('me') }, '/today', ''), '/me?tab=BODY')
  assert.equal(navTo({ section: sec('me'), tab: tab('me', 'ROUTINE') }, '/analyse/tests', '?x=1'), '/me?tab=ROUTINE')
  assert.equal(pathOf({ section: sec('data'), tab: tab('data', 'rows') }), '/data/rows')
})

test('every tab and view the tree names is a real page route', () => {
  const routes = new Set(['analyse/compare', 'analyse/evidence', 'analyse/experiments', 'analyse/regimen', 'analyse/tests', 'analyse/routine', 'review/sessions', 'review/log', 'review/reports', 'plan/overview', 'plan/training', 'plan/food', 'plan/sessions', 'plan/races', 'data/integrity', 'data/rows', 'data/connections'])
  for (const s of NAV) for (const t of s.tabs ?? []) if (!s.tabParam) assert.ok(routes.has(`${s.id}/${t.id}`), `${s.id}/${t.id}`)
})

test('Plan > Sessions is the planned ones; the recorded workouts are Review > Sessions', () => {
  assert.equal(locate('/plan/sessions', '').tab?.id, 'sessions')
  assert.equal(locate('/plan/sessions', '').view, null)
  assert.deepEqual(crumbs(locate('/plan/sessions/planned/68', '')), ['Plan', 'Sessions'])
  assert.deepEqual(crumbs(locate('/review/sessions', '?type=run')), ['Review', 'Sessions'])
  assert.deepEqual(crumbs(locate('/review/sessions/812', '')), ['Review', 'Sessions']) // a recorded workout's page
  assert.equal(locate('/plan/overview/812', '').view, null)
})

test("Me › INTIMACY sits in the phone's place and its views are links of their own", () => {
  assert.equal(ME_TAB_IDS.indexOf('INTIMACY'), ME_TAB_IDS.indexOf('BLOODWORK') + 1)
  const intimacy = tab('me', 'INTIMACY')
  assert.deepEqual(crumbs(locate('/me', '?tab=INTIMACY&view=partners')), ['Me', 'Intimacy', 'Partners'])
  assert.equal(locate('/me', '?tab=INTIMACY').view?.id, 'overview')
  const partners = intimacy.views!.find((v) => v.id === 'partners')!
  assert.equal(navTo({ section: sec('me'), tab: intimacy, view: partners }, '/me', '?tab=INTIMACY&range=1Y'), '/me?tab=INTIMACY&range=1Y&view=partners')
  // a person open on one view does not follow to another; leaving the tab drops its view
  assert.equal(navTo({ section: sec('me'), tab: intimacy, view: intimacy.views![0] }, '/me', '?tab=INTIMACY&view=partners&person=4'), '/me?tab=INTIMACY')
  assert.equal(navTo({ section: sec('me'), tab: tab('me', 'BODY') }, '/me', '?tab=INTIMACY&view=mix&range=1Y'), '/me?tab=BODY&range=1Y')
})
