package com.ashotn.opencode.relay.terminal

import com.intellij.execution.filters.AbstractFileHyperlinkFilter
import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.FileHyperlinkRawData
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.io.File

/** Makes OpenCode's local file references and structured @ mentions navigable. */
class OpenCodeFileMentionFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> =
        arrayOf(createOpenCodeFileMentionFilter(project))
}

internal fun createOpenCodeFileMentionFilter(project: Project): Filter =
    OpenCodeFileMentionFilter(project)

private class OpenCodeFileMentionFilter(project: Project) :
    AbstractFileHyperlinkFilter(project, project.basePath),
    DumbAware {
    private val projectBasePath = project.basePath
    private val localFileReferenceMatcher = LocalFileReferenceMatcher(project)

    override fun parse(line: String): List<FileHyperlinkRawData> {
        if (line.length > MAX_LINE_LENGTH) return emptyList()

        val links = mutableListOf<FileHyperlinkRawData>()
        if ('@' in line) {
            fileMentionRegex.findAll(line).forEach { match ->
                val (path, lineText) = match.destructured
                val lineNumber = lineText.toIntOrNull() ?: return@forEach
                links += createLinkData(path, lineNumber - 1, match.range)
            }
            directoryMentionRegex.findAll(line).forEach { match ->
                val (path) = match.destructured
                links += createLinkData(path, -1, match.range)
            }
        }

        localFileReferenceMatcher.findAll(line).forEach { target ->
            links += FileHyperlinkRawData(
                target.path,
                target.lineNumberOneBased?.minus(1) ?: -1,
                -1,
                target.sourceStartOffset,
                target.sourceEndOffset,
            )
        }
        return links
            .distinctBy { it.hyperlinkStartInd to it.hyperlinkEndInd }
            .sortedBy { it.hyperlinkStartInd }
    }

    private fun createLinkData(path: String, lineNumber: Int, range: IntRange): FileHyperlinkRawData {
        val resolvedPath = File(path).let { file ->
            if (file.isAbsolute || projectBasePath == null) path else File(projectBasePath, path).path
        }
        return FileHyperlinkRawData(
            resolvedPath,
            lineNumber,
            -1,
            range.first,
            range.last + 1,
        )
    }
}

private val fileMentionRegex = Regex(
    """(?<!\S)@([^#\s]+)#L([1-9]\d*)(?:-L?[1-9]\d*)?(?![\w-])"""
)

private val directoryMentionRegex = Regex(
    """(?<!\S)@([^#\s]+/)(?![\w./#-])"""
)

// Matches PatternBasedFileHyperlinkRawDataFinder's default safeguard for console lines.
private const val MAX_LINE_LENGTH = 10_000
