import { createClient } from '@supabase/supabase-js'

// Public by design: the same project URL and publishable (anon) key are committed in index.html and the Android app, and
// row-level security is the real gate. The VITE_* variables still win when present (custom environments), but a production
// build does not depend on them: the deployed console used to ship the "Missing VITE_SUPABASE_URL" error because the
// workflow read repository secrets that never existed, and Vite 8's build did not inline the variables in any case.
const DEFAULT_URL = 'https://ugfrglbcoivkprjqvjzz.supabase.co'
const DEFAULT_ANON_KEY = 'sb_publishable_GyandyvuVbF0RxZLnugz4A_GlNIstPc'

const SUPABASE_URL = (import.meta.env.VITE_SUPABASE_URL as string | undefined) || DEFAULT_URL
const SUPABASE_ANON_KEY = (import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined) || DEFAULT_ANON_KEY

export const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY)
