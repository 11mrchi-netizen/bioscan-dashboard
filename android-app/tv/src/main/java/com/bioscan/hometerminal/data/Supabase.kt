package com.bioscan.hometerminal.data

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlin.time.Duration.Companion.seconds

// The same Supabase project as the phone app and the web dashboard. URL and publishable key are not secrets (see
// android-app/app/.../data/SupabaseClientProvider.kt); every write goes through the signed-in user's RLS.
private const val SUPABASE_URL = "https://ugfrglbcoivkprjqvjzz.supabase.co"
private const val SUPABASE_ANON_KEY = "sb_publishable_GyandyvuVbF0RxZLnugz4A_GlNIstPc"

object Supabase {
    val client by lazy {
        createSupabaseClient(supabaseUrl = SUPABASE_URL, supabaseKey = SUPABASE_ANON_KEY) {
            requestTimeout = 45.seconds
            httpEngine = io.ktor.client.engine.okhttp.OkHttp.create()
            // The collector runs with no activity on screen. The library's lifecycle callbacks drop the session from memory
            // whenever the app leaves the foreground, after which an upload would wait for it for ever.
            install(Auth) { enableLifecycleCallbacks = false }
            install(Postgrest)
        }
    }
}
