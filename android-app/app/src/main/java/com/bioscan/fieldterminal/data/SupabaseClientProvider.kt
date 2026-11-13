package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import kotlin.time.Duration.Companion.seconds
import com.bioscan.fieldterminal.util.PerfHttpLog

// The same Supabase project the web dashboard (index.html) talks to. Its URL and publishable key are defined once for
// both Android apps in android-app/gradle.properties, and are not secrets.
internal const val SUPABASE_URL = BuildConfig.SUPABASE_URL
private const val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY

@OptIn(SupabaseInternal::class) // httpConfig: only to install the debug request log
object SupabaseClientProvider {
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY,
        ) {
            // The default 10 s covers the whole request, which is too short for a 1,000-row read on a slow link and for the
            // zepp-extract edge function. A genuinely dead connection still fails (and now shows a RETRY card) within this.
            requestTimeout = 45.seconds
            httpEngine = io.ktor.client.engine.okhttp.OkHttp.create()
            // The session stays in memory while the app is in the background (data/SessionGuard.kt says why).
            install(Auth) { enableLifecycleCallbacks = false }
            install(Postgrest)
            install(Functions)
            // Debug-only request log (util/Perf.kt); a no-op when Perf.enabled is false.
            httpConfig {
                install(PerfHttpLog)
                // Any write through this client clears data/ReadCache, so cached reads never outlive a write.
                install(CacheInvalidation)
                // No data request leaves without the user's token: unsigned, it would be answered with empty lists.
                install(sessionGuard(SUPABASE_ANON_KEY) { client.sessionToken() })
            }
        }
    }
}
