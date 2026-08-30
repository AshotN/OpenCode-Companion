@file:Suppress("UnstableApiUsage")

package com.ashotn.opencode.relay.terminal

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.OpenCodeProcessEnvironment
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.settings.processEnvironmentVariables
import com.ashotn.opencode.relay.util.serverUrl
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.jediterm.core.util.TermSize
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.BorderLayout
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
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
 */
class ReworkedTuiPanel(
    private val project: Project,
    parentDisposable: Disposable,
    /** Invoked on the EDT when the shell process terminates. */
    private val onTerminated: (() -> Unit)? = null,
) : JPanel(BorderLayout()), TuiPanel, Disposable {

    private var terminalView: TerminalView? = null
    private var fileDropDisposable: Disposable? = null
    private var hyperlinkMouseGuard: Disposable? = null
    private var osc52Session: ReworkedOsc52Session? = null

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

            val tabBuilder = manager.createTabBuilder()
                .workingDirectory(workingDir)
                .requestFocus(false)
                .tabName("OpenCode Relay")
                .shellCommand(command)
            val startupOptions = ShellStartupOptions.Builder()
                .workingDirectory(workingDir)
                .shellCommand(command)
                .initialTermSize(TermSize(80, 20))
                .build()
            val pendingOsc52Session = AtomicReference<ReworkedOsc52Session?>()
            try {
                runWithModalProgressBlocking(project, "Starting OpenCode terminal") {
                    pendingOsc52Session.set(
                        ReworkedOsc52Session.tryStart(project, startupOptions, tabBuilder) { text ->
                            ApplicationManager.getApplication().invokeLater {
                                if (!project.isDisposed) CopyPasteManager.copyTextToClipboard(text)
                            }
                        }
                    )
                }
                osc52Session = pendingOsc52Session.getAndSet(null)
            } finally {
                pendingOsc52Session.getAndSet(null)?.let { Disposer.dispose(it) }
            }
            osc52Session?.let { session ->
                session.invokeOnTermination {
                    ApplicationManager.getApplication().invokeLater {
                        if (
                            osc52Session === session &&
                            terminalView?.sessionState?.value != TerminalViewSessionState.Running
                        ) {
                            tearDown()
                            onTerminated?.invoke()
                        }
                    }
                }
            }
            if (osc52Session != null) {
                // An injected session is already running and must be connected immediately.
                // The fixed initial size is updated by the view after it is embedded below.
                tabBuilder.deferSessionStartUntilUiShown(false)
            }
            val tab = tabBuilder.createTab()

            // Notify the backend that this directly-created tab is externally owned so it
            // is excluded from native Terminal persistence.
            var detached = false
            val view = try {
                manager.detachTab(tab).also {
                    detached = true
                }
            } finally {
                if (!detached) {
                    Disposer.dispose(tab.content as Disposable)
                }
            }
            terminalView = view
            hyperlinkMouseGuard = installTerminalHyperlinkMouseGuard(view.component)
            installFileDropTarget(view)

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

        } catch (e: LinkageError) {
            tearDown()
            logger.warn("Reworked terminal classes unavailable", e)
            // Panel stays empty.
        } catch (e: Exception) {
            tearDown()
            if (e is CancellationException || e is ControlFlowException) throw e
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            logger.warn("Failed to start reworked terminal", e)
            // Panel stays empty.
        } catch (e: Throwable) {
            tearDown()
            throw e
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

    private fun installFileDropTarget(view: TerminalView) {
        val disposable = Disposer.newDisposable("OpenCode reworked terminal file drop")
        fileDropDisposable = disposable
        DnDSupport.createBuilder(view.component)
            .disableAsSource()
            .enableAsNativeTarget()
            .setDropHandlerWithResult { event ->
                val files = FileCopyPasteUtil.getFileListFromAttachedObject(event.attachedObject)
                if (files.isEmpty()) return@setDropHandlerWithResult false

                view.preferredFocusableComponent.requestFocusInWindow()
                view.coroutineScope.launch {
                    files.forEach { file ->
                        view.createSendTextBuilder()
                            .useBracketedPasteMode()
                            .send(file.absolutePath)
                    }
                }
                true
            }
            .setDisposableParent(disposable)
            .install()
    }

    private fun tearDown() {
        val view = terminalView
        terminalView = null
        val dropTarget = fileDropDisposable
        fileDropDisposable = null
        val mouseGuard = hyperlinkMouseGuard
        hyperlinkMouseGuard = null
        val session = osc52Session
        osc52Session = null

        try {
            try {
                try {
                    mouseGuard?.let { Disposer.dispose(it) }
                } finally {
                    dropTarget?.let { Disposer.dispose(it) }
                }
            } finally {
                session?.let { Disposer.dispose(it) }
            }
        } finally {
            if (view != null) {
                // Detaching transfers ownership from the Terminal tool window to this panel.
                // Cancel the view scope to terminate the process and release the frontend session.
                view.coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                remove(view.component)
                revalidate()
                repaint()
            }
        }
    }

    override fun dispose() {
        tearDown()
    }

    companion object {
        private val logger = Logger.getInstance(ReworkedTuiPanel::class.java)
    }
}
