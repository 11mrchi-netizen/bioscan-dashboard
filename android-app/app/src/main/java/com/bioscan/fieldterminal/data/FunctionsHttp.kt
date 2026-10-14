package com.bioscan.fieldterminal.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout

/**
 * Client for edge functions called outside the Supabase SDK. Without a timeout a function that never answers (Gemini or
 * Cronometer stalling) leaves the caller's spinner running forever.
 */
internal fun functionsClient(requestTimeoutMs: Long = 45_000): HttpClient = HttpClient(Android) {
    install(HttpTimeout) {
        requestTimeoutMillis = requestTimeoutMs
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = requestTimeoutMs
    }
}

/** Message safe to show the user for a failed edge-function call. */
fun Throwable.functionErrorMessage(): String = when (this) {
    is HttpRequestTimeoutException -> "The server took too long to answer. Try again."
    is java.io.IOException -> "No connection to the server. Try again."
    else -> message ?: "Request failed"
}
