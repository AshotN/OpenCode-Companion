package com.ashotn.opencode.relay.terminal

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.OpenCodeProcessEnvironment
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.settings.processEnvironmentVariables
import com.ashotn.opencode.relay.util.serverUrl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalPanel
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.util.ui.ImageUtil
import com.jediterm.terminal.TtyConnector
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.BorderLayout
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JPanel

/**
 * Hosts an embedded classic terminal running `opencode attach <server-url>`.
 *
 * Backed by [LocalTerminalDirectRunner] with a [ShellStartupOptions.shellCommand] override.
 * The terminal is started lazily on the first call to [startIfNeeded] and lives for as long
 * as this panel's parent [Disposable] is alive.
 */
class ClassicTuiPanel(
    private val project: Project,
    parentDisposable: Disposable,
    /** Invoked on the EDT when the shell process terminates. */
    private val onTerminated: (() -> Unit)? = null,
    /** Injected process used in tests to verify the kill path without live terminal infrastructure. */
    internal val processOverride: Process? = null,
) : JPanel(BorderLayout()), TuiPanel, Disposable {

    private var terminalWidget: TerminalWidget? = null
    private var terminalPanel: JBTerminalPanel? = null
    private var hyperlinkMouseGuard: Disposable? = null

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
        if (terminalWidget != null) return

        //For test
        if (processOverride != null) {
            terminalWidget = noOpTerminalWidget()
            return
        }

        try {
            val executablePath = OpenCodePlugin.getInstance(project).openCodeInfo?.path
            if (executablePath.isNullOrBlank()) {
                logger.warn("Skipping classic terminal start because OpenCode executable is unresolved")
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

            val runner = ClipboardAwareTerminalRunner(project)
            val startupOptions = ShellStartupOptions.Builder()
                .workingDirectory(workingDir)
                .shellCommand(command)
                .envVariables(environmentVariables)
                .build()

            val widget = runner.startShellTerminalWidget(this, startupOptions, true)
            terminalWidget = widget
            Disposer.register(this, widget)
            terminalPanel =
                ShellTerminalWidget.asShellJediTermWidget(widget)?.terminalPanel
                    ?.also { panel ->
                        installEmbeddedTerminalDataProvider(project, panel)
                        hyperlinkMouseGuard = installTerminalHyperlinkMouseGuard(panel)
                        installFileDropTarget(panel)
                        installClipboardFilePasteHandler(panel)
                    }

            // When the shell process exits, clean up and notify the owner.
            widget.addTerminationCallback({
                ApplicationManager.getApplication().invokeLater {
                    logger.debug("Classic terminal process terminated")
                    if (terminalWidget === widget) {
                        uninstallEmbeddedTerminalIntegrations()
                        terminalWidget = null
                        remove(widget.component)
                        revalidate()
                        repaint()
                        onTerminated?.invoke()
                    }
                }
            }, this)

            add(widget.component, BorderLayout.CENTER)

            revalidate()
            repaint()

        } catch (e: NoClassDefFoundError) {
            logger.warn("Classic terminal classes unavailable", e)
            // Panel stays empty.
        } catch (e: Exception) {
            logger.warn("Failed to start classic terminal", e)
            // Panel stays empty.
        }
    }

    override fun focusTerminal() {
        terminalWidget?.preferredFocusableComponent?.requestFocusInWindow()
    }

    override val isStarted: Boolean get() = terminalWidget != null

    override fun stop() = tearDown()

    private fun tearDown() {
        val widget = terminalWidget ?: return
        uninstallEmbeddedTerminalIntegrations()
        terminalWidget = null
        remove(widget.component)
        revalidate()
        repaint()
        val process: Process? = processOverride
            ?: widget.ttyConnectorAccessor.ttyConnector
                ?.let { ShellTerminalWidget.getProcessTtyConnector(it)?.process }
        val handle = process?.let { ProcessHandle.of(it.pid()).orElse(null) }
        killProcessTree(handle)
        Disposer.dispose(widget)
    }

    override fun dispose() = tearDown()

    private fun uninstallEmbeddedTerminalIntegrations() {
        hyperlinkMouseGuard?.let { Disposer.dispose(it) }
        hyperlinkMouseGuard = null
        terminalPanel?.let { panel ->
            uninstallEmbeddedTerminalDataProvider(panel)
            panel.dropTarget = null
        }
        terminalPanel = null
    }

    private fun installFileDropTarget(panel: JBTerminalPanel) {
        panel.dropTarget = DropTarget(panel, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun drop(event: DropTargetDropEvent) {
                if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    event.rejectDrop()
                    return
                }

                event.acceptDrop(DnDConstants.ACTION_COPY)
                try {
                    val files = droppedFiles(event)
                    if (files.isEmpty()) {
                        event.dropComplete(false)
                        return
                    }

                    panel.requestFocusInWindow()
                    sendFilesAsBracketedPaste(panel, files)
                    event.dropComplete(true)
                } catch (e: Exception) {
                    logger.warn("Failed to handle dropped file for OpenCode TUI", e)
                    event.dropComplete(false)
                }
            }
        }, true)
    }

    private fun installClipboardFilePasteHandler(panel: JBTerminalPanel) {
        panel.addPreKeyEventHandler { event ->
            if (!event.isPasteShortcutPress()) return@addPreKeyEventHandler

            val files = clipboardFiles()
            if (files.isEmpty()) return@addPreKeyEventHandler

            event.consume()
            sendFilesAsBracketedPaste(panel, files)
        }
    }

    private fun sendFilesAsBracketedPaste(panel: JBTerminalPanel, files: List<File>) {
        files.forEach { file -> sendBracketedPaste(panel, file.absolutePath) }
    }

    private fun sendBracketedPaste(panel: JBTerminalPanel, text: String) {
        panel.terminalOutputStream.sendString("\u001b[200~$text\u001b[201~", false)
    }

    private fun droppedFiles(event: DropTargetDropEvent): List<File> {
        val data = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return emptyList()
        return data.filterIsInstance<File>()
    }

    private fun clipboardFiles(): List<File> {
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null) ?: return emptyList()
            filesFromTransferable(contents)
        } catch (e: Exception) {
            logger.debug("Failed to read files from clipboard", e)
            emptyList()
        }
    }

    private fun filesFromTransferable(transferable: Transferable): List<File> {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val data = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return emptyList()
            val files = data.filterIsInstance<File>()
            if (files.isNotEmpty()) return files
        }

        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return emptyList()

        val image = transferable.getTransferData(DataFlavor.imageFlavor) as? Image ?: return emptyList()
        return listOf(writeImageToTempPng(image))
    }

    private fun writeImageToTempPng(image: Image): File {
        val file = Files.createTempFile("opencode-clipboard-", ".png").toFile()
        file.deleteOnExit()
        try {
            if (!ImageIO.write(image.toBufferedImage(), "png", file)) {
                throw IOException("No PNG writer is available")
            }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun Image.toBufferedImage(): BufferedImage {
        if (this is BufferedImage) return this

        val icon = ImageIcon(this)
        val width = icon.iconWidth
        val height = icon.iconHeight
        if (width <= 0 || height <= 0) throw IOException("Clipboard image has invalid dimensions")

        val bufferedImage = ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = bufferedImage.createGraphics()
        try {
            graphics.drawImage(icon.image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return bufferedImage
    }

    private fun KeyEvent.isPasteShortcutPress(): Boolean {
        if (id != KeyEvent.KEY_PRESSED) return false
        if (keyCode != KeyEvent.VK_V) return false
        return modifiersEx == Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
    }

    companion object {
        private val logger = Logger.getInstance(ClassicTuiPanel::class.java)

        private fun noOpTerminalWidget(): TerminalWidget =
            Proxy.newProxyInstance(
                TerminalWidget::class.java.classLoader,
                arrayOf(TerminalWidget::class.java, ComponentContainer::class.java, Disposable::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getComponent", "getPreferredFocusableComponent" -> JPanel()
                    "dispose" -> Unit
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            } as TerminalWidget
    }

    private class ClipboardAwareTerminalRunner(project: Project) : LocalTerminalDirectRunner(project) {
        override fun createTtyConnector(startupOptions: ShellStartupOptions): TtyConnector =
            wrapTtyConnector(super.createTtyConnector(startupOptions))

        private fun wrapTtyConnector(delegate: TtyConnector): TtyConnector =
            Osc52ClipboardTtyConnector(delegate) { text ->
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) CopyPasteManager.copyTextToClipboard(text)
                }
            }
    }
}
