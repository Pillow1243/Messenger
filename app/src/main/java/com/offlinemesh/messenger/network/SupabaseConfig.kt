package com.offlinemesh.messenger.network

import com.offlinemesh.messenger.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Backend endpoints. The URL and the publishable (anon) key are compiled into the client on
 * purpose: they are public identifiers, and every authorization decision is made by row level
 * security in the database. A service-role key must never appear here.
 */
object SupabaseConfig {

    val rawUrl: String = BuildConfig.SUPABASE_URL
    val apiKey: String = BuildConfig.SUPABASE_API_KEY

    val baseUrl: HttpUrl? by lazy { rawUrl.trimEnd('/').toHttpUrlOrNull() }

    val configured: Boolean
        get() = baseUrl != null && apiKey.length > 20

    fun endpoint(path: String): HttpUrl? =
        baseUrl?.newBuilder()?.addPathSegments(path.trimStart('/'))?.build()

    /** wss://host/realtime/v1/websocket - OkHttp derives WS from the https scheme. */
    fun realtimeUrl(): HttpUrl? =
        baseUrl?.newBuilder()
            ?.addPathSegments("realtime/v1/websocket")
            ?.addQueryParameter("apikey", apiKey)
            ?.addQueryParameter("vsn", "1.0.0")
            ?.build()
}
