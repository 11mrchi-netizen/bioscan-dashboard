package com.bioscan.fieldterminal.data

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

// Same Supabase project the web dashboard (index.html) already talks to --
// ugfrglbcoivkprjqvjzz. URL + anon/publishable key are not secrets (same
// values already embedded client-side in index.html); nothing here is new
// exposure. See ROADMAP.md's Foundation section for the credential-handling
// rules this project follows for anything that IS secret.
private const val SUPABASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co"
private const val SUPABASE_ANON_KEY = "sb_publishable_GyandyvuVbF0RxZLnugz4A_GlNIstPc"

object SupabaseClientProvider {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY,
        ) {
            install(Auth)
            install(Postgrest)
        }
    }
}
