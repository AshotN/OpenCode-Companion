@file:Suppress("UnstableApiUsage")

package com.ashotn.opencode.relay.terminal

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.OpenCodeProcessEnvironment
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.settings.processEnvironmentVariables
import com.ashotn.opencode.relay.util.serverUrl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Hosts an embedded Reworked Terminal running `opencode attach <server-url>`.
 *
 * Uses the experimental [TerminalToolWindowTabsManager] API to create a detached terminal
 * session that is never shown or persisted in the native Terminal tool window. Correct
 * detached-session persistence requires IntelliJ Platform 2026.2 or newer. The
 * [TerminalView.component] is embedded in the panel's [BorderLayout.CENTER].
 *
 * The terminal is started lazily on the first call to [startIfNeeded] and lives
 * for as long as this panel's parent [Disposable] is alive.
 *
 * **Testability note:** unlike [ClassicTuiPanel], this panel cannot be unit-tested
 * with a stub process. It delegates process cleanup to the detached [TerminalView]
 * coroutine scope, with no explicit process handle to inject or assert against.
 * [TerminalToolWindowTabsManager] also requires a fully initialised IDE frontend
 * that is not available in a headless test environment.
 */
class ReworkedTuiPanel(
    private val project: Project,
    parentDisposable: Disposable,
    /** Invoked on the EDT when the shell process terminates. */
    private val onTerminated: (() -> Unit)? = null,
) : JPanel(BorderLayout()), TuiPanel, Disposable {

    private var terminalView: TerminalView? = null

    init {
        Disposer.register(parentDisposable, this)
    }

    override val component: JPanel get() = this

    /**
     * Creates and embeds the terminal (once). Safe to call multiple times —
     * subsequent calls are no-ops while a session is alive.
     *
     * Must be called on the EDT.
     */
    override fun startIfNeeded() {
        if (terminalView != null) return

        try {
            val executablePath = OpenCodePlugin.getInstance(project).openCodeInfo?.path
            if (executablePath.isNullOrBlank()) {
                logger.warn("Skipping reworked terminal start because OpenCode executable is unresolved")
                return
            }

            val workingDir = project.basePath ?: System.getProperty("user.home")
            val environmentVariables = OpenCodeSettings.getInstance(project)
                .processEnvironmentVariables(OpenCodeServerAuth.getInstance(project).connectionEnvironmentVariables())
            val command = OpenCodeProcessEnvironment.terminalCommand(
                listOf(
                    executablePath,
                    "attach",
                    serverUrl(OpenCodeSettings.getInstance(project).serverPort),
                ),
                environmentVariables,
            )
            val manager = TerminalToolWindowTabsManager.getInstance(project)

            val tab = manager.createTabBuilder()
                .workingDirectory(workingDir)
                .requestFocus(false)
                .shouldAddToToolWindow(false)
                .tabName("OpenCode Relay")
                .shellCommand(command)
                .createTab()

            // Notify the backend that this directly-created tab is externally owned so it
            // is excluded from native Terminal persistence.
            val view = try {
                manager.detachTab(tab)
            } catch (e: Exception) {
                Disposer.dispose(tab.content as Disposable)
                throw e
            }
            terminalView = view

            // Watch sessionState flow: when Terminated the shell has exited.
            view.coroutineScope.launch {
                view.sessionState.collect { state ->
                    logger.debug("Terminal session state $state")
                    if (state is TerminalViewSessionState.Terminated) {
                        ApplicationManager.getApplication().invokeLater {
                            if (terminalView === view) {
                                tearDown()
                                onTerminated?.invoke()
                            }
                        }
                    }
                }
            }

            add(view.component, BorderLayout.CENTER)
            revalidate()
            repaint()

        } catch (e: NoClassDefFoundError) {
            logger.warn("Reworked terminal classes unavailable", e)
            // Panel stays empty.
        } catch (e: Exception) {
            logger.warn("Failed to start reworked terminal", e)
            // Panel stays empty.
        }
    }

    override fun focusTerminal() {
        val view = terminalView ?: return
        view.preferredFocusableComponent.requestFocusInWindow()
    }

    /** True while a terminal session is live. */
    override val isStarted: Boolean get() = terminalView != null

    /** Tears down the running session. The next [startIfNeeded] will create a fresh one. */
    override fun stop() = tearDown()

    private fun tearDown() {
        val view = terminalView ?: return
        terminalView = null
        remove(view.component)
        revalidate()
        repaint()
        // Detaching transfers ownership from the Terminal tool window to this panel.
        // Cancel the view scope to terminate the process and release the frontend session.
        view.coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    override fun dispose() {
        tearDown()
    }

    companion object {
        private val logger = Logger.getInstance(ReworkedTuiPanel::class.java)
    }
}
