package com.offlinemesh.messenger.core.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the 1 -> 2 upgrade that every existing installation runs on first launch.
 *
 * Instrumented Room migration tests need a device, so the contract is enforced here instead: the
 * SQL must be additive, every new column must be nullable or defaulted, and the columns the online
 * transport actually reads/writes must be among the ones created.
 */
class MigrationTest {

    private val statements = MIGRATION_1_2_STATEMENTS

    private val sql = statements.joinToString("\n")

    @Test
    fun `migration is additive only`() {
        val destructive = listOf(
            "DROP TABLE", "DROP INDEX", "DELETE FROM", "TRUNCATE", "ALTER TABLE RENAME",
            "RENAME TO", "CREATE TABLE messages AS"
        )
        destructive.forEach { keyword ->
            assertFalse("$keyword would destroy user data", sql.uppercase().contains(keyword))
        }
        assertTrue(sql.contains("ALTER TABLE conversations ADD COLUMN"))
        assertTrue(sql.contains("ALTER TABLE messages ADD COLUMN"))
    }

    @Test
    fun `new columns are nullable or defaulted`() {
        statements.filter { it.startsWith("ALTER TABLE") && it.contains("ADD COLUMN") }
            .forEach { statement ->
                val upper = statement.uppercase()
                assertTrue(
                    "column without DEFAULT or NULL would break existing rows: $statement",
                    upper.contains("DEFAULT") || upper.contains("NULL")
                )
            }
    }

    @Test
    fun `online transport columns exist`() {
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
            assertTrue("missing $column", sql.contains(column))
        }
    }

    @Test
    fun `new tables are created for the outbox, sync cursors and peer caches`() {
        listOf("online_outbox", "sync_state", "peer_devices", "peer_profiles").forEach { table ->
            assertTrue(
                "missing table $table",
                sql.contains("CREATE TABLE IF NOT EXISTS $table", ignoreCase = true)
            )
        }
    }

    @Test
    fun `existing rows are backfilled instead of dropped`() {
        assertTrue(sql.contains("UPDATE conversations SET type ="))
        assertTrue(sql.contains("UPDATE messages SET clientMsgId = id"))
        // Backfills must be scoped so a second run cannot rewrite data.
        assertTrue(sql.contains("WHERE clientMsgId = ''"))
    }

    @Test
    fun `indexes back every new lookup the sync engine performs`() {
        listOf(
            "index_conversations_onlineId",
            "index_messages_clientMsgId",
            "index_messages_remoteId"
        ).forEach { index ->
            assertTrue("missing $index", sql.contains(index))
        }
    }
}
