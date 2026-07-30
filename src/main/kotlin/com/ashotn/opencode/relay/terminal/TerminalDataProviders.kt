package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.Project
import com.intellij.terminal.JBTerminalPanel
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent

private const val DATA_PROVIDER_CLIENT_PROPERTY = "DataProvider"

internal fun installEmbeddedTerminalDataProvider(
    project: Project,
    panel: JBTerminalPanel,
) {
    // JBTerminalPanel's internal TerminalEscapeKeyListener moves focus back to the editor
    // whenever the terminal reports a ToolWindow in its data context. Our terminal is embedded
    // inside a custom tool window. Install the override on the terminal panel itself so the
    // explicit null wins before any ancestor ToolWindow provider in the data-context chain.
    installTerminalToolWindowOverride(panel)
    installEmbeddedTerminalKeyOverrides(panel)
    installClassicTerminalHyperlinkFilter(project, panel)
}

internal fun uninstallEmbeddedTerminalDataProvider(panel: JBTerminalPanel) {
    uninstallTerminalToolWindowOverride(panel)
}

internal fun installTerminalToolWindowOverride(component: JComponent) {
    component.putClientProperty(DATA_PROVIDER_CLIENT_PROPERTY, DataProvider { dataId ->
        when {
            PlatformDataKeys.TOOL_WINDOW.`is`(dataId) -> CustomizedDataContext.EXPLICIT_NULL
            else -> null
        }
    })
}

internal fun uninstallTerminalToolWindowOverride(component: JComponent) {
    component.putClientProperty(DATA_PROVIDER_CLIENT_PROPERTY, null)
}

internal fun installEmbeddedTerminalKeyOverrides(panel: JBTerminalPanel) {
    panel.addPreKeyEventHandler { event ->
        if (consumeEmbeddedTerminalControlKey(event)) {
            event.consume()
        }
    }
}

internal fun consumeEmbeddedTerminalControlKey(event: KeyEvent): Boolean {
    return event.isCtrlZPress()
}

private fun KeyEvent.isCtrlZPress(): Boolean {
    if (id != KeyEvent.KEY_PRESSED) return false
    if (keyCode != KeyEvent.VK_Z) return false
    return modifiersEx == InputEvent.CTRL_DOWN_MASK
}
