import test from 'node:test'
import assert from 'node:assert/strict'
import { crumbs, locate, NAV, navTo, pathOf, ME_TAB_IDS } from './nav.ts'

const sec = (id: string) => NAV.find((s) => s.id === id)!
const tab = (s: string, t: string) => sec(s).tabs!.find((x) => x.id === t)!

test('the tree has the phone\'s five tabs in order and the Setup gear', () => {
  assert.deepEqual(NAV.map((s) => s.id), ['today', 'me', 'lab', 'plan', 'log', 'setup'])
  assert.deepEqual(sec('me').tabs!.map((t) => t.id), [...ME_TAB_IDS])
  assert.deepEqual(sec('lab').tabs!.map((t) => t.id), ['compare', 'evidence', 'experiments', 'regimen', 'tests', 'routine'])
})

test('an address with no tab or view is on the page\'s own default, so the menu marks something', () => {
  assert.equal(locate('/me', '').tab?.id, 'BODY') // Me opens on BODY
  assert.equal(locate('/lab/evidence', '').view?.id, 'queue')
  assert.equal(locate('/lab/compare', '').view?.id, 'days')
  assert.equal(locate('/lab/experiments', '').view?.id, 'randomised')
  assert.equal(locate('/lab/evidence', '?view=trust').view?.id, 'trust')
  assert.equal(locate('/lab/compare', '?view=periods&series=hrv').view?.id, 'periods')
  assert.equal(locate('/lab/evidence', '?view=nonsense').view?.id, 'queue')
  assert.equal(locate('/me', '?tab=exposures').tab?.id, 'EXPOSURES')
  assert.equal(locate('/me', '?tab=junk').tab?.id, 'BODY')
})

test('every section and tab is found, a metric page and an unknown route belong to none', () => {
  assert.equal(locate('/today', '').section?.id, 'today')
  assert.equal(locate('/plan/sessions', '').tab?.id, 'sessions')
  assert.equal(locate('/setup/data', '').tab?.id, 'data')
  assert.equal(locate('/log', '').tab, null)
  assert.equal(locate('/m/series%3Ahrv', '').section, null)
  assert.equal(locate('/nowhere', '').section, null)
  assert.equal(locate('/lab/nonsense', '').tab, null)
})

test('the breadcrumb reads SECTION > TAB > VIEW', () => {
  assert.deepEqual(crumbs(locate('/lab/evidence', '?view=board')), ['Lab', 'Evidence', 'Evidence board'])
  assert.deepEqual(crumbs(locate('/lab/regimen', '')), ['Lab', 'Regimen'])
  assert.deepEqual(crumbs(locate('/me', '?tab=HEALTH')), ['Me', 'Health'])
  assert.deepEqual(crumbs(locate('/today', '')), ['Today'])
  assert.deepEqual(crumbs(locate('/m/charge', '')), [])
})

test('a menu link keeps the parameters that are not navigation when only the view or the Me tab changes', () => {
  const evidence = { section: sec('lab'), tab: tab('lab', 'evidence') }
  assert.equal(navTo({ ...evidence, view: tab('lab', 'evidence').views![1] }, '/lab/evidence', '?view=ledger&x=1'), '/lab/evidence?view=board&x=1')
  assert.equal(navTo({ ...evidence, view: tab('lab', 'evidence').views![0] }, '/lab/evidence', '?view=ledger&x=1'), '/lab/evidence?x=1') // the default: no parameter
  const compare = { section: sec('lab'), tab: tab('lab', 'compare') }
  assert.equal(navTo({ ...compare, view: tab('lab', 'compare').views![1] }, '/lab/compare', '?m=series%3Ahrv&with=sleep&range=365'), '/lab/compare?m=series%3Ahrv&with=sleep&range=365&view=periods')
  assert.equal(navTo({ section: sec('me'), tab: tab('me', 'HEALTH') }, '/me', '?tab=BODY&range=1Y'), '/me?tab=HEALTH&range=1Y')
})

test('going to another page starts clean; a section opens its first tab and a tab its default view', () => {
  assert.equal(navTo({ section: sec('plan') }, '/lab/compare', '?m=x'), '/plan/overview')
  assert.equal(navTo({ section: sec('lab'), tab: tab('lab', 'evidence') }, '/lab/compare', '?view=periods&series=hrv'), '/lab/evidence')
  assert.equal(navTo({ section: sec('lab') }, '/today', ''), '/lab/compare')
  assert.equal(navTo({ section: sec('me') }, '/today', ''), '/me?tab=BODY')
  assert.equal(navTo({ section: sec('me'), tab: tab('me', 'ROUTINE') }, '/lab/tests', '?x=1'), '/me?tab=ROUTINE')
  assert.equal(pathOf({ section: sec('setup'), tab: tab('setup', 'data') }), '/setup/data')
})

test('every tab and view the tree names is a real page route', () => {
  const routes = new Set(['lab/compare', 'lab/evidence', 'lab/experiments', 'lab/regimen', 'lab/tests', 'lab/routine', 'plan/overview', 'plan/training', 'plan/food', 'plan/sessions', 'plan/races', 'setup/integrity', 'setup/data', 'setup/connections'])
  for (const s of NAV) for (const t of s.tabs ?? []) if (!s.tabParam) assert.ok(routes.has(`${s.id}/${t.id}`), `${s.id}/${t.id}`)
})

test('Plan > Sessions has Planned and Recorded, and one recorded session\'s page belongs to Recorded', () => {
  assert.equal(locate('/plan/sessions', '').view?.id, 'planned')
  assert.equal(locate('/plan/sessions', '?view=recorded&type=run').view?.id, 'recorded')
  assert.equal(locate('/plan/sessions/812', '').view?.id, 'recorded')
  assert.deepEqual(crumbs(locate('/plan/sessions/812', '')), ['Plan', 'Sessions', 'Recorded'])
  assert.equal(locate('/plan/sessions/planned/68', '').view?.id, 'planned') // a planned session's page belongs to Planned
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
