import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import type { Session, User } from '@supabase/supabase-js'
import { supabase } from '../lib/supabase'

interface AuthValue {
  session: Session | null
  user: User | null
  loading: boolean
  signInWithGoogle: () => Promise<void>
  signOut: () => Promise<void>
}

const Ctx = createContext<AuthValue | null>(null)

// Calendar events (planned sessions sync) and Drive (GPX route files linked from calendar events): the old login.html's scopes.
const GOOGLE_SCOPES = 'https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/drive.readonly'

/**
 * Google returns a refresh token only right after a consented sign-in, and Supabase does not keep it: store it for the
 * refresh-google-token Edge Function (user_google_tokens), as the old dashboard did on load. A no-op on every other load.
 */
function keepGoogleToken(s: Session | null): void {
  const token = s?.provider_refresh_token
  if (!s || !token) return
  void supabase.from('user_google_tokens')
    .upsert({ user_id: s.user.id, refresh_token: token, updated_at: new Date().toISOString() }, { onConflict: 'user_id' })
    .then(({ error }) => { if (error) console.warn('Could not store the Google refresh token:', error.message) })
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    supabase.auth.getSession()
      .then(({ data }) => { setSession(data.session); setLoading(false); keepGoogleToken(data.session) })
      .catch(() => setLoading(false))
    const { data: { subscription } } = supabase.auth.onAuthStateChange((_event, s) => { setSession(s); keepGoogleToken(s) })
    return () => subscription.unsubscribe()
  }, [])

  // One sign-in for the whole app. It asks for the calendar and Drive
  // scopes with offline access, as the old dashboard's login.html did, so signing in from either page keeps the Google
  // Calendar sync connected. The page Google returns to is the one that started the sign-in.
  const signInWithGoogle = async () => {
    await supabase.auth.signInWithOAuth({
      provider: 'google',
      options: {
        redirectTo: window.location.origin + window.location.pathname,
        scopes: GOOGLE_SCOPES,
        queryParams: { access_type: 'offline', prompt: 'consent' },
      },
    })
  }

  const signOut = async () => {
    await supabase.auth.signOut()
  }

  return (
    <Ctx.Provider value={{ session, user: session?.user ?? null, loading, signInWithGoogle, signOut }}>
      {children}
    </Ctx.Provider>
  )
}

export function useAuth(): AuthValue {
  const val = useContext(Ctx)
  if (!val) throw new Error('useAuth must be used inside AuthProvider')
  return val
}
