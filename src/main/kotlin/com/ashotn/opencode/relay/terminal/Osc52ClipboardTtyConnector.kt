package com.ashotn.opencode.relay.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import org.jetbrains.plugins.terminal.ProxyTtyConnector
import java.util.Base64

internal class Osc52ClipboardTtyConnector(
    override val connector: TtyConnector,
    clipboardWriter: (String) -> Unit,
) : ProxyTtyConnector {
    private val clipboardHandler = Osc52ClipboardHandler(clipboardWriter)

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val count = connector.read(buf, offset, length)
        if (count > 0) {
            clipboardHandler.process(String(buf, offset, count))
        }
        return count
    }

    override fun write(bytes: ByteArray) = connector.write(bytes)

    override fun write(string: String) = connector.write(string)

    override fun isConnected(): Boolean = connector.isConnected

    override fun waitFor(): Int = connector.waitFor()

    override fun ready(): Boolean = connector.ready()

    override fun getName(): String = connector.name

    override fun close() = connector.close()

    override fun resize(termSize: TermSize) = connector.resize(termSize)
}

internal class Osc52ClipboardHandler(
    private val clipboardWriter: (String) -> Unit,
) {
    private val buffer = StringBuilder()

    fun process(text: CharSequence) {
        buffer.append(text)

        while (true) {
            val startIndex = buffer.indexOf(OSC52_PREFIX)
            if (startIndex < 0) {
                keepPotentialPrefixSuffix()
                return
            }

            if (startIndex > 0) {
                buffer.delete(0, startIndex)
            }

            val terminator = findTerminator() ?: run {
                trimOversizedIncompleteSequence()
                return
            }

            val body = buffer.substring(OSC52_PREFIX.length, terminator.startIndex)
            handleOsc52Body(body)
            buffer.delete(0, terminator.startIndex + terminator.length)
        }
    }

    private fun handleOsc52Body(body: String) {
        val separatorIndex = body.indexOf(';')
        if (separatorIndex < 0) return

        val payload = body.substring(separatorIndex + 1)
        if (payload == "?") return

        val decodedText = try {
            Base64.getDecoder().decode(payload).toString(Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return
        }

        clipboardWriter(decodedText)
    }

    private fun findTerminator(): Terminator? {
        val fromIndex = OSC52_PREFIX.length
        val belIndex = buffer.indexOf(BEL, fromIndex)
        val stIndex = buffer.indexOf(ST, fromIndex)
        val c1StIndex = buffer.indexOf(C1_ST, fromIndex)

        return listOfNotNull(
            belIndex.takeIf { it >= 0 }?.let { Terminator(it, BEL.length) },
            stIndex.takeIf { it >= 0 }?.let { Terminator(it, ST.length) },
            c1StIndex.takeIf { it >= 0 }?.let { Terminator(it, C1_ST.length) },
        ).minByOrNull { it.startIndex }
    }

    private fun keepPotentialPrefixSuffix() {
        val keepLength = minOf(buffer.length, OSC52_PREFIX.length - 1)
        if (buffer.length > keepLength) {
            buffer.delete(0, buffer.length - keepLength)
        }
    }

    private fun trimOversizedIncompleteSequence() {
        if (buffer.length > 8 * 1024 * 1024) {
            buffer.clear()
        }
    }

    private data class Terminator(val startIndex: Int, val length: Int)

    companion object {
        private const val OSC52_PREFIX = "\u001b]52;"
        private const val BEL = "\u0007"
        private const val ST = "\u001b\\"
        private const val C1_ST = "\u009c"
    }
}
