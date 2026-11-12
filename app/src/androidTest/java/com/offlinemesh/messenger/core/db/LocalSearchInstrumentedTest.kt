package com.offlinemesh.messenger.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offlinemesh.messenger.domain.LikePattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The search queries, run by a real SQLite.
 *
 * They need a device: Room checks a query's syntax when the app is compiled, but SQLite checks the
 * `ESCAPE` argument only when it compares the first row. The contact query carried a two-character
 * escape for exactly that reason - it compiled, and failed on every search once a contact existed.
 */
@RunWith(AndroidJUnit4::class)
class LocalSearchInstrumentedTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()

        db.contactDao().upsert(ContactEntity(deviceId = "d1", displayName = "Sara 100%", addedAt = 1L, username = "sara_k"))
        db.contactDao().upsert(ContactEntity(deviceId = "d2", displayName = "Sarah", addedAt = 1L, username = "sarahk"))

        db.conversationDao().upsert(conversation("c1", "Budget_2026", "we are at 50% now"))
        db.conversationDao().upsert(conversation("c2", "Budget 2026", "we are at 500 now"))

        db.messageDao().upsert(message("m1", "c1", "we are at 50% now"))
        db.messageDao().upsert(message("m2", "c2", "we are at 500 now"))
        db.messageDao().upsert(message("m3", "c2", "path is C:\\temp"))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun everySearchQueryRunsWithRowsPresent() = runBlocking {
        val pattern = LikePattern.contains("sar")
        assertEquals(2, db.contactDao().search(pattern).size)
        assertEquals(2, db.conversationDao().search(LikePattern.contains("budget")).size)
        assertEquals(2, db.messageDao().search(LikePattern.contains("we are"), null).size)
        assertTrue(db.peerDao().searchProfiles(pattern).isEmpty())
    }

    @Test
    fun aPercentSignIsSearchedLiterally() = runBlocking {
        assertEquals(listOf("m1"), db.messageDao().search(LikePattern.contains("50%"), null).map { it.id })
        assertEquals(listOf("c1"), db.conversationDao().search(LikePattern.contains("50%")).map { it.id })
        assertEquals(listOf("d1"), db.contactDao().search(LikePattern.contains("100%")).map { it.deviceId })
    }

    @Test
    fun anUnderscoreIsSearchedLiterally() = runBlocking {
        assertEquals(listOf("c1"), db.conversationDao().search(LikePattern.contains("budget_2")).map { it.id })
        assertEquals(listOf("d1"), db.contactDao().search(LikePattern.contains("sara_")).map { it.deviceId })
    }

    @Test
    fun aBackslashIsSearchedLiterally() = runBlocking {
        assertEquals(listOf("m3"), db.messageDao().search(LikePattern.contains("C:\\t"), null).map { it.id })
    }

    @Test
    fun aSearchCanBeLimitedToOneConversation() = runBlocking {
        assertEquals(listOf("m2"), db.messageDao().search(LikePattern.contains("we are"), "c2").map { it.id })
    }

    private fun conversation(id: String, title: String, preview: String) = ConversationEntity(
        id = id,
        title = title,
        isGroup = false,
        memberIds = "peer-$id",
        createdAt = 1L,
        lastMessageAt = 1L,
        lastMessagePreview = preview
    )

    private fun message(id: String, conversationId: String, body: String) = MessageEntity(
        id = id,
        conversationId = conversationId,
        senderDeviceId = "self",
        outgoing = true,
        type = "TEXT",
        body = body,
        timestamp = 2L,
        status = "SENT"
    )
}
