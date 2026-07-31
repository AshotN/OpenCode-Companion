package com.ashotn.opencode.relay.terminal

import com.intellij.ide.DataManager
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.lang.reflect.Proxy
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

    fun `test embedded terminal consumes only ctrl z key presses`() {
        val source = JPanel()
        val cases = listOf(
            KeyEvent.KEY_PRESSED to InputEvent.CTRL_DOWN_MASK,
            KeyEvent.KEY_RELEASED to InputEvent.CTRL_DOWN_MASK,
            KeyEvent.KEY_PRESSED to (InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
        )

        cases.forEachIndexed { index, (eventId, modifiers) ->
            val event = KeyEvent(source, eventId, 0, modifiers, KeyEvent.VK_Z, 'Z')
            assertEquals(index == 0, consumeEmbeddedTerminalControlKey(event))
        }

        val escape = KeyEvent(source, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
        assertFalse(consumeEmbeddedTerminalControlKey(escape))
    }

    fun `test local file reference matcher contract`() {
        data class Case(
            val line: String,
            val target: String,
            val file: File,
            val lineNumber: Int?,
        )

        val readme = createProjectFile("README.md", "one\ntwo\nthree\n")
        val nested = createProjectFile("src/main/Example.kt", (1..50).joinToString("\n") { "line $it" })
        val encoded = createProjectFile("encoded name.md", "content\n")
        val punctuated = createProjectFile("literal!", "content\n")
        val directory = File(project.basePath, "local-directory").apply { mkdirs() }
        listOf(readme, nested, encoded, punctuated, directory).forEach { file ->
            assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file))
        }

        val targetWithLine = "./README.md:2"
        val cases = listOf(
            Case("See ./README.md", "./README.md", readme, null),
            Case("See ${readme.path}", readme.path, readme, null),
            Case("See src/main/Example.kt:42;", "src/main/Example.kt:42", nested, 42),
            Case("See ./encoded%20name.md", "./encoded%20name.md", encoded, null),
            Case("See ./literal!?", "./literal!", punctuated, null),
            Case("Open ./local-directory/.", "./local-directory/", directory, null),
        ) + listOf('.', ',', ':', ';', '!', '?').map { punctuation ->
            Case("See $targetWithLine$punctuation", targetWithLine, readme, 2)
        }

        val matcher = LocalFileReferenceMatcher(project)
        cases.forEach { case ->
            val result = matcher.findAll(case.line).single()
            assertEquals(case.file.path, result.virtualFile.path)
            assertEquals(case.lineNumber, result.lineNumberOneBased)
            assertEquals(case.line.indexOf(case.target), result.sourceStartOffset)
            assertEquals(case.line.indexOf(case.target) + case.target.length, result.sourceEndOffset)
        }

        listOf(
            "See ./missing.md:2",
            "See ./README.md:0",
            "See ${readme.toURI()}",
            "See @src/main/Example.kt#L2",
        ).forEach { line -> assertTrue(matcher.findAll(line).isEmpty()) }
    }

    fun `test Classic adapter maps local file reference to JediTerm link`() {
        val file = createProjectFile("classic.kt", "one\ntwo\n")
        assertNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file))
        val target = "./classic.kt:2"
        val line = "See $target."
        var navigatedFile: VirtualFileAndLine? = null

        val item = ClassicTerminalHyperlinkFilter(project) { virtualFile, lineNumber ->
            navigatedFile = VirtualFileAndLine(virtualFile.path, lineNumber)
        }.apply(line)!!.items.single()

        assertEquals(line.indexOf(target), item.startOffset)
        assertEquals(line.indexOf(target) + target.length, item.endOffset)
        item.linkInfo.navigate()
        assertEquals(VirtualFileAndLine(file.path, 2), navigatedFile)
    }

    fun `test Reworked adapter maps local file reference to platform hyperlink`() {
        val file = createProjectFile("reworked.kt", "one\ntwo\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        assertNotNull(virtualFile)
        val target = "./reworked.kt:2"
        val line = "| Source | $target |"

        val item = createOpenCodeFileMentionFilter(project).applyFilter(line, line.length)!!.resultItems.single()

        assertEquals(line.indexOf(target), item.highlightStartOffset)
        assertEquals(line.indexOf(target) + target.length, item.highlightEndOffset)
        val hyperlink = item.hyperlinkInfo as OpenFileHyperlinkInfo
        assertEquals(virtualFile!!.path, hyperlink.virtualFile?.path)
        hyperlink.navigate(project)
        assertEquals(1, FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)
    }

    fun `test OpenCode file mention filter resolves line ranges from the project root`() {
        val firstFile = createProjectFile("note.md", "one\ntwo\nthree\n")
        val secondFile = createProjectFile("src/File.kt", "one\ntwo\n")
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
        val file = createProjectFile("note.md", "one\ntwo\nthree\n")
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

        assertEquals(1, result!!.resultItems.size)
        val item = result.resultItems[0]
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

    private fun createProjectFile(path: String, content: String): File =
        File(project.basePath, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }

    private data class VirtualFileAndLine(
        val path: String,
        val lineNumber: Int?,
    )
}
