package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.api.session.Session
import com.ashotn.opencode.relay.api.session.SessionSummary
import com.ashotn.opencode.relay.api.session.SessionTime
import org.junit.Test
import kotlin.test.assertEquals

class MessageSummaryFileCountLoadPlannerTest {

    @Test
    fun `message summary file count loads only newest eligible summary sessions up to batch size`() {
        val sessions = listOf(
            session("newest", updated = 50, summarized = true),
            session("plain", updated = 100, summarized = false),
            session("loaded", updated = 60, summarized = true),
            session("middle", updated = 30, summarized = true),
            session("newer", updated = 45, summarized = true),
        )

        val selected = selectMessageSummaryFileCountLoadBatch(
            sessions = sessions,
            maxBatchSize = 2,
        ) { session -> session.id != "loaded" }

        assertEquals(listOf("newest", "newer"), selected.map { it.id })
    }

    private fun session(id: String, updated: Long, summarized: Boolean): Session = Session(
        id = id,
        projectID = null,
        directory = null,
        parentID = null,
        title = id,
        version = null,
        time = SessionTime(created = 0, updated = updated, compacting = null),
        summary = if (summarized) SessionSummary(additions = 0, deletions = 0, files = 0, diffs = null) else null,
        share = null,
    )
}
