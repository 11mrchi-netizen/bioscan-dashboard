package com.bioscan.hometerminal.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.bioscan.hometerminal.BuildConfig
import com.bioscan.hometerminal.data.Supabase
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "TvGoogleAuth"

/**
 * Google sign-in for the Supabase session, the same flow as the phone app's GoogleAuthManager: Credential Manager gives
 * a Google ID token, Supabase exchanges it for a session that persists and refreshes itself. Needs an Android OAuth
 * client for com.bioscan.hometerminal in the same Google Cloud project (docs/home-environment/README.md).
 */
object TvGoogleAuth {
    /** Returns null when signed in, otherwise what to show (empty when the person closed the picker). */
    suspend fun signIn(context: Context): String? {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) return "GOOGLE_WEB_CLIENT_ID is not set in local.properties."
        val rawNonce = UUID.randomUUID().toString()
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(sha256(rawNonce))
            .build()
        return try {
            val result = CredentialManager.create(context)
                .getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val credential = result.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return "Unexpected sign-in type: ${credential.type}"
            }
            Supabase.client.auth.signInWith(IDToken) {
                idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                provider = Google
                nonce = rawNonce
            }
            null
        } catch (e: GetCredentialCancellationException) {
            ""
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google account", e)
            "No Google account on this device. Add one in Settings › Accounts."
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Sign-in failed", e)
            "Google sign-in didn't complete: ${e.message}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Supabase sign-in failed", e)
            "The server refused the sign-in: ${e.message}"
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
