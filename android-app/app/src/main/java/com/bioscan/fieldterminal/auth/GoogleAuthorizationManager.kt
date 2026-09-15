package com.bioscan.fieldterminal.auth

import android.app.Activity
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Step 14 (Map tab). Requests the calendar.readonly + drive.readonly scopes
 * the Map tab needs, via Play Services' Authorization API -- a distinct flow
 * from [GoogleAuthManager]'s Credential Manager sign-in, which only ever
 * verifies identity for Supabase auth and never touches OAuth scopes at all.
 *
 * Deliberately does NOT request offline access / a serverAuthCode. The web
 * dashboard needs one (see ROADMAP.md's refresh-google-token write-up)
 * specifically because a browser has no OS-level Google account layer to
 * silently remint an access token -- it has to keep a refresh token
 * server-side (`user_google_tokens`) and mint fresh access tokens through a
 * Supabase Edge Function on every calendar/drive call. Android's Play
 * Services layer already does that job on-device: authorize() called with no
 * UI mints a fresh short-lived access token whenever scopes are already
 * granted for the signed-in Google account, so there is nothing for this app
 * to store server-side. Called fresh every time the Map screen loads, same
 * no-client-caching principle as the web's getFreshGoogleToken(), just
 * backed by Play Services instead of a stored refresh token.
 */
object GoogleAuthorizationManager {
    private val SCOPES = listOf(
        Scope("https://www.googleapis.com/auth/calendar.readonly"),
        Scope("https://www.googleapis.com/auth/drive.readonly"),
    )

    suspend fun authorize(activity: Activity): AuthorizationResult = suspendCancellableCoroutine { cont ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES)
            .build()

        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    fun resultFromIntent(activity: Activity, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
}
