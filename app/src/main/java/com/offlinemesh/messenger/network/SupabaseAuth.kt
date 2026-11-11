package com.offlinemesh.messenger.network

import com.offlinemesh.messenger.core.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** An authenticated Supabase session. The refresh token never leaves encrypted storage. */
@Serializable
data class AuthSession(
    val userId: String,
    val email: String? = null,
    val accessToken: String,
    val refreshToken: String,
    /** Epoch seconds. */
    val expiresAt: Long
) {
    fun isFresh(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = expiresAt - nowSeconds > 90
}

class AuthException(val reason: String, message: String) : Exception(message)

/**
 * Email/password authentication against GoTrue (Supabase auth).
 *
 * Refresh tokens are rotated by the server; the newest one is kept in [SecretStore] so a killed
 * process resumes the session without asking the user to sign in again. All network failures are
 * surfaced as [AuthException] with a stable [AuthException.reason] the UI can translate.
 */
class SupabaseAuth(
    private val http: SupabaseHttp,
    private val secrets: SecretStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _session = MutableStateFlow(restore())
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    fun acknowledgeSessionExpired() {
        _sessionExpired.value = false
    }

    fun currentUserId(): String? = _session.value?.userId

    suspend fun signUp(email: String, password: String): AuthSession {
        val trimmed = email.trim().lowercase()
        requireCredentials(trimmed, password)
        val response = tokenRequest(
            path = "signup",
            query = emptyList(),
            body = buildJsonObject {
                put("email", trimmed)
                put("password", password)
            }
        )
        return persist(parse(response, requiresSession = true))
    }

    suspend fun signIn(email: String, password: String): AuthSession {
        val trimmed = email.trim().lowercase()
        requireCredentials(trimmed, password)
        val response = tokenRequest(
            path = "token",
            query = listOf("grant_type" to "password"),
            body = buildJsonObject {
                put("email", trimmed)
                put("password", password)
            }
        )
        return persist(parse(response, requiresSession = true))
    }

    suspend fun signOut() {
        val current = _session.value
        if (current != null) {
            runCatching {
                http.execute(
                    method = "POST",
                    url = authUrl("logout", emptyList()),
                    bearer = current.accessToken
                )
            }
        }
        clear()
    }

    /** Returns a token that is valid for at least another ~90 seconds, refreshing if needed. */
    suspend fun validAccessToken(): String? = mutex.withLock {
        val current = _session.value ?: return@withLock null
        if (current.isFresh()) return@withLock current.accessToken
        if (current.refreshToken.isBlank()) {
            clear()
            return@withLock null
        }
        try {
            val response = tokenRequest(
                path = "token",
                query = listOf("grant_type" to "refresh_token"),
                body = buildJsonObject { put("refresh_token", current.refreshToken) }
            )
            persist(parse(response, requiresSession = true)).accessToken
        } catch (e: AuthException) {
            // Preserve the refresh token across transient offline/rate-limit/5xx errors so going
            // offline never logs the user out; only wipe when the server rejects the grant.
            if (e.reason == "network" || e.reason == "rate_limited" || e.reason == "server_error") {
                null
            } else {
                clear(expired = true)
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(expired: Boolean = false) {
        _session.value = null
        secrets.remove(KEY_SESSION)
        if (expired) _sessionExpired.value = true
    }

    // ------------------------------------------------------------------ internals

    private fun requireCredentials(email: String, password: String) {
        if (!email.contains('@') || email.length < 5) {
            throw AuthException("invalid_email", "Enter a valid email address")
        }
        if (password.length < 6) {
            throw AuthException("weak_password", "Password must be at least 6 characters")
        }
    }

    private fun restore(): AuthSession? {
        val stored = secrets.get(KEY_SESSION) ?: return null
        return runCatching { json.decodeFromString(AuthSession.serializer(), stored) }.getOrNull()
    }

    private fun persist(session: AuthSession): AuthSession {
        _sessionExpired.value = false
        _session.value = session
        secrets.put(KEY_SESSION, json.encodeToString(AuthSession.serializer(), session))
        return session
    }

    private fun authUrl(path: String, query: List<Pair<String, String>>): okhttp3.HttpUrl? {
        val base = SupabaseConfig.endpoint("auth/v1/$path") ?: return null
        val builder = base.newBuilder()
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private suspend fun tokenRequest(
        path: String,
        query: List<Pair<String, String>>,
        body: JsonObject
    ): JsonObject {
        if (!SupabaseConfig.configured) throw AuthException("not_configured", "Online mode is not configured")
        val text = try {
            http.execute(
                method = "POST",
                url = authUrl(path, query),
                body = body.toString(),
                headers = mapOf("Content-Type" to "application/json")
            )
        } catch (e: SupabaseException) {
            throw AuthException(mapReason(e), e.message ?: "Authentication failed")
        }
        return runCatching { json.parseToJsonElement(text) as JsonObject }
            .getOrElse { throw AuthException("bad_response", "Unexpected response from the server") }
    }

    private fun mapReason(e: SupabaseException): String = when {
        e.code == 0 -> "network"
        e.code == 429 -> "rate_limited"
        e.message?.contains("Invalid login", ignoreCase = true) == true -> "invalid_credentials"
        e.message?.contains("already registered", ignoreCase = true) == true -> "email_in_use"
        e.message?.contains("Email not confirmed", ignoreCase = true) == true -> "email_not_confirmed"
        e.code >= 500 -> "server_error"
        else -> e.reason ?: "auth_failed"
    }

    private fun parse(response: JsonObject, requiresSession: Boolean): AuthSession {
        val access = response["access_token"]?.jsonPrimitive?.contentOrNull
        if (access.isNullOrBlank()) {
            if (requiresSession) {
                // Signup with email confirmation enabled returns the user but no session.
                throw AuthException("email_not_confirmed", "Confirm your email address, then sign in")
            }
            throw AuthException("bad_response", "Missing access token")
        }
        val refresh = response["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expiresIn = response["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        val user = response["user"] as? JsonObject
        val userId = user?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (userId.isBlank()) throw AuthException("bad_response", "Missing user id")
        return AuthSession(
            userId = userId,
            email = user?.get("email")?.jsonPrimitive?.contentOrNull,
            accessToken = access,
            refreshToken = refresh,
            expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        )
    }

    private companion object {
        const val KEY_SESSION = "supabase_session_v1"
    }
}
