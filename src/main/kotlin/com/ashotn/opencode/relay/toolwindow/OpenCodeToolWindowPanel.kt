package com.ashotn.opencode.relay.toolwindow

import com.ashotn.opencode.relay.OpenCodeExecutableResolutionState
import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.OpenCodeInfoChangedListener
import com.ashotn.opencode.relay.ServerState
import com.ashotn.opencode.relay.ServerStateListener
import com.ashotn.opencode.relay.core.DiffHunksChangedListener
import com.ashotn.opencode.relay.core.OpenCodeCoreService
import com.ashotn.opencode.relay.core.session.SessionStateChangedListener
import com.ashotn.opencode.relay.ipc.PermissionChangedListener
import com.ashotn.opencode.relay.permission.OpenCodePermissionService
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeSettings.TerminalEngine
import com.ashotn.opencode.relay.settings.OpenCodeSettingsChangedListener
import com.ashotn.opencode.relay.settings.effectiveForIde
import com.ashotn.opencode.relay.terminal.ClassicTuiPanel
import com.ashotn.opencode.relay.terminal.ReworkedTuiPanel
import com.ashotn.opencode.relay.terminal.TuiPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Graphics
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

class OpenCodeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    companion object {
        private const val CARD_CONTENT = "content"
        private const val CARD_PENDING = "pending"

        /** Installs a theme-adaptive, 1px divider on [pane] so it matches the IDE border color. */
        fun applyThemedDivider(pane: JSplitPane) {
            pane.setUI(object : BasicSplitPaneUI() {
                override fun createDefaultDivider(): BasicSplitPaneDivider =
                    object : BasicSplitPaneDivider(this) {
                        override fun paint(g: Graphics) {
                            g.color = JBColor.border()
                            g.fillRect(0, 0, width, height)
                        }
                    }
            })
            pane.border = null
        }
    }

    private var slotDisposable: Disposable = Disposer.newDisposable("OpenCodeToolWindowPanel.slot")

    private val outerCardLayout = CardLayout()
    private val outerCardPanel = JPanel(outerCardLayout)
    private val mcpWarningPanel = JetBrainsMcpWarningPanel(project)
    private val pendingFilesPanel = PendingFilesPanel(project, this)
    private var tuiPanel: TuiPanel = createTuiPanel()
    private var activeTuiEngine: TerminalEngine = configuredTuiEngine()
    private val syncScheduled = AtomicBoolean(false)
    private val plugin = OpenCodePlugin.getInstance(project)
    private val serverStateListener = ServerStateListener { requestSyncCard() }
    private var expandedDividerLocation: Int? = null

    // Split pane that stacks content (top) and TUI (bottom).
    // The TUI half is hidden until the server is READY.
    private val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, outerCardPanel, tuiPanel.component.apply {
        minimumSize = JBUI.size(0, 120)
    }).apply {
        // resizeWeight = 0.0: all resize delta goes to the bottom (terminal) panel.
        // This means when the tool window grows, the terminal expands rather than
        // the session/file section, making the terminal the priority for new space.
        resizeWeight = 0.0
        isContinuousLayout = true
        border = null
        // Hide the divider bar until the server is READY.
        dividerSize = 0
        // Start with the divider all the way down so TUI is not visible yet.
        dividerLocation = Int.MAX_VALUE
        applyThemedDivider(this)
    }

    init {
        add(mcpWarningPanel, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)
        outerCardPanel.add(JPanel(BorderLayout()), CARD_CONTENT) // placeholder until buildContent runs
        outerCardPanel.add(pendingFilesPanel, CARD_PENDING)

        Disposer.register(plugin, this)
        Disposer.register(this, slotDisposable)
        Disposer.register(this, mcpWarningPanel)

        project.messageBus.connect(this).subscribe(
            DiffHunksChangedListener.TOPIC,
            DiffHunksChangedListener { _ -> requestSyncCard() }
        )

        project.messageBus.connect(this).subscribe(
            SessionStateChangedListener.TOPIC,
            SessionStateChangedListener { requestSyncCard() }
        )

        project.messageBus.connect(this).subscribe(
            PermissionChangedListener.TOPIC,
            PermissionChangedListener { requestSyncCard() }
        )

        plugin.addListener(serverStateListener)

        project.messageBus.connect(this).subscribe(
            OpenCodeSettingsChangedListener.TOPIC,
            OpenCodeSettingsChangedListener { _, _ ->
                swapTuiPanelIfEngineChanged()
                requestSyncCard()
            }
        )

        project.messageBus.connect(this).subscribe(
            OpenCodeInfoChangedListener.TOPIC,
            OpenCodeInfoChangedListener { _ ->
                // Dispose and recreate the slot so the old InstalledPanel is eagerly cleaned up.
                Disposer.dispose(slotDisposable)
                slotDisposable = Disposer.newDisposable("OpenCodeToolWindowPanel.slot")
                Disposer.register(this, slotDisposable)
                buildContent()
            }
        )

        buildContent()
    }

    private fun requestSyncCard() {
        if (!syncScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            syncScheduled.set(false)
            syncCard()
        }
    }

    private fun syncCard() {
        val settings = OpenCodeSettings.getInstance(project)
        val serverReady = plugin.serverState == ServerState.READY
        val inlineTerminal = serverReady && settings.inlineTerminalEnabled

        if (inlineTerminal) {
            tuiPanel.startIfNeeded()
            if (tuiPanel.isStarted) {
                if (!settings.sessionsSectionVisible) {
                    if (splitPane.dividerSize > 0) {
                        expandedDividerLocation = splitPane.dividerLocation
                    }
                    collapseSplit(showTopSection = false, dividerLocation = 0)
                } else {
                    restoreSplitView()
                }
            } else {
                collapseSplit(showTopSection = true)
            }
        } else {
            // Inline terminal disabled or server not running – stop any running widget
            // so it doesn't linger in the background, then collapse the divider.
            if (tuiPanel.isStarted) tuiPanel.stop()
            collapseSplit(showTopSection = true)
        }

        val showPending = when (plugin.serverState) {
            ServerState.READY -> {
                val diffService = OpenCodeCoreService.getInstance(project)
                val permissionService = OpenCodePermissionService.getInstance(project)
                val hasPending = diffService.allTrackedFiles().isNotEmpty()
                val hasSessions = diffService.listSessions().isNotEmpty()
                val hasPermission = permissionService.currentPermission() != null
                hasPending || hasSessions || hasPermission
            }

            else -> false
        }

        outerCardLayout.show(outerCardPanel, if (showPending) CARD_PENDING else CARD_CONTENT)
    }

    private fun collapseSplit(showTopSection: Boolean, dividerLocation: Int = Int.MAX_VALUE) {
        outerCardPanel.isVisible = showTopSection
        splitPane.dividerSize = 0
        splitPane.dividerLocation = dividerLocation
    }

    private fun restoreSplitView() {
        outerCardPanel.isVisible = true
        if (splitPane.dividerSize == 0) {
            ApplicationManager.getApplication().invokeLater {
                val splitPaneHeight = splitPane.height
                if (splitPaneHeight > 1 && !project.isDisposed) {
                    splitPane.dividerSize = JBUI.scale(2)
                    splitPane.dividerLocation = (expandedDividerLocation ?: (splitPaneHeight * 0.25).toInt())
                        .coerceIn(1, splitPaneHeight - 1)
                }
            }
        }
    }

    fun focusTerminal() {
        tuiPanel.focusTerminal()
    }

    /**
     * If the user changed the terminal engine in settings, tears down the current
     * [tuiPanel], swaps in a fresh instance backed by the new engine, and re-wires
     * it into the split pane — all without requiring an IDE restart.
     */
    private fun swapTuiPanelIfEngineChanged() {
        val configuredEngine = configuredTuiEngine()
        if (configuredEngine == activeTuiEngine) return

        // Stop and dispose the old panel.
        if (tuiPanel.isStarted) tuiPanel.stop()
        Disposer.dispose(tuiPanel)
        splitPane.bottomComponent = null

        // Create and wire up the new panel.
        val newPanel = createTuiPanel()
        newPanel.component.minimumSize = JBUI.size(0, 120)
        splitPane.bottomComponent = newPanel.component
        tuiPanel = newPanel
        activeTuiEngine = configuredEngine
    }

    private fun buildContent() {
        val screen = when (val state = plugin.executableResolutionState) {
            OpenCodeExecutableResolutionState.Resolving -> ResolvingExecutablePanel()
            OpenCodeExecutableResolutionState.NotFound -> NotInstalledPanel()
            is OpenCodeExecutableResolutionState.Resolved -> InstalledPanel(project, slotDisposable, state.info)
        }

        // Replace the content card with the new screen
        val existingContent = outerCardPanel.components.firstOrNull { it != pendingFilesPanel }
        if (existingContent != null) outerCardPanel.remove(existingContent)
        outerCardPanel.add(screen, CARD_CONTENT)
        syncCard()
        revalidate()
        repaint()
    }

    /** Creates the terminal panel for the configured [OpenCodeSettings.terminalEngine]. */
    private fun createTuiPanel(): TuiPanel =
        when (configuredTuiEngine()) {
            TerminalEngine.CLASSIC -> ClassicTuiPanel(project, this, onTerminated = { requestSyncCard() })
            TerminalEngine.REWORKED -> ReworkedTuiPanel(project, this, onTerminated = { requestSyncCard() })
        }

    private fun configuredTuiEngine(): TerminalEngine =
        OpenCodeSettings.getInstance(project).terminalEngine.effectiveForIde()


    private var disposed = false

    override fun dispose() {
        if (disposed) return
        disposed = true
        plugin.removeListener(serverStateListener)
    }
}
