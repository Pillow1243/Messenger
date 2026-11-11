package com.offlinemesh.messenger.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards both the 1 -> 2 and 2 -> 3 Room migrations so upgrading existing installations never
 * destroys local SQLCipher messages, contacts, Signal sessions, or Sender Keys.
 */
class MigrationTest {

    private val sql12 = MIGRATION_1_2_STATEMENTS.joinToString("\n")
    private val sql23 = MIGRATION_2_3_STATEMENTS.joinToString("\n")

    @Test
    fun `current database version is 3`() {
        assertEquals(3, DatabaseVersion.CURRENT)
    }

    @Test
    fun `migrations 1_2 and 2_3 are strictly additive`() {
        val destructive = listOf(
            "DROP TABLE", "DROP INDEX", "DELETE FROM", "TRUNCATE", "ALTER TABLE RENAME",
            "RENAME TO", "CREATE TABLE messages AS"
        )
        for (sql in listOf(sql12, sql23)) {
            destructive.forEach { keyword ->
                assertFalse("$keyword would destroy user data", sql.uppercase().contains(keyword))
            }
        }
        assertTrue(sql12.contains("ALTER TABLE conversations ADD COLUMN"))
        assertTrue(sql23.contains("ALTER TABLE conversations ADD COLUMN"))
        assertTrue(sql23.contains("ALTER TABLE messages ADD COLUMN"))
    }

    @Test
    fun `all added columns in 1_2 and 2_3 are nullable or defaulted`() {
        (MIGRATION_1_2_STATEMENTS + MIGRATION_2_3_STATEMENTS)
            .filter { it.startsWith("ALTER TABLE") && it.contains("ADD COLUMN") }
            .forEach { statement ->
                val upper = statement.uppercase()
                assertTrue(
                    "column without DEFAULT or NULL would break existing rows: $statement",
                    upper.contains("DEFAULT") || upper.contains("NULL")
                )
            }
    }

    @Test
    fun `v1_2 online transport columns exist`() {
        val required = listOf(
            "conversations ADD COLUMN onlineId",
            "conversations ADD COLUMN peerUserId",
            "conversations ADD COLUMN memberUserIds",
            "conversations ADD COLUMN draft",
            "messages ADD COLUMN clientMsgId",
            "messages ADD COLUMN remoteId",
            "messages ADD COLUMN transport",
            "messages ADD COLUMN editedAt",
            "messages ADD COLUMN deletedAt",
            "messages ADD COLUMN reactions",
            "messages ADD COLUMN deliveredAt",
            "messages ADD COLUMN readAt",
            "contacts ADD COLUMN userId",
            "contacts ADD COLUMN username",
            "file_transfers ADD COLUMN plaintextSha256"
        )
        required.forEach { column ->
            assertTrue("missing $column", sql12.contains(column))
        }
    }

    @Test
    fun `v2_3 phase 2 media, sender keys, mute, device and call columns & tables exist`() {
        val requiredColumns = listOf(
            "conversations ADD COLUMN muted",
            "conversations ADD COLUMN mutedUntil",
            "conversations ADD COLUMN pinned",
            "conversations ADD COLUMN archived",
            "conversations ADD COLUMN encryptionVersion",
            "conversations ADD COLUMN senderKeyEpoch",
            "conversations ADD COLUMN distributionId",
            "conversations ADD COLUMN myRole",
            "messages ADD COLUMN attachmentRemoteId",
            "messages ADD COLUMN remoteObjectPath",
            "messages ADD COLUMN cipherSha256",
            "messages ADD COLUMN cipherSize",
            "messages ADD COLUMN mediaKeyBase64",
            "messages ADD COLUMN mediaIvBase64",
            "messages ADD COLUMN thumbnailBase64",
            "messages ADD COLUMN mediaWidth",
            "messages ADD COLUMN mediaHeight",
            "messages ADD COLUMN transferState",
            "messages ADD COLUMN transferBytes",
            "messages ADD COLUMN senderDeviceRowId",
            "peer_devices ADD COLUMN name",
            "peer_devices ADD COLUMN platform",
            "peer_devices ADD COLUMN verified"
        )
        requiredColumns.forEach { column ->
            assertTrue("missing v2->v3 column: $column", sql23.contains(column))
        }

        listOf("signal_sender_keys", "group_skdm_states", "seen_packets", "call_logs").forEach { table ->
            assertTrue(
                "missing v2->v3 table: $table",
                sql23.contains("CREATE TABLE IF NOT EXISTS $table")
            )
        }

        listOf(
            "index_messages_attachmentRemoteId",
            "index_group_skdm_states_conversationId_epoch",
            "index_seen_packets_seenAt",
            "index_call_logs_conversationId"
        ).forEach { idx ->
            assertTrue("missing v2->v3 index: $idx", sql23.contains(idx))
        }
    }
}
