package com.bioscan.fieldterminal.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.bioscan.fieldterminal.BuildConfig
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import java.security.MessageDigest
import java.util.UUID

private const val TAG = "GoogleAuthManager"

/**
 * Native Google sign-in via Android's Credential Manager, exchanging the
 * resulting ID token for a real Supabase session -- same Supabase project
 * (ugfrglbcoivkprjqvjzz) and Google Cloud OAuth app the web dashboard already
 * uses, just a native-appropriate flow (Credential Manager, not the web
 * redirect flow `login.html` uses).
 *
 * Confirmed via Supabase's and Google's current docs (Sept 2026) rather than
 * assumed, per this project's "check don't assume" discipline -- this DOES
 * need a second, Android-specific OAuth client ID (keyed to package name +
 * signing cert SHA-1), separate from the existing web client ID. That's a
 * real manual Google Cloud Console step -- see android-app/README.md.
 *
 * BuildConfig.GOOGLE_WEB_CLIENT_ID is the *Web* client ID (not the new
 * Android one) -- Google's Credential Manager API always wants the web
 * client ID as the "server client ID" here, confirmed in Supabase's own
 * sample. It's not a secret (client IDs are public identifiers, unlike
 * client secrets), so embedding it in the app is fine, but its real value
 * isn't hardcoded in this scaffold -- see README for how to supply it.
 */
object GoogleAuthManager {

    suspend fun signIn(context: Context): Result<Unit> {
        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = sha256(rawNonce)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(hashedNonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(request = request, context = context)

            val credential = result.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
            }

            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)

            SupabaseClientProvider.client.auth.signInWith(IDToken) {
                idToken = googleIdTokenCredential.idToken
                provider = Google
                nonce = rawNonce // raw (unhashed) nonce -- Supabase re-hashes to verify
            }
            Result.success(Unit)
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential retrieval failed", e)
            Result.failure(e)
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Failed to parse Google ID token", e)
            Result.failure(e)
        }
    }

    // Mirrors the web dashboard's sign-out button (see index.html / ROADMAP.md
    // Foundation section): a valid Supabase session skips straight past any
    // sign-in screen, so signing out is the only way to re-trigger consent --
    // e.g. after a new OAuth scope is added. Same reasoning applies here.
    suspend fun signOut() {
        SupabaseClientProvider.client.auth.signOut()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.fold("") { acc, byte -> acc + "%02x".format(byte) }
    }
}
