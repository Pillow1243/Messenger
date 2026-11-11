package com.offlinemesh.messenger.network

import com.offlinemesh.messenger.core.crypto.MediaTransferCancelledException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A backend call failed. [code] is the HTTP status (0 for transport/parse failures). */
class SupabaseException(
    val code: Int,
    val reason: String?,
    message: String
) : Exception(message)

/**
 * Minimal, explicit HTTP layer for Supabase (Auth, PostgREST, Edge Functions, and streaming
 * Storage uploads/downloads).
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

    /** Streaming media client with no total call-duration cap so multi-100 MB transfers complete cleanly. */
    private val mediaClient: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    fun okHttpClient(): OkHttpClient = client

    fun cancelTransfer(transferId: String) {
        cancelledIds.add(transferId)
        activeCalls.remove(transferId)?.cancel()
    }

    fun clearCancelled(transferId: String) {
        cancelledIds.remove(transferId)
    }

    fun isTransferCancelled(transferId: String): Boolean = cancelledIds.contains(transferId)

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
        } catch (e: IOException) {
            throw SupabaseException(0, "network", e.message ?: "Network error")
        }
    }

    /**
     * Streams an encrypted file directly from disk to Supabase Storage without loading the file
     * into memory. Uses TUS 1.0.0 chunked resumable upload (6 MiB chunks) for files >= 6 MiB so
     * interrupted large uploads resume from the last committed server offset, and single-stream
     * `POST /storage/v1/object/...` for smaller files.
     */
    suspend fun uploadFileResumable(
        transferId: String,
        baseStorageUrl: HttpUrl?,
        bucket: String,
        objectPath: String,
        sourceFile: File,
        bearer: String,
        resumeTusUrl: String? = null,
        onTusUrlCreated: ((String) -> Unit)? = null,
        onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val base = baseStorageUrl ?: throw SupabaseException(0, "not_configured", "Backend is not configured")
        val total = sourceFile.length()
        if (cancelledIds.contains(transferId)) throw MediaTransferCancelledException()

        if (total < TUS_THRESHOLD_BYTES) {
            val directUrl = base.newBuilder()
                .addPathSegments("object/$bucket")
                .addPathSegments(objectPath.trimStart('/'))
                .build()
            streamSliceRequest(
                transferId = transferId,
                method = "POST",
                url = directUrl,
                sourceFile = sourceFile,
                offset = 0L,
                length = total,
                contentType = OCTET_MEDIA,
                bearer = bearer,
                extraHeaders = mapOf("x-upsert" to "true"),
                onByteProgress = { sent -> onProgress?.invoke(sent, total) }
            )
            return@withContext
        }

        // TUS 1.0.0 resumable upload for >= 6 MiB blobs
        val tusEndpoint = base.newBuilder().addPathSegments("upload/resumable").build()
        var uploadUrl: HttpUrl? = resumeTusUrl?.toHttpUrlOrNull()
        var offset = 0L

        if (uploadUrl != null) {
            offset = queryTusOffset(transferId, uploadUrl, bearer) ?: run {
                uploadUrl = null
                0L
            }
        }

        if (uploadUrl == null) {
            val metadata = listOf(
                "bucketName" to bucket,
                "objectName" to objectPath.trimStart('/'),
                "contentType" to "application/octet-stream",
                "cacheControl" to "3600"
            ).joinToString(",") { (k, v) ->
                "$k ${Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8))}"
            }
            val createReq = Request.Builder()
                .url(tusEndpoint)
                .header("apikey", apiKey)
                .header("Authorization", "Bearer $bearer")
                .header("Tus-Resumable", "1.0.0")
                .header("x-upsert", "true")
                .header("Upload-Length", total.toString())
                .header("Upload-Metadata", metadata)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val call = mediaClient.newCall(createReq)
            activeCalls[transferId] = call
            try {
                call.execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw errorFrom(resp.code, body)
                    val loc = resp.header("Location")
                        ?: throw SupabaseException(0, "tus_no_location", "Missing TUS Location header")
                    uploadUrl = tusEndpoint.resolve(loc) ?: loc.toHttpUrlOrNull()
                        ?: throw SupabaseException(0, "tus_bad_location", "Invalid TUS Location header")
                    onTusUrlCreated?.invoke(uploadUrl.toString())
                }
            } catch (e: IOException) {
                if (cancelledIds.contains(transferId) || call.isCanceled()) {
                    throw MediaTransferCancelledException()
                }
                throw SupabaseException(0, "network", e.message ?: "Upload creation failed")
            } finally {
                activeCalls.remove(transferId, call)
            }
        }

        val resolvedUploadUrl = uploadUrl!!
        onProgress?.invoke(offset, total)

        while (offset < total) {
            if (cancelledIds.contains(transferId)) throw MediaTransferCancelledException()
            val chunkLen = minOf(TUS_CHUNK_BYTES, total - offset)
            val baseOffset = offset
            val newOffset = streamSliceRequest(
                transferId = transferId,
                method = "PATCH",
                url = resolvedUploadUrl,
                sourceFile = sourceFile,
                offset = baseOffset,
                length = chunkLen,
                contentType = TUS_PATCH_MEDIA,
                bearer = bearer,
                extraHeaders = mapOf(
                    "Tus-Resumable" to "1.0.0",
                    "Upload-Offset" to baseOffset.toString()
                ),
                onByteProgress = { inChunk -> onProgress?.invoke(baseOffset + inChunk, total) }
            )
            offset = newOffset ?: (baseOffset + chunkLen)
        }
    }

    private fun queryTusOffset(transferId: String, url: HttpUrl, bearer: String): Long? {
        val req = Request.Builder()
            .url(url)
            .head()
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $bearer")
            .header("Tus-Resumable", "1.0.0")
            .build()
        val call = mediaClient.newCall(req)
        activeCalls[transferId] = call
        return try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.header("Upload-Offset")?.toLongOrNull()
            }
        } catch (_: IOException) {
            null
        } finally {
            activeCalls.remove(transferId, call)
        }
    }

    private fun streamSliceRequest(
        transferId: String,
        method: String,
        url: HttpUrl,
        sourceFile: File,
        offset: Long,
        length: Long,
        contentType: MediaType,
        bearer: String,
        extraHeaders: Map<String, String>,
        onByteProgress: (Long) -> Unit
    ): Long? {
        val body = object : RequestBody() {
            override fun contentType(): MediaType = contentType
            override fun contentLength(): Long = length

            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(sourceFile, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(STREAM_BUFFER_BYTES)
                    var remaining = length
                    var sent = 0L
                    while (remaining > 0L) {
                        if (cancelledIds.contains(transferId)) throw MediaTransferCancelledException()
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = raf.read(buf, 0, toRead)
                        if (read <= 0) break
                        sink.write(buf, 0, read)
                        sink.flush()
                        remaining -= read
                        sent += read
                        onByteProgress(sent)
                    }
                }
            }
        }
        val builder = Request.Builder()
            .url(url)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $bearer")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "POST" -> builder.post(body)
            "PUT" -> builder.put(body)
            "PATCH" -> builder.patch(body)
            else -> throw IllegalArgumentException("Unsupported upload method $method")
        }

        val call = mediaClient.newCall(builder.build())
        activeCalls[transferId] = call
        try {
            call.execute().use { resp ->
                val respText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw errorFrom(resp.code, respText)
                return resp.header("Upload-Offset")?.toLongOrNull()
            }
        } catch (e: MediaTransferCancelledException) {
            throw e
        } catch (e: SupabaseException) {
            throw e
        } catch (e: IOException) {
            if (cancelledIds.contains(transferId) || call.isCanceled()) {
                throw MediaTransferCancelledException()
            }
            throw SupabaseException(0, "network", e.message ?: "Upload interrupted")
        } finally {
            activeCalls.remove(transferId, call)
        }
    }

    /**
     * Streams a download from an expiring signed Storage URL into [destPartFile], automatically
     * resuming via `Range: bytes=<offset>-` if a partial download already exists on disk.
     */
    suspend fun downloadFileResumable(
        transferId: String,
        url: HttpUrl,
        destPartFile: File,
        expectedTotalBytes: Long? = null,
        bearer: String? = null,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        if (cancelledIds.contains(transferId)) throw MediaTransferCancelledException()
        destPartFile.parentFile?.mkdirs()
        val existingBytes = if (destPartFile.exists()) destPartFile.length() else 0L
        if (expectedTotalBytes != null && expectedTotalBytes > 0L && existingBytes == expectedTotalBytes) {
            onProgress?.invoke(existingBytes, expectedTotalBytes)
            return@withContext destPartFile
        }

        val builder = Request.Builder().url(url).get()
        if (bearer != null) {
            builder.header("apikey", apiKey)
            builder.header("Authorization", "Bearer $bearer")
        }
        if (existingBytes > 0L) {
            builder.header("Range", "bytes=$existingBytes-")
        }

        val call = mediaClient.newCall(builder.build())
        activeCalls[transferId] = call
        try {
            call.execute().use { resp ->
                if (resp.code == 416 && expectedTotalBytes != null && existingBytes >= expectedTotalBytes) {
                    return@withContext destPartFile
                }
                if (!resp.isSuccessful) {
                    val errText = resp.body?.string().orEmpty()
                    throw errorFrom(resp.code, errText)
                }
                val append = resp.code == 206 && existingBytes > 0L
                val startOffset = if (append) existingBytes else 0L
                val body = resp.body ?: throw SupabaseException(0, "empty_body", "Empty storage response")
                val contentLen = body.contentLength()
                val total = expectedTotalBytes
                    ?: if (contentLen > 0L) startOffset + contentLen else -1L

                body.byteStream().use { input ->
                    FileOutputStream(destPartFile, append).use { fos ->
                        val buf = ByteArray(STREAM_BUFFER_BYTES)
                        var current = startOffset
                        while (true) {
                            if (cancelledIds.contains(transferId)) throw MediaTransferCancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                fos.write(buf, 0, n)
                                current += n
                                onProgress?.invoke(current, total)
                            }
                        }
                        fos.flush()
                    }
                }
                destPartFile
            }
        } catch (e: MediaTransferCancelledException) {
            throw e
        } catch (e: SupabaseException) {
            throw e
        } catch (e: IOException) {
            if (cancelledIds.contains(transferId) || call.isCanceled()) {
                throw MediaTransferCancelledException()
            }
            throw SupabaseException(0, "network", e.message ?: "Download interrupted")
        } finally {
            activeCalls.remove(transferId, call)
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
        val OCTET_MEDIA = "application/octet-stream".toMediaType()
        val TUS_PATCH_MEDIA = "application/offset+octet-stream".toMediaType()
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val TUS_THRESHOLD_BYTES = 6L * 1024 * 1024
        const val TUS_CHUNK_BYTES = 6L * 1024 * 1024
    }
}
