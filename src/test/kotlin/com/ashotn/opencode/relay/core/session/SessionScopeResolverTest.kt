package com.ashotn.opencode.relay.core.session

import com.ashotn.opencode.relay.api.session.Session
import com.ashotn.opencode.relay.api.session.SessionTime
import org.junit.Test
import kotlin.test.assertEquals

class SessionScopeResolverTest {

    private val resolver = SessionScopeResolver()

    @Test
    fun `selected root family excludes an unrelated root session`() {
        val session1 = "ses_001"
        val session2 = "ses_002"

        val knownSessionIds = setOf(session1, session2)
        val sessions = mapOf(
            session1 to Session(
                id = session1,
                projectID = null,
                directory = null,
                parentID = null,
                title = "Session 1",
                version = null,
                time = SessionTime(0L, 0L, null),
                summary = null,
                share = null
            ),
            session2 to Session(
                id = session2,
                projectID = null,
                directory = null,
                parentID = null,
                title = "Session 2",
                version = null,
                time = SessionTime(0L, 0L, null),
                summary = null,
                share = null
            ),
        )

        val familyForSession1 = resolver.familySessionIds(
            selectedSessionId = session1,
            sessions = sessions,
            knownSessionIds = knownSessionIds,
            busyBySession = emptyMap(),
            updatedAtBySession = emptyMap(),
            hunksBySessionAndFile = emptyMap(),
            nowMillis = 0L,
        )

        assertEquals(
            setOf(session1),
            familyForSession1,
            "Session 1's family must contain only Session 1 — not the unrelated Session 2 — " +
                    "but got: $familyForSession1",
        )
    }
}
