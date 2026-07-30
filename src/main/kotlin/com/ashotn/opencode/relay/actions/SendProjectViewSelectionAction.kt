package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.ServerState
import com.ashotn.opencode.relay.tui.OpenCodeTuiClient
import com.ashotn.opencode.relay.util.applyStrings
import com.ashotn.opencode.relay.util.showNotification
import com.ashotn.opencode.relay.util.toProjectRelativePath
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile

/**
 * Project View action that appends references for all selected files and folders.
 */
class SendProjectViewSelectionAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val ready = project != null && OpenCodePlugin.getInstance(project).serverState == ServerState.READY
        val selected = resolveSelectedItems(e)
        val strings = stringsFor(selected)

        e.presentation.isVisible = selected.isNotEmpty()
        e.presentation.icon = iconFor(selected)
        e.applyStrings(strings, ready && selected.isNotEmpty())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = resolveSelectedItems(e)
        if (selected.isEmpty()) return

        val projectBase = project.basePath
        val ref = selected.joinToString(" ") { item ->
            val relativePath = projectBase?.let { item.path.toProjectRelativePath(it) } ?: item.path
            formatProjectViewReference(relativePath, item.isDirectory)
        } + " "

        OpenCodeTuiClient.getInstance(project).appendToTuiPrompt(ref) { success, error ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    project.showSendReferenceSuccessNotification(
                        titleFor(selected),
                        "${labelFor(selected)} appended to the active session's prompt.",
                    )
                } else {
                    project.showNotification(
                        "Failed to send selected items",
                        error ?: "Could not reach the OpenCode server.",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }

    private fun resolveSelectedItems(e: AnActionEvent): List<VirtualFile> {
        if (e.getData(CommonDataKeys.EDITOR) != null) return emptyList()
        return e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()
    }

    private fun stringsFor(selected: List<VirtualFile>): ActionStrings = when {
        selected.isNotEmpty() && selected.all { !it.isDirectory } -> ActionStrings.SEND_FILE
        selected.isNotEmpty() && selected.all { it.isDirectory } -> ActionStrings.SEND_FOLDER
        else -> ActionStrings.SEND_PROJECT_VIEW_SELECTION
    }

    private fun iconFor(selected: List<VirtualFile>) = when {
        selected.isNotEmpty() && selected.all { !it.isDirectory } -> AllIcons.FileTypes.Any_type
        selected.isNotEmpty() && selected.all { it.isDirectory } -> AllIcons.Nodes.Folder
        else -> AllIcons.Actions.Upload
    }

    private fun titleFor(selected: List<VirtualFile>): String = when {
        selected.all { !it.isDirectory } -> "File sent to OpenCode"
        selected.all { it.isDirectory } -> "Folder sent to OpenCode"
        else -> "Selected items sent to OpenCode"
    }

    private fun labelFor(selected: List<VirtualFile>): String = when {
        selected.size == 1 && !selected.single().isDirectory -> "File reference"
        selected.size == 1 && selected.single().isDirectory -> "Folder reference"
        selected.all { !it.isDirectory } -> "${selected.size} file references"
        selected.all { it.isDirectory } -> "${selected.size} folder references"
        else -> "${selected.size} selected item references"
    }
}

internal fun formatProjectViewReference(path: String, isDirectory: Boolean): String {
    if (!isDirectory) return "@$path"
    return "@${path.trimEnd('/').ifEmpty { "." }}/"
}
