package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal class LocalFileReferenceMatcher(project: Project) {
    private val projectBaseDirectory = project.basePath?.let(LocalFileSystem.getInstance()::findFileByPath)

    fun findAll(line: String): List<ResolvedLocalFileReference> {
        if ('/' !in line) return emptyList()

        return localFileReferenceRegex.findAll(line).mapNotNull { match ->
            val group = match.groups[1] ?: return@mapNotNull null
            resolve(group.value, group.range.first)
        }.toList()
    }

    private fun resolve(rawTarget: String, sourceStartOffset: Int): ResolvedLocalFileReference? {
        var target = rawTarget
        while (true) {
            resolveExactTarget(target)?.let { (virtualFile, lineNumber) ->
                return ResolvedLocalFileReference(
                    virtualFile,
                    virtualFile.path,
                    lineNumber,
                    sourceStartOffset,
                    sourceStartOffset + target.length,
                )
            }
            if (target.lastOrNull() !in trailingReferencePunctuation) return null
            target = target.dropLast(1)
        }
    }

    private fun resolveExactTarget(target: String): Pair<VirtualFile, Int?>? {
        val (path, lineNumber) = parseLineSuffix(target)
        if (path.contains("://") || path.endsWith("/.")) return null

        val decodedPath = try {
            URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            path
        }
        val file = File(decodedPath)
        val virtualFile = if (file.isAbsolute) {
            LocalFileSystem.getInstance().findFileByIoFile(file)
        } else {
            projectBaseDirectory?.findFileByRelativePath(decodedPath)
        }
        return virtualFile?.let { it to lineNumber }
    }
}

internal data class ResolvedLocalFileReference(
    val virtualFile: VirtualFile,
    val path: String,
    val lineNumberOneBased: Int?,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int,
)

private fun parseLineSuffix(target: String): Pair<String, Int?> {
    val separator = target.lastIndexOf(':')
    val lineNumber = if (separator >= 0) target.substring(separator + 1).toIntOrNull() else null
    return if (lineNumber != null && lineNumber > 0) target.substring(0, separator) to lineNumber else target to null
}

private val localFileReferenceRegex =
    Regex("""(?<![\w@./:-])((?:\.{1,2}/|/|[\w.-]+/)[^\s\[\]()<>"|]+)(?![\w./-])""")

private val trailingReferencePunctuation = setOf('.', ',', ':', ';', '!', '?')
