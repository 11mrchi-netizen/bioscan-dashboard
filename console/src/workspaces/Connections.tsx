// CONNECTIONS (web transition plan, phase 4): the services that feed the data, and Google Calendar's connection, which
// moved here from the old dashboard. Connecting asks Google again for calendar and Drive read access with offline consent;
// AuthContext stores the refresh token Google returns (user_google_tokens), and the refresh-google-token Edge Function
// mints access tokens from it. TEST mints one and reads the next events, so a broken link shows its real reason.
// Sync times are real instants, shown in the browser's own zone.
import { useState } from 'react'
import { supabase } from '../lib/supabase'
import { useAuth } from '../context/AuthContext'
import { useLoadEffect } from '../lib/useLoadEffect'
import { RETURN_KEY } from '../components/layout/Shell'
import './PlanBlock.css'
import './Pages.css'
import { Board } from '../components/layout/Board'

interface Status {
  google: string | null
  calendar: { calendar_id: string; last_synced_at: string | null }[]
  zepp: { last_synced_at: string | null; last_error: string | null; last_error_at: string | null } | null
  syncs: { source: string; last: string }[]
}

/** The refresh-google-token function's error codes, each with what fixes it (as the old dashboard explained them). */
const GOOGLE_FIX: Record<string, string> = {
  no_refresh_token: 'No calendar access stored for this account. Connect Google Calendar below.',
  invalid_grant: 'Google withdrew the access (while the OAuth consent screen is in Testing, refresh tokens last 7 days). Connect again.',
  not_configured: 'The refresh-google-token Edge Function is missing GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET in its secrets.',
  calendar_api_error: 'Google refused the calendar read even with a valid token: check that the Calendar API is enabled for the OAuth project.',
}

const when = (ts: string | null | undefined): string => {
  if (!ts) return 'never'
  const d = new Date(ts)
  const mins = Math.round((Date.now() - d.getTime()) / 60000)
  const ago = mins < 60 ? `${mins} min ago` : mins < 48 * 60 ? `${Math.round(mins / 60)} h ago` : `${Math.round(mins / 1440)} days ago`
  return `${d.toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })} · ${ago}`
}
const stale = (ts: string | null | undefined, hours: number) => !ts || Date.now() - new Date(ts).getTime() > hours * 3600_000

async function loadStatus(): Promise<Status> {
  const [g, c, z, s] = await Promise.all([
    supabase.from('user_google_tokens').select('updated_at').maybeSingle(),
    supabase.from('calendar_sync_state').select('calendar_id,last_synced_at'),
    supabase.from('zepp_sync_state').select('last_synced_at,last_error,last_error_at').maybeSingle(),
    supabase.from('sync_log').select('sync_source,synced_at').order('synced_at', { ascending: false }).limit(400),
  ])
  const last = new Map<string, string>()
  for (const r of (s.data ?? []) as { sync_source: string; synced_at: string }[]) if (!last.has(r.sync_source)) last.set(r.sync_source, r.synced_at)
  return {
    google: (g.data as { updated_at: string } | null)?.updated_at ?? null,
    calendar: (c.data ?? []) as Status['calendar'],
    zepp: z.data as Status['zepp'],
    syncs: [...last].map(([source, at]) => ({ source, last: at })),
  }
}

interface Probe { ok: boolean; message: string; events?: { title: string; start: string }[] }

async function testGoogle(): Promise<Probe> {
  const { data, error } = await supabase.functions.invoke('refresh-google-token')
  if (error) {
    let body = null as { error?: string; message?: string } | null
    const ctx = (error as { context?: { json?: () => Promise<unknown> } }).context
    if (typeof ctx?.json === 'function') { try { body = (await ctx.json()) as typeof body } catch { /* not JSON */ } }
    const code = body?.error ?? 'refresh_failed'
    return { ok: false, message: GOOGLE_FIX[code] ?? `Could not get a Google token (${body?.message ?? error.message}).` }
  }
  const token = (data as { access_token?: string }).access_token
  if (!token) return { ok: false, message: 'The function answered without a token.' }
  const q = new URLSearchParams({ timeMin: new Date().toISOString(), maxResults: '5', singleEvents: 'true', orderBy: 'startTime' })
  const r = await fetch(`https://www.googleapis.com/calendar/v3/calendars/primary/events?${q}`, { headers: { Authorization: `Bearer ${token}` } })
  if (!r.ok) return { ok: false, message: `${GOOGLE_FIX.calendar_api_error} (HTTP ${r.status})` }
  const j = (await r.json()) as { items?: { summary?: string; start?: { dateTime?: string; date?: string } }[] }
  return {
    ok: true, message: 'Connected: a fresh token was minted and the calendar answered.',
    events: (j.items ?? []).map((e) => ({ title: e.summary ?? '(no title)', start: e.start?.dateTime ?? e.start?.date ?? '' })),
  }
}

const SOURCE_NAME: Record<string, string> = { health_connect: 'Health Connect → database', health_connect_writeback: 'Database → Health Connect' }

export default function Connections() {
  const { signInWithGoogle } = useAuth()
  const [status, setStatus] = useState<Status | null>(null)
  const [probe, setProbe] = useState<Probe | null>(null)
  const [testing, setTesting] = useState(false)
  const load = useLoadEffect(async (isCurrent) => { const s = await loadStatus(); if (isCurrent()) setStatus(s) }, [])

  const test = async () => {
    setTesting(true)
    try { setProbe(await testGoogle()) } catch (e) { setProbe({ ok: false, message: (e as Error).message }) } finally { setTesting(false) }
  }

  return (
    <div className="pg">
      <header className="pg-head">
        <div>
          <h2 className="pg-title mono">CONNECTIONS</h2>
          <p className="pg-sub">The services that feed your data, and when each last delivered. Google Calendar is connected here; the phone connects Health Connect and Zepp itself.</p>
        </div>
        <button className="btn-ghost mono" onClick={load.retry}>REFRESH</button>
      </header>
      {load.error && <p className="pb-warn">Could not load the status: {load.error}</p>}

      <Board className="pg-grid">
        <section className="pg-card">
          <h3 className="pg-card-title mono">GOOGLE CALENDAR AND DRIVE</h3>
          <ul className="pg-rows">
            <li className="pg-row"><span className="pg-row-title">Access stored for the web</span>
              <span className={`pg-row-value ${status && !status.google ? 'pg-warn' : ''}`}>{status ? (status.google ? when(status.google) : 'not connected') : '…'}</span>
              <span className="pg-row-sub">Read-only access to the calendar (planned sessions) and Drive (GPX routes linked from events), kept as a refresh token.</span></li>
            {(status?.calendar ?? []).map((c) => (
              <li key={c.calendar_id} className="pg-row"><span className="pg-row-title">Phone's calendar sync · {c.calendar_id}</span>
                <span className={`pg-row-value ${stale(c.last_synced_at, 48) ? 'pg-warn' : ''}`}>{when(c.last_synced_at)}</span></li>
            ))}
          </ul>
          <div className="pg-actions">
            <button className="pb-primary mono" onClick={() => { try { sessionStorage.setItem(RETURN_KEY, '/setup/connections') } catch { /* storage off */ } void signInWithGoogle() }}>{status?.google ? 'RECONNECT GOOGLE CALENDAR' : 'CONNECT GOOGLE CALENDAR'}</button>
            <button className="btn-ghost mono" disabled={testing} onClick={() => void test()}>{testing ? 'TESTING…' : 'TEST THE CONNECTION'}</button>
          </div>
          <p className="pg-note">Connecting goes to Google's consent page and comes back here; it keeps you signed in.</p>
          {probe && (
            <div className={probe.ok ? 'pg-ok' : 'pb-warn'} role="status">
              <p className="pg-note" style={{ color: 'inherit' }}>{probe.message}</p>
              {probe.events && (probe.events.length === 0 ? <p className="pg-note">No upcoming events.</p> : (
                <ul className="pg-rows">{probe.events.map((e, i) => (
                  <li key={i} className="pg-row"><span className="pg-row-title">{e.title}</span>
                    <span className="pg-row-value mono">{e.start.length > 10 ? new Date(e.start).toLocaleString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : e.start}</span></li>
                ))}</ul>
              ))}
            </div>
          )}
        </section>

        <section className="pg-card">
          <h3 className="pg-card-title mono">FROM THE PHONE</h3>
          <ul className="pg-rows">
            {(status?.syncs ?? []).filter((s) => SOURCE_NAME[s.source]).map((s) => (
              <li key={s.source} className="pg-row"><span className="pg-row-title">{SOURCE_NAME[s.source]}</span>
                <span className={`pg-row-value ${stale(s.last, 24) ? 'pg-warn' : ''}`}>{when(s.last)}</span></li>
            ))}
            <li className="pg-row"><span className="pg-row-title">Zepp (watch workouts and tracks)</span>
              <span className={`pg-row-value ${stale(status?.zepp?.last_synced_at, 48) ? 'pg-warn' : ''}`}>{status ? when(status.zepp?.last_synced_at) : '…'}</span>
              {status?.zepp?.last_error && <span className="pg-row-sub pg-warn">Last error {when(status.zepp.last_error_at)}: {status.zepp.last_error}</span>}</li>
          </ul>
          <p className="pg-note">These run on the phone: reconnect one in Setup › CONNECTED SERVICES there. Amber means nothing arrived for over a day (two for Zepp and the calendar).</p>
        </section>
      </Board>
    </div>
  )
}
