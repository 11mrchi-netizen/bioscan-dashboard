import { createClient } from '@supabase/supabase-js'

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL as string
const SUPABASE_ANON_KEY = import.meta.env.VITE_SUPABASE_ANON_KEY as string

if (!SUPABASE_URL || !SUPABASE_ANON_KEY) {
  document.body.innerHTML = '<pre style="color:red;padding:2rem">Missing VITE_SUPABASE_URL or VITE_SUPABASE_ANON_KEY — check GitHub Actions secrets.</pre>'
  throw new Error('Missing Supabase env vars')
}

export const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY)
