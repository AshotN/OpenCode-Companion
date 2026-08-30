package com.ashotn.opencode.relay.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateStoreResetTest {

    @Test
    fun `resetState clears stored state and revision counters`() {
        val store = StateStore()
        val sessionId = "session"
        val path = "/project/file.kt"
        val stateLock = Any()
        store.selectedSessionId = sessionId
        store.busyBySession[sessionId] = true
        store.updatedAtBySession[sessionId] = 1L
        store.hunksBySessionAndFile[sessionId] = emptyMap()
        store.liveHunksBySessionAndFile[sessionId] = emptyMap()
        store.deletedBySession[sessionId] = setOf(path)
        store.addedBySession[sessionId] = setOf(path)
        store.baselineBeforeBySessionAndFile[sessionId] = mapOf(path to "before")
        store.messageSummaryFileCountBySession[sessionId] = 1
        store.messageSummaryFileCountUpdatedAtBySession[sessionId] = 1L
        assertEquals(1L, store.reserveRevisionForSessionDiffApply(stateLock, sessionId, 1L) { 1L })

        store.resetState()

        assertNull(store.selectedSessionId)
        assertTrue(store.busyBySession.isEmpty())
        assertTrue(store.updatedAtBySession.isEmpty())
        assertTrue(store.hunksBySessionAndFile.isEmpty())
        assertTrue(store.liveHunksBySessionAndFile.isEmpty())
        assertTrue(store.deletedBySession.isEmpty())
        assertTrue(store.addedBySession.isEmpty())
        assertTrue(store.baselineBeforeBySessionAndFile.isEmpty())
        assertTrue(store.messageSummaryFileCountBySession.isEmpty())
        assertTrue(store.messageSummaryFileCountUpdatedAtBySession.isEmpty())
        assertEquals(1L, store.reserveRevisionForSessionDiffApply(stateLock, sessionId, 1L) { 1L })
    }
}
