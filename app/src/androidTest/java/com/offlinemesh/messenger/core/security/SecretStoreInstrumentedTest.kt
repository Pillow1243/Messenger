package com.offlinemesh.messenger.core.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Keystore-backed secret store on a real device/emulator.
 *
 * The security property worth testing is that the stored bytes are *not* the plaintext and that a
 * second instance (same alias, new process-equivalent object) can still read them — that is what
 * makes the offline refresh token and the database passphrase survive restarts.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var store: SecretStore

    @Before
    fun setUp() {
        store = SecretStore(context, "instrumented-secrets")
        store.clear()
    }

    @Test
    fun roundTripsASecretThroughTheKeystore() {
        val secret = "refresh-token-" + System.nanoTime()
        store.put("session", secret)
        assertEquals(secret, store.get("session"))
    }

    @Test
    fun overwritesAndRemovesValues() {
        store.put("k", "one")
        store.put("k", "two")
        assertEquals("two", store.get("k"))
        store.remove("k")
        assertNull(store.get("k"))
    }

    @Test
    fun aSecondInstanceSharesTheSameKeystoreKey() {
        store.put("shared", "value-42")
        val other = SecretStore(context, "instrumented-secrets")
        assertEquals("value-42", other.get("shared"))
    }

    @Test
    fun storedCiphertextIsNotThePlaintext() {
        store.put("token", "super-secret-value")
        // The implementation stores base64(iv || ciphertext) in SharedPreferences; reading the raw
        // preference file must not reveal the plaintext.
        val prefs = context.getSharedPreferences("instrumented-secrets", Context.MODE_PRIVATE)
        val all = prefs.all.values.joinToString("|") { it.toString() }
        assertTrue("something must have been stored", all.isNotEmpty())
        assertNotEquals("plaintext must not be stored verbatim", "super-secret-value", all)
        assertTrue(
            "plaintext must not be a substring of the stored value",
            !all.contains("super-secret-value")
        )
    }
}
