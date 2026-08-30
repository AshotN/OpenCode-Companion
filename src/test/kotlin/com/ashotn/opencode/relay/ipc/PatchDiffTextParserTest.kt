package com.ashotn.opencode.relay.ipc

import com.google.gson.JsonParser
import org.junit.Test
import kotlin.test.assertEquals

class PatchDiffTextParserTest {

    @Test
    fun `preserves no newline at end of file marker`() {
        val obj = JsonParser.parseString(
            """
            {
              "file": "a.txt",
              "patch": "Index: a.txt\n===================================================================\n--- a.txt\t\n+++ a.txt\t\n@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file\n"
            }
            """.trimIndent()
        ).asJsonObject

        val diffText = PatchDiffTextParser.parse(obj)

        assertEquals("old", diffText.before)
        assertEquals("new", diffText.after)
    }
}
