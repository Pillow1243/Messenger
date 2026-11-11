package com.offlinemesh.messenger.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A backend call failed. [code] is the HTTP status (0 for transport/parse failures). */
class SupabaseException(
    val code: Int,
    val reason: String?,
    message: String
) : Exception(message)

/**
 * Minimal, explicit HTTP layer for Supabase.
 *
 * Deliberately not an SDK: each request is visible, the auth header is injected in one place,
 * and errors are mapped to a single exception type so the sync engine can decide whether to
 * retry, back off or surface an actionable error to the user.
 */
class SupabaseHttp(private val apiKey: String = SupabaseConfig.apiKey) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun okHttpClient(): OkHttpClient = client

    suspend fun execute(
        method: String,
        url: HttpUrl?,
        body: String? = null,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
        bytes: ByteArray? = null,
        contentType: String? = null
    ): String = withContext(Dispatchers.IO) {
        val target = url ?: throw SupabaseException(0, "not_configured", "Backend is not configured")
        val builder = Request.Builder().url(target).header("apikey", apiKey)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        headers.forEach { (name, value) -> builder.header(name, value) }

        val requestBody = when {
            bytes != null -> bytes.toRequestBody((contentType ?: "application/octet-stream").toMediaType())
            body != null -> body.toRequestBody(JSON_MEDIA)
            method == "POST" || method == "PATCH" || method == "PUT" ->
                "{}".toRequestBody(JSON_MEDIA)
            else -> null
        }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post(requestBody!!)
            "PATCH" -> builder.patch(requestBody!!)
            "PUT" -> builder.put(requestBody!!)
            else -> throw IllegalArgumentException("Unsupported method $method")
        }

        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw errorFrom(response.code, text)
                text
            }
        } catch (e: SupabaseException) {
            throw e
        } catch (e: java.io.IOException) {
            throw SupabaseException(0, "network", e.message ?: "Network error", )
        }
    }

    private fun errorFrom(status: Int, body: String): SupabaseException {
        var reason: String? = null
        var message = "HTTP $status"
        val parsed: JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (parsed != null) {
            reason = parsed["code"]?.jsonPrimitive?.contentOrNull
                ?: parsed["error"]?.jsonPrimitive?.contentOrNull
            message = parsed["message"]?.jsonPrimitive?.contentOrNull
                ?: parsed["error_description"]?.jsonPrimitive?.contentOrNull
                ?: parsed["msg"]?.jsonPrimitive?.contentOrNull
                ?: parsed["hint"]?.jsonPrimitive?.contentOrNull
                ?: message
        }
        return SupabaseException(status, reason, message)
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
