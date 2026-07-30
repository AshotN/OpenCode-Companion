package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.JBTerminalPanel
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import com.jediterm.terminal.model.hyperlinks.TextProcessing
import java.io.File
import java.net.URI
import java.net.URISyntaxException

// Classic's embedded JediTerm does not receive Reworked Terminal's native file filters.
private val localFileReferenceRegex = Regex("""(?<![\w./:-])((?:\.{1,2}/|/|[\w.-]+/)[^\s\[\]()<>\"]+)(?![\w./-])""")
private val trailingReferencePunctuation = setOf('.', ',', ':', ';')
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
    private val projectBaseDirectory = project.basePath?.let(LocalFileSystem.getInstance()::findFileByPath)

    override fun apply(line: String): LinkResult? {
        if ('/' !in line) return null

        val links = localFileReferenceRegex.findAll(line).mapNotNull { match ->
            val target = match.groupValues[1].trimEnd(*trailingReferencePunctuation.toCharArray())
            val resolvedTarget = resolveTarget(target) ?: return@mapNotNull null
            LinkResultItem(
                match.range.first,
                match.range.first + target.length,
                LinkInfo { navigate(resolvedTarget.virtualFile, resolvedTarget.lineNumber) },
            )
        }.toList()
        return links.takeIf { it.isNotEmpty() }?.let(::LinkResult)
    }

    private fun resolveTarget(target: String): ResolvedTerminalLink? {
        val (path, lineNumber) = parseLineSuffix(target)
        if (path.contains("://")) return null

        val decodedPath = try {
            URI(null, null, path, null).path ?: path
        } catch (_: URISyntaxException) {
            path
        }
        val file = File(decodedPath)
        val virtualFile = if (file.isAbsolute) {
            LocalFileSystem.getInstance().findFileByIoFile(file)
        } else {
            projectBaseDirectory?.findFileByRelativePath(decodedPath)
        }
        return virtualFile
            ?.let {
                ResolvedTerminalLink(
                    it,
                    lineNumber,
                )
            }
    }
}

private fun parseLineSuffix(target: String): Pair<String, Int?> {
    val separator = target.lastIndexOf(':')
    val lineNumber = if (separator >= 0) target.substring(separator + 1).toIntOrNull() else null
    return if (lineNumber != null && lineNumber > 0) target.substring(0, separator) to lineNumber else target to null
}

private data class ResolvedTerminalLink(
    val virtualFile: VirtualFile,
    val lineNumber: Int?,
)
