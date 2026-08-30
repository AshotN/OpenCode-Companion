package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.api.session.FileDiff
import com.ashotn.opencode.relay.api.session.SessionDiffFile
import com.ashotn.opencode.relay.api.session.SessionDiffSnapshot
import com.ashotn.opencode.relay.ipc.SessionDiffStatus
import com.ashotn.opencode.relay.util.createPathIdentityMap
import com.ashotn.opencode.relay.util.toProjectRelativePath
import com.intellij.openapi.diagnostic.Logger

/**
 * Shared JVM-only harness for testing the diff pipeline without the IntelliJ platform.
 *
 * Usage:
 * ```
 * val h = DiffPipelineHarness()
 * h.disk[h.abs("note.md")] = "content\n"
 * h.applySessionDiff(listOf("note.md" to SessionDiffStatus.MODIFIED))
 * ```
 */
class DiffPipelineHarness(
    val projectBase: String = "/project",
    val sessionId: String = "ses_test",
    val generation: Long = 1L,
    hunkComputer: ((FileDiff, String) -> List<DiffHunk>)? = null,
) {
    data class ApplySessionDiffResult(
        val changedFiles: Set<String>,
    )

    /** Simulated disk: keyed by absolute path. */
    val disk = createPathIdentityMap<String>(projectBase)

    private val stateStore = StateStore()
    private val stateLock = Any()
    private val eventReducer = EventReducer()

    private val computer = SessionDiffApplyComputer(
        contentReader = { absPath -> disk[absPath] ?: "" },
        hunkComputer = hunkComputer ?: { fileDiff, sid ->
            // Default fake: emit one hunk whenever before != after
            if (fileDiff.before == fileDiff.after) emptyList()
            else listOf(
                DiffHunk(
                    fileDiff.file, 0,
                    if (fileDiff.before.isEmpty()) emptyList() else listOf(fileDiff.before),
                    if (fileDiff.after.isEmpty()) emptyList() else listOf(fileDiff.after),
                    sid,
                )
            )
        },
        log = NoOpLogger,
        tracer = NoOpDiffTracer,
    )

    /** Converts a relative path to an absolute path under [projectBase]. */
    fun abs(relPath: String): String = "$projectBase/$relPath"

    fun applySessionDiff(
        files: List<Pair<String, SessionDiffStatus>>,
    ): ApplySessionDiffResult? {
        val eventFiles = files.map { (relPath, status) ->
            SessionDiffFile(
                file = abs(relPath),
                before = "",
                after = "",
                additions = 0,
                deletions = 0,
                status = status,
            )
        }
        return applySessionDiffFiles(eventFiles)
    }

    fun applySessionDiffFiles(
        files: List<SessionDiffFile>,
    ): ApplySessionDiffResult? {
        return applySessionDiffFiles(files, fromHistory = false)
    }

    fun applyHistoricalSessionDiffFiles(
        files: List<SessionDiffFile>,
    ): ApplySessionDiffResult? {
        return applySessionDiffFiles(files, fromHistory = true)
    }

    private fun applySessionDiffFiles(
        files: List<SessionDiffFile>,
        fromHistory: Boolean,
    ): ApplySessionDiffResult? {
        val revision = stateStore.reserveRevisionForSessionDiffApply(
            stateLock = stateLock,
            sessionId = sessionId,
            expectedGeneration = generation,
            currentGeneration = { generation },
        )
        if (revision == null) return null

        val event = SessionDiffSnapshot(
            sessionId = sessionId,
            files = files.map { diffFile ->
                diffFile.copy(
                    file = diffFile.file.toProjectRelativePath(projectBase),
                )
            },
        )

        val computedState = computer.compute(
            projectBase = projectBase,
            event = event,
            fromHistory = fromHistory,
        )

        return stateStore.commitSessionDiffApply(
            stateLock = stateLock,
            sessionId = sessionId,
            revision = revision,
            fromHistory = fromHistory,
            computedState = computedState,
            nowMillis = 0L,
            expectedGeneration = generation,
            currentGeneration = { generation },
        )?.let { ApplySessionDiffResult(changedFiles = it.changedFiles) }
    }

    // --- Convenience accessors into stateStore ---

    fun hunkFiles(): Set<String> =
        stateStore.hunksBySessionAndFile[sessionId]?.keys?.toSet() ?: emptySet()

    fun liveHunkFiles(): Set<String> =
        stateStore.liveHunksBySessionAndFile[sessionId]?.keys?.toSet() ?: emptySet()

    fun addedFiles(): Set<String> =
        stateStore.addedBySession[sessionId] ?: emptySet()

    /** Mirrors the trackedFileCount logic in QueryService.listSessions. */
    fun trackedFileCount(): Int =
        ((stateStore.hunksBySessionAndFile[sessionId]?.keys ?: emptySet()) +
                (stateStore.addedBySession[sessionId] ?: emptySet()) +
                (stateStore.deletedBySession[sessionId] ?: emptySet())).size

    fun hunksFor(relPath: String): List<DiffHunk> =
        stateStore.hunksBySessionAndFile[sessionId]?.get(abs(relPath)) ?: emptyList()

    fun baseline(relPath: String): String? =
        stateStore.baselineBeforeBySessionAndFile[sessionId]?.get(abs(relPath))

    fun reconcileCurrentState() {
        val snapshot = stateStore.snapshotSessionReconcileState(
            stateLock = stateLock,
            sessionId = sessionId,
            expectedGeneration = generation,
            currentGeneration = { generation },
        ) ?: return
        val decision = eventReducer.reduceReconcile(
            currentHunks = snapshot.currentHunks,
            currentDeleted = snapshot.currentDeleted,
            currentAdded = snapshot.currentAdded,
            currentBaselines = snapshot.currentBaselines,
            readCurrentContent = { absPath -> disk[absPath] ?: "" },
        ) ?: return
        stateStore.commitReconcile(
            stateLock = stateLock,
            sessionId = sessionId,
            revision = stateStore.currentSessionRevision(stateLock, sessionId),
            updatedHunks = decision.updatedHunks,
            updatedDeleted = decision.updatedDeleted,
            updatedAdded = decision.updatedAdded,
            currentBaselines = snapshot.currentBaselines,
            expectedGeneration = generation,
            currentGeneration = { generation },
        )
    }
}

object NoOpLogger : Logger() {
    override fun isDebugEnabled() = false
    override fun debug(message: String, t: Throwable?) = Unit
    override fun debug(t: Throwable?) = Unit
    override fun debug(message: String, vararg details: Any?) = Unit
    override fun info(message: String) = Unit
    override fun info(message: String, t: Throwable?) = Unit
    override fun warn(message: String, t: Throwable?) = Unit
    override fun error(message: String, t: Throwable?, vararg details: String) = Unit
}
