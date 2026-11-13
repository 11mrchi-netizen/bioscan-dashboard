package com.bioscan.hometerminal.data

import com.bioscan.hometerminal.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.time.Duration.Companion.seconds

// The same Supabase project as the phone app and the web dashboard, defined once in android-app/gradle.properties; every
// write goes through the signed-in user's RLS.
object Supabase {
    val client by lazy {
        createSupabaseClient(supabaseUrl = BuildConfig.SUPABASE_URL, supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
            requestTimeout = 45.seconds
            httpEngine = OkHttp.create()
            // The collector runs with no activity on screen. The library's lifecycle callbacks drop the session from memory
            // whenever the app leaves the foreground, after which an upload would wait for it for ever.
            install(Auth) { enableLifecycleCallbacks = false }
            install(Postgrest)
        }
    }
}
