package com.bioscan.fieldterminal.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

// The edge functions called outside the Supabase SDK (its Functions plugin gives no per-call timeout). One client, one way
// to call: as the signed-in user, with a JSON body, and a timeout, since a function that never answers (Gemini or
// Cronometer stalling) would otherwise leave the caller's spinner running for ever.
private val functions by lazy { HttpClient(OkHttp) { install(HttpTimeout) { connectTimeoutMillis = 15_000 } } }

/**
 * POSTs [body] (JSON) to the edge function [name] as the signed-in user. Waits for the session if it is being restored;
 * throws [NoSessionException] when no one is signed in, and an IOException when the function does not answer in [timeoutMs].
 */
internal suspend fun SupabaseClient.postFunction(name: String, body: String, timeoutMs: Long = 45_000): HttpResponse {
    val token = sessionToken() ?: throw NoSessionException()
    return functions.post("$SUPABASE_URL/functions/v1/$name") {
        timeout { requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs }
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

/** Message safe to show the user for a failed edge-function call. */
fun Throwable.functionErrorMessage(): String = when (this) {
    is HttpRequestTimeoutException -> "The server took too long to answer. Try again."
    is NoSessionException -> message.orEmpty()
    is java.io.IOException -> "No connection to the server. Try again."
    else -> message ?: "Request failed"
}
