package com.offlinemesh.messenger.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl

/**
 * Typed-enough PostgREST access. Callers pass filters as `name to value` pairs where the value
 * already contains the PostgREST operator (for example `conversation_id` to `eq.<uuid>`), which
 * keeps every query explicit at the call site instead of hiding it behind a DSL.
 *
 * There is no offline fallback here: any failure throws [SupabaseException] and the caller decides
 * whether to queue the work, retry later or report an error.
 */
class PostgrestClient(
    private val http: SupabaseHttp,
    private val auth: SupabaseAuth
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun select(
        table: String,
        params: List<Pair<String, String>> = emptyList()
    ): JsonArray = request("GET", restUrl(table, params), parseArray = true)
        .let { it as? JsonArray ?: JsonArray(emptyList()) }

    suspend fun insert(
        table: String,
        rows: List<JsonObject>,
        onConflict: String? = null,
        ignoreDuplicates: Boolean = false
    ): JsonArray {
        val params = buildList {
            if (onConflict != null) add("on_conflict" to onConflict)
            add("select" to "*")
        }
        val prefer = buildString {
            append(if (ignoreDuplicates) "resolution=ignore-duplicates" else "resolution=merge-duplicates")
            append(",return=representation")
        }
        val text = http.execute(
            method = "POST",
            url = restUrl(table, params),
            body = JsonArray(rows).toString(),
            bearer = token(),
            headers = mapOf("Prefer" to prefer)
        )
        return runCatching { json.parseToJsonElement(text) as JsonArray }.getOrElse { JsonArray(emptyList()) }
    }

    suspend fun patch(table: String, params: List<Pair<String, String>>, values: JsonObject) {
        http.execute(
            method = "PATCH",
            url = restUrl(table, params),
            body = values.toString(),
            bearer = token(),
            headers = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun delete(table: String, params: List<Pair<String, String>>) {
        http.execute(
            method = "DELETE",
            url = restUrl(table, params),
            bearer = token(),
            headers = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun rpc(name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement? {
        val text = http.execute(
            method = "POST",
            url = SupabaseConfig.endpoint("rest/v1/rpc/$name"),
            body = args.toString(),
            bearer = token()
        )
        if (text.isBlank()) return JsonNull
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    /** Uploads an already-encrypted blob to a private storage bucket. */
    suspend fun uploadObject(bucket: String, path: String, bytes: ByteArray, contentType: String) {
        http.execute(
            method = "POST",
            url = SupabaseConfig.endpoint("storage/v1/object/$bucket/$path"),
            bytes = bytes,
            contentType = contentType,
            bearer = token(),
            headers = mapOf("x-upsert" to "true")
        )
    }

    suspend fun downloadObject(bucket: String, path: String): ByteArray {
        val text = http.execute(
            method = "GET",
            url = SupabaseConfig.endpoint("storage/v1/object/$bucket/$path"),
            bearer = token()
        )
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    private suspend fun token(): String =
        auth.validAccessToken() ?: throw SupabaseException(401, "not_signed_in", "Sign in to use online mode")

    private fun restUrl(table: String, params: List<Pair<String, String>>): HttpUrl? {
        val base = SupabaseConfig.endpoint("rest/v1/$table") ?: return null
        val builder = base.newBuilder()
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private suspend fun request(
        method: String,
        url: HttpUrl?,
        parseArray: Boolean
    ): JsonElement? {
        val text = http.execute(method = method, url = url, bearer = token())
        if (text.isBlank()) return if (parseArray) JsonArray(emptyList()) else null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }
}
