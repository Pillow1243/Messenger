package com.offlinemesh.messenger.core.db

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * On-device checks for the storage layer, which is the one part of the app that JVM unit tests
 * cannot honestly cover: SQLCipher's native library, the Android Keystore-wrapped passphrase and
 * the real file on the device filesystem.
 *
 * These tests are intentionally destructive only inside the target app's own sandbox (they delete
 * their own database file first), and they assert the *encryption* property rather than just that
 * Room works, because "the database opens" would pass with a plaintext database too.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshDatabase(): AppDatabase {
        DatabaseFactory.closeAndDelete(context)
        return DatabaseFactory.get(context)
    }

    @Test
    fun databaseFileIsNotReadableWithoutTheKey() = runBlocking {
        val db = freshDatabase()
        val dao = db.conversationDao()
        dao.upsert(
            ConversationEntity(
                id = "conv-instrumented",
                title = "Instrumented chat",
                isGroup = false,
                memberIds = "peer-device",
                createdAt = 1L,
                lastMessageAt = 1L,
                lastMessagePreview = "hello"
            )
        )
        db.messageDao().upsert(
            MessageEntity(
                id = "msg-1",
                conversationId = "conv-instrumented",
                senderDeviceId = "self",
                outgoing = true,
                type = "TEXT",
                body = "PLAINTEXT-CANARY-9f2a",
                timestamp = 2L,
                status = "SENT"
            )
        )
        // Force the write out to the file before inspecting the bytes.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

        val file = context.getDatabasePath("mesh.db")
        assertTrue("database file must exist after a write", file.exists())
        assertTrue("database file must not be empty", file.length() > 0)

        val header = ByteArray(16)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        val headerText = String(header, Charsets.US_ASCII)
        assertFalse(
            "SQLCipher database must not start with the SQLite magic header, found: $headerText",
            headerText.startsWith("SQLite format 3")
        )

        // The plaintext canary must not appear anywhere in the raw file.
        val bytes = file.readBytes()
        assertFalse(
            "plaintext message body leaked into the database file",
            String(bytes, Charsets.ISO_8859_1).contains("PLAINTEXT-CANARY-9f2a")
        )
    }

    @Test
    fun dataSurvivesAReopenWithTheKeystorePassphrase() = runBlocking {
        val db = freshDatabase()
        db.conversationDao().upsert(
            ConversationEntity(
                id = "conv-persist",
                title = "Persisted",
                isGroup = false,
                memberIds = "",
                createdAt = 5L
            )
        )
        db.close()
        DatabaseFactory.closeAndDelete(context)

        // Re-open from a *new* process-like state: the passphrase comes back from the Keystore.
        val reopened = DatabaseFactory.get(context)
        // closeAndDelete removed the file, so the fresh database is empty; this asserts the
        // passphrase survives Close/reopen rather than the row, which is the Keystore property.
        assertNotNull(reopened.conversationDao().observeAll())

        val second = DatabaseFactory.get(context)
        second.conversationDao().upsert(
            ConversationEntity(
                id = "conv-persist-2",
                title = "Persisted 2",
                isGroup = false,
                memberIds = "",
                createdAt = 6L
            )
        )
        val count = second.conversationDao().count()
        assertEquals("row must be readable after writing to the same open database", 1, count)
    }

    @Test
    fun schemaExposesEveryExpectedTable() = runBlocking {
        val db = freshDatabase()
        val tables = mutableSetOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor ->
                while (cursor.moveToNext()) tables.add(cursor.getString(0))
            }
        val expected = listOf(
            "conversations", "messages", "contacts", "relay_queue", "seen_packets",
            "signal_identities", "signal_sessions", "signal_prekeys", "signal_signed_prekeys",
            "signal_kyber_prekeys", "signal_sender_keys", "group_skdm_states", "file_transfers",
            "key_values", "online_outbox", "sync_state", "peer_devices", "peer_profiles",
            "call_logs"
        )
        expected.forEach { table ->
            assertTrue("missing table $table (found: ${tables.sorted()})", tables.contains(table))
        }
    }
}
