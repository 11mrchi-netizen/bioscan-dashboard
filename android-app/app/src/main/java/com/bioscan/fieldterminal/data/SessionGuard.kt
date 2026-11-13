package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.encodedPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Clock

// Why pages came back empty after time in the background. Every table is protected by row-level security, so a request
// that carries no user token is not refused: it is answered with 200 and an empty list, which a page shows as "no data"
// and ReadCache then keeps (on disk too). supabase-kt sends such a request whenever the session is not in memory at that
// instant: while it is being restored from storage, while an expired token is being refreshed, after a refresh failed on
// a weak network. Two things stop that:
//
//  1. SupabaseClientProvider turns off the library's lifecycle callbacks, which dropped the session from memory every time
//     the app left the foreground (and left the background workers without one for as long as the process lived).
//  2. [SessionGuard], on the one shared client: a data request without a user token waits for the session and then carries
//     it, or fails as a load error. It is never sent anonymously.

/** How long a request waits for the session to be restored or refreshed before it fails. */
private const val SESSION_WAIT_MS = 15_000L

/** A data request was about to go out with no user signed in; shown by the load guards like any failed load. */
class NoSessionException : IOException("The session is not ready yet. Try again in a moment.")

/**
 * The signed-in user's access token, waiting while the session is being restored or refreshed. Null when signed out, or
 * when the session did not come back in time. For the edge functions called outside the SDK (data/FunctionsHttp.kt).
 */
suspend fun SupabaseClient.sessionToken(): String? {
    val status = withTimeoutOrNull(SESSION_WAIT_MS) {
        auth.sessionStatus.first { it is SessionStatus.Authenticated || it is SessionStatus.NotAuthenticated }
    }
    val session = (status as? SessionStatus.Authenticated)?.session ?: return null
    // Past its expiry (the phone slept through the scheduled refresh): refresh now rather than send a token the server refuses.
    if (session.expiresAt <= Clock.System.now()) softLoad { auth.refreshCurrentSession() }
    return auth.currentAccessTokenOrNull() ?: session.accessToken
}

/** True for a request to the database or an edge function that carries no user token (none at all, or only the public key). */
internal fun needsSession(path: String, authorization: String?, publicKey: String): Boolean =
    (path.startsWith("/rest/v1/") || path.startsWith("/functions/v1/")) &&
        (authorization == null || authorization == "Bearer $publicKey")

fun sessionGuard(publicKey: String, token: suspend () -> String?) = createClientPlugin("SessionGuard") {
    onRequest { request, _ ->
        if (!needsSession(request.url.encodedPath, request.headers[HttpHeaders.Authorization], publicKey)) return@onRequest
        request.headers[HttpHeaders.Authorization] = "Bearer ${token() ?: throw NoSessionException()}"
    }
}
