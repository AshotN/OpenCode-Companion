package com.ashotn.opencode.relay.terminal

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Prevents terminal mouse reporting from receiving the press/release used to follow a hyperlink. */
internal fun installTerminalHyperlinkMouseGuard(root: JComponent): Disposable {
    val disposable = Disposer.newDisposable("OpenCode terminal hyperlink mouse guard")
    IdeEventQueue.getInstance().addDispatcher(
        TerminalHyperlinkMouseGuard(root),
        disposable,
    )
    return disposable
}

internal class TerminalHyperlinkMouseGuard(
    private val root: JComponent,
) : IdeEventQueue.NonLockedEventDispatcher {
    private var guardedPress = false

    override fun dispatch(e: java.awt.AWTEvent): Boolean {
        val mouseEvent = e as? MouseEvent ?: return false
        val source = mouseEvent.component ?: return false
        if (!source.isIn(root) || mouseEvent.button != MouseEvent.BUTTON1) return false

        return when (mouseEvent.id) {
            MouseEvent.MOUSE_PRESSED -> {
                guardedPress = source.cursor.type == Cursor.HAND_CURSOR
                guardedPress
            }

            MouseEvent.MOUSE_RELEASED -> guardedPress.also { guardedPress = false }
            else -> false
        }
    }
}

private fun Component.isIn(root: JComponent): Boolean =
    this === root || SwingUtilities.isDescendingFrom(this, root)
