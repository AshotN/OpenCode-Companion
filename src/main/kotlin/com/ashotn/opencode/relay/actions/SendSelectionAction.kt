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

/**
 * Editor action that appends an @path#Lstart or @path#Lstart-end file reference to the TUI's prompt
 * input buffer via POST /tui/append-prompt.
 *
 * Sends a reference only — no file content is embedded in the prompt. OpenCode resolves
 * the file and line range itself when the user submits.
 *
 * The OpenCode server provides no session-scoping for this endpoint — the reference lands
 * in whichever session the TUI currently has open. The plugin has no control over that.
 *
 * Does NOT create a message or trigger a model response; the user still submits manually.
 */
class SendSelectionAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val ready = project != null && OpenCodePlugin.getInstance(project).serverState == ServerState.READY
        val hasEditor = editor != null

        // Hide and disable entirely outside editors so Project View actions can use the same shortcut.
        e.presentation.isVisible = hasEditor
        e.presentation.icon = AllIcons.Actions.Upload
        e.applyStrings(ActionStrings.SEND_SELECTION, ready && hasEditor)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val document = editor.document
        val selectionModel = editor.selectionModel

        val startLine: Int
        val endLine: Int
        if (selectionModel.hasSelection()) {
            startLine = document.getLineNumber(selectionModel.selectionStart) + 1
            // Use selectionEnd - 1 so a cursor parked at column 0 of the next line doesn't
            // inflate the range by one (e.g. selecting lines 1–3 ending at line 4 offset 0).
            endLine =
                document.getLineNumber((selectionModel.selectionEnd - 1).coerceAtLeast(selectionModel.selectionStart)) + 1
        } else {
            // No selection — fall back to the line the caret is on
            val caretLine = document.getLineNumber(editor.caretModel.offset) + 1
            startLine = caretLine
            endLine = caretLine
        }

        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
        val relativePath = virtualFile?.path?.let { path ->
            project.basePath?.let { base -> path.toProjectRelativePath(base) } ?: path
        } ?: return

        val lineAnchor = if (startLine == endLine) "#L$startLine" else "#L$startLine-$endLine"
        val ref = "@$relativePath$lineAnchor "

        OpenCodeTuiClient.getInstance(project).appendToTuiPrompt(ref) { success, error ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    project.showSendReferenceSuccessNotification(
                        "Selection sent to OpenCode",
                        "File reference appended to the active session's prompt.",
                    )
                } else {
                    project.showNotification(
                        "Failed to send selection",
                        error ?: "Could not reach the OpenCode server.",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }
}
