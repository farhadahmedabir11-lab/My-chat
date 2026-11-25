package com.example

import com.example.data.ChatUtils
import com.example.model.MessageStatus
import com.example.model.PrivateChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `deterministic chat id prevents duplicate conversations regardless of order`() {
        val chatA = ChatUtils.deterministicChatId("uid_bob", "uid_alice")
        val chatB = ChatUtils.deterministicChatId("uid_alice", "uid_bob")
        assertEquals("uid_alice_uid_bob", chatA)
        assertEquals(chatA, chatB)
    }

    @Test
    fun `username validation and normalization enforces rules`() {
        assertEquals("farhad_11", ChatUtils.normalizeUsername("  @Farhad_11 "))
        assertTrue(ChatUtils.isValidUsername("farhad_11"))
        assertFalse(ChatUtils.isValidUsername("ab"))
        assertFalse(ChatUtils.isValidUsername("bad-user!"))
    }

    @Test
    fun `sha256 email hash is deterministic and hides raw email`() {
        val h1 = ChatUtils.sha256Email("  Alice@Gmail.com ")
        val h2 = ChatUtils.sha256Email("alice@gmail.com")
        assertEquals(64, h1.length)
        assertEquals(h1, h2)
        assertFalse(h1.contains("alice", ignoreCase = true))
    }

    @Test
    fun `message status safe parsing falls back cleanly`() {
        assertEquals(MessageStatus.SENT, MessageStatus.fromRaw("SENT"))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.fromRaw("DELIVERED"))
        assertEquals(MessageStatus.SEEN, MessageStatus.fromRaw("SEEN"))
        assertEquals(MessageStatus.SENT, MessageStatus.fromRaw("UNKNOWN_FUTURE_VALUE"))
    }

    @Test
    fun `private chat helper methods resolve peer metadata safely`() {
        val chat = PrivateChat(
            id = "alice_bob",
            participantIds = listOf("alice", "bob"),
            participantNames = mapOf("alice" to "Alice", "bob" to "Bob"),
            participantUsernames = mapOf("alice" to "alice_r", "bob" to "bob_k"),
            unreadCounts = mapOf("alice" to 3L, "bob" to 0L)
        )
        assertEquals("bob", chat.peerId("alice"))
        assertEquals("Bob", chat.peerName("alice"))
        assertEquals("bob_k", chat.peerUsername("alice"))
        assertEquals(3, chat.unreadFor("alice"))
        assertEquals(0, chat.unreadFor("bob"))
    }

    @Test
    fun `phase 2 duration size formatting and mime validators work accurately`() {
        assertEquals("00:05", ChatUtils.formatDurationSeconds(5))
        assertEquals("02:15", ChatUtils.formatDurationSeconds(135))
        assertEquals("1:01:05", ChatUtils.formatDurationSeconds(3665))
        assertEquals("512 B", ChatUtils.formatFileSize(512))
        assertEquals("1.5 MB", ChatUtils.formatFileSize(1_572_864))
        assertTrue(ChatUtils.isSupportedVideoMime("video/mp4"))
        assertTrue(ChatUtils.isSupportedVideoMime("video/webm"))
        assertFalse(ChatUtils.isSupportedVideoMime("video/x-msvideo"))
    }

    @Test
    fun `call history entry resolves direction peer and outcome safely`() {
        val entry = com.example.model.CallHistoryEntry(
            id = "call_1",
            callId = "call_1",
            callerId = "alice",
            callerName = "Alice",
            calleeId = "bob",
            calleeName = "Bob",
            participantIds = listOf("alice", "bob"),
            callType = "VIDEO",
            outcome = "MISSED",
            durationSeconds = 0L
        )
        assertTrue(entry.isOutgoing("alice"))
        assertFalse(entry.isOutgoing("bob"))
        assertEquals("Bob", entry.peerName("alice"))
        assertEquals("Alice", entry.peerName("bob"))
        assertEquals(com.example.model.CallType.VIDEO, entry.parsedCallType)
        assertEquals(com.example.model.CallOutcome.MISSED, entry.parsedOutcome)
        assertEquals(com.example.model.MessageType.IMAGE, com.example.model.MessageType.fromRaw("IMAGE"))
        assertEquals(com.example.model.MessageType.TEXT, com.example.model.MessageType.fromRaw(null))
    }

    @Test
    fun `free tier quota snapshot blocks media and writes cleanly when limits exceeded`() {
        val base = com.example.data.FreeTierQuotaSnapshot(
            dayKey = "2026-11-24",
            dailyFirestoreWrites = 100L,
            maxDailyFirestoreWrites = com.example.data.FreeTierQuotaManager.MAX_DAILY_FIRESTORE_WRITES,
            dailyMediaBytes = 1024L,
            maxDailyMediaBytes = com.example.data.FreeTierQuotaManager.MAX_DAILY_MEDIA_BYTES,
            firestoreQuotaExceeded = false,
            activeWarningMessage = null
        )
        assertFalse(base.isMediaBlocked)

        val mediaCapped = base.copy(dailyMediaBytes = com.example.data.FreeTierQuotaManager.MAX_DAILY_MEDIA_BYTES)
        assertTrue(mediaCapped.isMediaBlocked)

        val writeCapped = base.copy(dailyFirestoreWrites = com.example.data.FreeTierQuotaManager.MAX_DAILY_FIRESTORE_WRITES)
        assertTrue(writeCapped.isMediaBlocked)

        val quotaFlagged = base.copy(firestoreQuotaExceeded = true)
        assertTrue(quotaFlagged.isMediaBlocked)
    }

    @Test
    fun `bandwidth optimization constants keep shared images and media within Spark free tier budgets`() {
        assertEquals(10L * 1024L * 1024L, com.example.data.MediaProcessor.MAX_VIDEO_BYTES)
        assertEquals(1080, com.example.data.MediaProcessor.MAX_IMAGE_DIMENSION)
        assertEquals(380_000, com.example.data.MediaProcessor.MAX_PHOTO_BYTES)
    }
}
