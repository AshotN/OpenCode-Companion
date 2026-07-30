package com.ashotn.opencode.relay.terminal

import com.intellij.ide.DataManager
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.terminal.JBTerminalPanel
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.lang.reflect.Proxy
import java.util.function.Consumer
import javax.swing.JPanel

class TerminalDataProvidersTest : BasePlatformTestCase() {

    fun `test terminal panel override hides ancestor tool window from data context`() {
        val container = JPanel(BorderLayout())
        val terminalPanel = JPanel(BorderLayout())
        val toolWindow = toolWindowStub()

        container.putClientProperty("DataProvider", DataProvider { dataId ->
            when {
                PlatformDataKeys.TOOL_WINDOW.`is`(dataId) -> toolWindow
                else -> null
            }
        })
        container.add(terminalPanel, BorderLayout.CENTER)

        installTerminalToolWindowOverride(terminalPanel)
        try {
            ApplicationManager.getApplication().invokeAndWait {
                val dataContext = DataManager.getInstance().getDataContext(terminalPanel)
                assertNull(dataContext.getData(PlatformDataKeys.TOOL_WINDOW))
            }
        } finally {
            uninstallTerminalToolWindowOverride(terminalPanel)
        }
    }

    fun `test embedded terminal data provider installs ctrl z key override without intercepting escape`() {
        val terminalPanel = createTerminalPanel()
        val existingHandlers = preKeyEventHandlers(terminalPanel)

        try {
            installEmbeddedTerminalDataProvider(project, terminalPanel)

            val handlers = preKeyEventHandlers(terminalPanel)
            val addedHandlers = handlers.drop(existingHandlers.size)
            assertEquals(1, addedHandlers.size)

            val ctrlZ = KeyEvent(
                terminalPanel,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_Z,
                'Z',
            )
            addedHandlers.forEach { it.accept(ctrlZ) }
            assertTrue(ctrlZ.isConsumed)

            val escape = KeyEvent(
                terminalPanel,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_ESCAPE,
                KeyEvent.CHAR_UNDEFINED,
            )
            addedHandlers.forEach { it.accept(escape) }
            assertFalse(escape.isConsumed)
        } finally {
            ensureTerminalPanelCanBeDisposed(terminalPanel)
            Disposer.dispose(terminalPanel)
        }
    }

    fun `test classic hyperlink filter resolves local paths with optional line`() {
        data class Case(
            val text: String,
            val target: String,
            val relativePath: String,
            val lineNumber: Int?,
        )

        val cases = listOf(
            Case("See ./README.md.", "./README.md", "README.md", null),
            Case("See src/main/Example.kt:42;", "src/main/Example.kt:42", "src/main/Example.kt", 42),
        )

        cases.forEach { case ->
            val file = File(project.basePath, case.relativePath).apply {
                parentFile?.mkdirs()
                writeText((1..50).joinToString("\n") { "line $it" })
            }
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file))

            var navigatedFile: VirtualFileAndLine? = null
            val result = ClassicTerminalHyperlinkFilter(project) { virtualFile, lineNumber ->
                navigatedFile = VirtualFileAndLine(virtualFile.path, lineNumber)
            }.apply(case.text)

            val item = result!!.items.single()
            val targetStart = case.text.indexOf(case.target)
            assertEquals(targetStart, item.startOffset)
            assertEquals(targetStart + case.target.length, item.endOffset)
            item.linkInfo.navigate()
            assertEquals(file.path, navigatedFile?.path)
            assertEquals(case.lineNumber, navigatedFile?.lineNumber)
        }
    }

    fun `test classic hyperlink filter ignores missing files and file URIs`() {
        val file = File(project.basePath, "note.md").apply { writeText("one\ntwo\nthree\n") }
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file))
        val filter = ClassicTerminalHyperlinkFilter(project)

        assertNull(filter.apply("See ./missing.md:2"))
        assertNull(filter.apply("See ./note.md:0"))
        assertNull(filter.apply("See ${file.toURI()}"))
    }

    fun `test classic hyperlink filter resolves a directory`() {
        val directory = File(project.basePath, "src/main/kotlin").apply { mkdirs() }
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory))
        val line = "Open src/main/kotlin/."
        val target = "src/main/kotlin/"
        var navigatedPath: String? = null
        var navigatedToDirectory = false

        val result = ClassicTerminalHyperlinkFilter(project) { virtualFile, lineNumber ->
            navigatedPath = virtualFile.path
            navigatedToDirectory = virtualFile.isDirectory
            assertNull(lineNumber)
        }.apply(line)

        val item = result!!.items.single()
        assertEquals(line.indexOf(target), item.startOffset)
        assertEquals(line.indexOf(target) + target.length, item.endOffset)
        item.linkInfo.navigate()
        assertEquals(directory.path, navigatedPath)
        assertTrue(navigatedToDirectory)
    }

    fun `test OpenCode file mention filter resolves line ranges from the project root`() {
        val firstFile = File(project.basePath, "note.md").apply { writeText("one\ntwo\nthree\n") }
        val secondFile = File(project.basePath, "src/File.kt").apply {
            parentFile.mkdirs()
            writeText("one\ntwo\n")
        }
        val firstVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(firstFile)
        val secondVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(secondFile)
        assertNotNull(firstVirtualFile)
        assertNotNull(secondVirtualFile)
        val line = "Review @note.md#L2-3, then @src/File.kt#L1."

        val result = createOpenCodeFileMentionFilter(project).applyFilter(line, line.length)

        val items = result!!.resultItems
        assertEquals(2, items.size)
        val firstMention = "@note.md#L2-3"
        assertEquals(line.indexOf(firstMention), items[0].highlightStartOffset)
        assertEquals(line.indexOf(firstMention) + firstMention.length, items[0].highlightEndOffset)
        val firstHyperlink = items[0].hyperlinkInfo as OpenFileHyperlinkInfo
        assertEquals(firstVirtualFile!!.path, firstHyperlink.virtualFile?.path)
        assertEquals(secondVirtualFile!!.path, (items[1].hyperlinkInfo as OpenFileHyperlinkInfo).virtualFile?.path)
        firstHyperlink.navigate(project)
        assertEquals(1, FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)
    }

    fun `test OpenCode file mention filter rejects malformed line anchors`() {
        val file = File(project.basePath, "note.md").apply { writeText("one\ntwo\nthree\n") }
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file))
        val filter = createOpenCodeFileMentionFilter(project)

        assertNull(filter.applyFilter("@note.md#L0", "@note.md#L0".length))
        assertNull(filter.applyFilter("@note.md#L2abc", "@note.md#L2abc".length))
        assertNull(filter.applyFilter("@note.md#L2-3foo", "@note.md#L2-3foo".length))
        val overflow = "@note.md#L99999999999999999999"
        assertNull(filter.applyFilter(overflow, overflow.length))
        val validAfterOverflow = "$overflow @note.md#L2"
        assertEquals(1, filter.applyFilter(validAfterOverflow, validAfterOverflow.length)!!.resultItems.size)
    }

    fun `test OpenCode file mention filter resolves a trailing slash directory mention only`() {
        val directory = File(project.basePath, "src").apply { mkdirs() }
        val virtualDirectory = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)
        assertNotNull(virtualDirectory)
        val filter = createOpenCodeFileMentionFilter(project)
        val mention = "@src/"
        val line = "Open $mention, ask @agent, or email dev@example.com."

        val result = filter.applyFilter(line, line.length)

        val item = result!!.resultItems.single()
        assertEquals(line.indexOf(mention), item.highlightStartOffset)
        assertEquals(line.indexOf(mention) + mention.length, item.highlightEndOffset)
        val hyperlink = item.hyperlinkInfo as OpenFileHyperlinkInfo
        assertEquals(virtualDirectory!!.path, hyperlink.virtualFile?.path)
        assertTrue(hyperlink.virtualFile!!.isDirectory)

        val ambiguous = "Ignore @agent, dev@example.com, and @src/main"
        assertNull(filter.applyFilter(ambiguous, ambiguous.length))

        val projectRoot = filter.applyFilter("Open @./", "Open @./".length)!!.resultItems.single()
        val projectRootHyperlink = projectRoot.hyperlinkInfo as OpenFileHyperlinkInfo
        assertEquals(project.basePath, projectRootHyperlink.virtualFile?.path)
    }

    private fun toolWindowStub(): ToolWindow =
        Proxy.newProxyInstance(
            ToolWindow::class.java.classLoader,
            arrayOf(ToolWindow::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "ToolWindowStub"
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as ToolWindow

    private fun createTerminalPanel(): JBTerminalPanel {
        lateinit var terminalPanel: JBTerminalPanel
        ApplicationManager.getApplication().invokeAndWait {
            val styleState = StyleState()
            terminalPanel = JBTerminalPanel(
                JBTerminalSystemSettingsProviderBase(),
                TerminalTextBuffer(80, 24, styleState),
                styleState,
            )
        }
        return terminalPanel
    }

    @Suppress("UNCHECKED_CAST")
    private fun preKeyEventHandlers(terminalPanel: JBTerminalPanel): List<Consumer<KeyEvent>> {
        val field = JBTerminalPanel::class.java.getDeclaredField("myPreKeyEventConsumers")
        field.isAccessible = true
        return (field.get(terminalPanel) as List<Consumer<KeyEvent>>).toList()
    }

    private fun ensureTerminalPanelCanBeDisposed(terminalPanel: JBTerminalPanel) {
        val field = terminalPanel.javaClass.superclass.getDeclaredField("myRepaintTimer")
        field.isAccessible = true
        if (field.get(terminalPanel) == null) {
            field.set(terminalPanel, javax.swing.Timer(0) { })
        }
    }

    private data class VirtualFileAndLine(
        val path: String,
        val lineNumber: Int?,
    )
}
