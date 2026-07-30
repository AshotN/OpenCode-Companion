package com.ashotn.opencode.relay.terminal

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Osc52ClipboardHandlerTest {
    @Test
    fun `decodes BEL ST and C1 ST terminated sequences`() {
        val clipboardWrites = mutableListOf<String>()
        val handler = Osc52ClipboardHandler(clipboardWrites::add)

        handler.process(sequence("BEL", "\u0007"))
        handler.process(sequence("ST", "\u001b\\"))
        handler.process(sequence("C1 ST", "\u009c"))

        assertEquals(listOf("BEL", "ST", "C1 ST"), clipboardWrites)
    }

    @Test
    fun `handles sequences split across connector reads`() {
        val clipboardWrites = mutableListOf<String>()
        val handler = Osc52ClipboardHandler(clipboardWrites::add)

        sequence("split clipboard", "\u001b\\").forEach { char ->
            handler.process(char.toString())
        }

        assertEquals(listOf("split clipboard"), clipboardWrites)
    }

    @Test
    fun `handles multiple sequences in one connector read`() {
        val clipboardWrites = mutableListOf<String>()
        val handler = Osc52ClipboardHandler(clipboardWrites::add)

        handler.process("before${sequence("first", "\u0007")}between${sequence("second", "\u009c")}after")

        assertEquals(listOf("first", "second"), clipboardWrites)
    }

    @Test
    fun `ignores queries and malformed payloads`() {
        val clipboardWrites = mutableListOf<String>()
        val handler = Osc52ClipboardHandler(clipboardWrites::add)

        handler.process("\u001b]52;c;?\u0007")
        handler.process("\u001b]52;c;%%%\u0007")
        handler.process("\u001b]52;missing-separator\u0007")

        assertTrue(clipboardWrites.isEmpty())
    }

    private fun sequence(text: String, terminator: String): String {
        val payload = Base64.getEncoder().encodeToString(text.toByteArray())
        return "\u001b]52;c;$payload$terminator"
    }
}
