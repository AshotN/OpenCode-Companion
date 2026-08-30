package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.JBTerminalPanel
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import com.jediterm.terminal.model.hyperlinks.TextProcessing

// Classic's embedded JediTerm does not receive Reworked Terminal's native file filters.
private val logger = Logger.getInstance(ClassicTerminalHyperlinkFilter::class.java)

internal fun installClassicTerminalHyperlinkFilter(project: Project, panel: JBTerminalPanel) {
    val textProcessing = try {
        panel.terminalTextProcessing()
    } catch (error: ReflectiveOperationException) {
        logger.warn("Failed to install Classic terminal hyperlink filter", error)
        return
    }
    if (textProcessing == null) {
        logger.warn("Skipping Classic terminal hyperlink filter because JediTerm text processing is unavailable")
        return
    }
    textProcessing.addHyperlinkFilter(ClassicTerminalHyperlinkFilter(project))
}

private fun JBTerminalPanel.terminalTextProcessing(): TextProcessing? {
    val method = terminalTextBuffer.javaClass.methods.firstOrNull { method ->
        method.parameterCount == 0 && TextProcessing::class.java.isAssignableFrom(method.returnType)
    } ?: return null
    method.isAccessible = true
    return method.invoke(terminalTextBuffer) as? TextProcessing
}

internal class ClassicTerminalHyperlinkFilter(
    private val project: Project,
    private val navigate: (VirtualFile, Int?) -> Unit = { virtualFile, lineNumber ->
        ApplicationManager.getApplication().invokeLater {
            if (lineNumber != null) {
                OpenFileDescriptor(project, virtualFile, lineNumber - 1, 0).navigate(true)
            } else {
                OpenFileDescriptor(project, virtualFile).navigate(true)
            }
        }
    },
) : HyperlinkFilter {
    private val localFileReferenceMatcher = LocalFileReferenceMatcher(project)

    override fun apply(line: String): LinkResult? {
        val links = localFileReferenceMatcher.findAll(line).map { target ->
            LinkResultItem(
                target.sourceStartOffset,
                target.sourceEndOffset,
                LinkInfo { navigate(target.virtualFile, target.lineNumberOneBased) },
            )
        }
        return links.takeIf { it.isNotEmpty() }?.let(::LinkResult)
    }
}
