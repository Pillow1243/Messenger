package com.offlinemesh.messenger.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

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

    /** Mints a short-lived signed download URL for an encrypted blob in a private bucket. */
    suspend fun createSignedStorageUrl(
        bucket: String,
        path: String,
        expiresInSeconds: Int = 300
    ): HttpUrl {
        val cleanPath = path.trimStart('/')
        val text = http.execute(
            method = "POST",
            url = SupabaseConfig.endpoint("storage/v1/object/sign/$bucket/$cleanPath"),
            body = buildJsonObject { put("expiresIn", expiresInSeconds) }.toString(),
            bearer = token()
        )
        val signedPath = runCatching {
            json.parseToJsonElement(text).jsonObject["signedURL"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: throw SupabaseException(0, "signed_url", "Could not mint signed storage URL")

        return if (signedPath.startsWith("http://") || signedPath.startsWith("https://")) {
            signedPath.toHttpUrlOrNull()
        } else {
            val rel = signedPath.trimStart('/')
            val fullPath = if (rel.startsWith("storage/v1/")) rel else "storage/v1/$rel"
            SupabaseConfig.endpoint(fullPath)
        } ?: throw SupabaseException(0, "signed_url", "Invalid signed storage URL")
    }

    suspend fun uploadEncryptedMedia(
        transferId: String,
        bucket: String,
        objectPath: String,
        encryptedFile: File,
        resumeTusUrl: String? = null,
        onTusUrlCreated: ((String) -> Unit)? = null,
        onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        http.clearCancelled(transferId)
        http.uploadFileResumable(
            transferId = transferId,
            baseStorageUrl = SupabaseConfig.endpoint("storage/v1"),
            bucket = bucket,
            objectPath = objectPath,
            sourceFile = encryptedFile,
            bearer = token(),
            resumeTusUrl = resumeTusUrl,
            onTusUrlCreated = onTusUrlCreated,
            onProgress = onProgress
        )
    }

    suspend fun downloadEncryptedMedia(
        transferId: String,
        signedUrl: HttpUrl,
        destPartFile: File,
        expectedTotalBytes: Long? = null,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): File {
        http.clearCancelled(transferId)
        return http.downloadFileResumable(
            transferId = transferId,
            url = signedUrl,
            destPartFile = destPartFile,
            expectedTotalBytes = expectedTotalBytes,
            bearer = null,
            onProgress = onProgress
        )
    }

    suspend fun deleteStorageObject(bucket: String, objectPath: String) {
        http.execute(
            method = "DELETE",
            url = SupabaseConfig.endpoint("storage/v1/object/$bucket/${objectPath.trimStart('/')}"),
            bearer = token()
        )
    }

    fun cancelTransfer(transferId: String) {
        http.cancelTransfer(transferId)
    }

    fun clearCancelled(transferId: String) {
        http.clearCancelled(transferId)
    }

    fun isTransferCancelled(transferId: String): Boolean = http.isTransferCancelled(transferId)

    /** Invokes an authenticated Supabase Edge Function (`/functions/v1/<slug>`). */
    suspend fun invokeFunction(slug: String, body: JsonObject = JsonObject(emptyMap())): JsonObject? {
        val text = http.execute(
            method = "POST",
            url = SupabaseConfig.endpoint("functions/v1/$slug"),
            body = body.toString(),
            bearer = token()
        )
        if (text.isBlank()) return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
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
