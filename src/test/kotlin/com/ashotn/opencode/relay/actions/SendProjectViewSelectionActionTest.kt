package com.ashotn.opencode.relay.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class SendProjectViewSelectionActionTest {
    @Test
    fun `mixed selection formatting appends slash only to directories`() {
        val references = listOf(
            formatProjectViewReference("src/main/App.kt", isDirectory = false),
            formatProjectViewReference("src/main/kotlin", isDirectory = true),
        ).joinToString(" ")

        assertEquals("@src/main/App.kt @src/main/kotlin/", references)
        assertEquals("@./", formatProjectViewReference("", isDirectory = true))
        assertEquals("@src/", formatProjectViewReference("src/", isDirectory = true))
    }
}
