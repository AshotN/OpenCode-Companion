package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.api.session.Session
import com.ashotn.opencode.relay.api.session.SessionDiffFile
import com.ashotn.opencode.relay.api.session.SessionDiffSnapshot
import com.ashotn.opencode.relay.api.session.SessionTime
import com.ashotn.opencode.relay.ipc.SessionDiffStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the diff pipeline using [DiffPipelineHarness].
 *
 * Each test covers a distinct behaviour or regression. The harness simulates the full
 * pipeline (message-scoped diff → state commit) without requiring the IntelliJ
 * platform — disk content is a plain in-memory map and hunk computation is a fake.
 */
class SessionDiffPipelineTest {

    private val h = DiffPipelineHarness()

    // -------------------------------------------------------------------------
    // A file whose current disk content matches its effective baseline is not
    // a live AI change, even if the server reports it as ADDED. It must not be
    // tracked through the ADDED set alone.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to create a new empty file.
    //   2. The session in the tool window should show (0), not (1).
    // -------------------------------------------------------------------------
    @Test
    fun `baseline matching added file with no content should not count toward trackedFileCount`() {
        val file = "note.md"
        h.disk[h.abs(file)] = ""
        h.applySessionDiff(listOf(file to SessionDiffStatus.ADDED))

        assertTrue(h.addedFiles().isEmpty(), "baseline-matching file should not be in addedFiles")
        assertTrue(h.hunkFiles().isEmpty(), "no hunks for empty new file")
        assertEquals(0, h.trackedFileCount(), "baseline-matching file must not be tracked")
    }

    // -------------------------------------------------------------------------
    // A hunk for a newly created file must have empty removedLines. The file
    // did not exist before, so there is nothing to show as deleted. Any
    // non-empty removedLines would render as a red inlay block in the editor,
    // obscuring or replacing the green addition highlight.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to create a new file and write content into it.
    //   2. Open the file — only green highlighting should be visible, no red
    //      inlay block above the added lines.
    // -------------------------------------------------------------------------
    @Test
    fun `added file with content should produce only addedLines, no removedLines`() {
        val file = "note.md"

        // Turn 1: file created empty — no hunk expected
        h.disk[h.abs(file)] = ""
        h.applySessionDiff(listOf(file to SessionDiffStatus.ADDED))
        assertTrue(h.hunksFor(file).isEmpty(), "turn 1: no hunk for empty file")

        // Turn 2: content written — hunk must have no removedLines
        h.disk[h.abs(file)] = "1"
        h.applySessionDiff(listOf(file to SessionDiffStatus.ADDED))

        val hunks = h.hunksFor(file)
        assertEquals(1, hunks.size, "turn 2: expected one hunk")
        val hunk = hunks.single()
        assertTrue(
            hunk.removedLines.isEmpty(),
            "removedLines must be empty for a new file addition, got: ${hunk.removedLines}"
        )
        assertEquals(listOf("1"), hunk.addedLines, "addedLines should contain the new content")
    }

    // -------------------------------------------------------------------------
    // When the AI intentionally empties a file (replaces all content with an
    // empty string), the "After AI" panel in the diff viewer must show an empty
    // file. If a previous turn wrote content to the same file, the diff viewer
    // must not show that earlier content in the "After AI" panel — the user
    // would be looking at the wrong thing and could make incorrect decisions
    // about accepting or reverting the change.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to add a joke to note.md.
    //   2. In a second turn, ask the AI to replace note.md with an empty string.
    //   3. Double-click note.md in the diff viewer.
    //   4. The "After AI" panel must be empty.
    //      If it still shows the joke from turn 1, this invariant is violated.
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // A file the AI modified must still appear in the session's file count even
    // when the diff computation throws an unexpected error. If hunk computation
    // fails internally, the file silently disappears from tracking — the session
    // panel shows (0) and the file is absent from the list, even though the AI
    // clearly changed it. The user has no indication the AI touched the file at all.
    //
    // MANUAL VERIFICATION:
    //   There is no reliable way to force this from the UI — it depends on an
    //   internal exception in hunk computation (e.g. from ComparisonManager).
    //   Check the IDE log for "failed to compute hunks" warnings. If that warning
    //   appears for a file but the session panel shows (0) and the file is absent
    //   from the file list, this invariant is violated.
    // -------------------------------------------------------------------------
    @Test
    fun `file must still appear in session file count when hunk computation produces no hunks`() {
        val file = "note.md"

        // Use a harness with a hunk computer that always returns empty — regardless
        // of why (failed diff, ComparisonManager exception, algorithmic edge case).
        // The invariant is about the outcome (zero hunks) not the mechanism.
        val zeroHunkHarness = DiffPipelineHarness(
            hunkComputer = { _, _ -> emptyList() }
        )

        zeroHunkHarness.disk[zeroHunkHarness.abs(file)] = "line1\n"
        zeroHunkHarness.applySessionDiff(
            files = listOf(file to SessionDiffStatus.MODIFIED),
        )

        // The file was reported as MODIFIED — it must still be counted.
        assertEquals(
            1,
            zeroHunkHarness.trackedFileCount(),
            "a MODIFIED file must still be counted when hunk computation produces no hunks, got: ${zeroHunkHarness.trackedFileCount()}",
        )
    }

    // -------------------------------------------------------------------------
    // Once the user manually reverts a file back to its original content, the
    // AI change is resolved. The file should be removed from the session file
    // list and inline hunk state instead of remaining as an empty hunk entry.
    //
    // MANUAL VERIFICATION:
    //   1. Create an empty note.md.
    //   2. Ask the AI to write a joke into note.md.
    //      note.md appears in the session's file list.
    //   3. Manually delete the AI's content so note.md is empty again.
    //   4. Restart the IDE.
    //      note.md must not appear in the session's file list after the reset.
    //      If it remains visible, this invariant is violated.
    // -------------------------------------------------------------------------
    @Test
    fun `file is removed from session list after user reverts it to original content`() {
        val file = "note.md"
        val original = ""   // file was empty before the AI touched it
        val aiContent = "Why do Java developers wear glasses? Because they can't C#.\n"

        // AI writes content to the previously empty file
        h.disk[h.abs(file)] = aiContent
        h.applySessionDiff(
            files = listOf(file to SessionDiffStatus.ADDED),
        )
        assertEquals(1, h.trackedFileCount(), "file should be tracked after AI wrote to it")
        assertEquals(setOf(h.abs(file)), h.addedFiles(), "file should be in addedFiles after AI wrote it")

        // User reverts the file back to original — disk now matches baseline
        h.disk[h.abs(file)] = original

        // Reconciler runs (simulated by running it directly)
        h.reconcileCurrentState()

        // The file must be removed from all tracked state.
        assertEquals(
            0,
            h.trackedFileCount(),
            "file must be removed from session list after user reverts it, got: ${h.trackedFileCount()}",
        )
        assertTrue(h.hunkFiles().isEmpty(), "reverted file should be removed from hunkFiles")
        assertTrue(h.liveHunkFiles().isEmpty(), "reverted file should be removed from liveHunkFiles")
        assertTrue(h.addedFiles().isEmpty(), "reverted file should be removed from addedFiles")
    }

    // -------------------------------------------------------------------------
    // If the AI adds content in one turn and removes it in a later AI turn, the
    // later live diff is computed against the start of that AI turn. It must
    // still show a red deletion even though the disk now matches the original
    // session baseline.
    // -------------------------------------------------------------------------
    @Test
    fun `later AI deletion is diffed against turn baseline not original baseline`() {
        val file = "note.md"
        val aiContent = "hello\n"

        h.disk[h.abs(file)] = aiContent
        h.applySessionDiffFiles(
            listOf(
                SessionDiffFile(
                    file = h.abs(file),
                    before = "",
                    after = aiContent,
                    additions = 1,
                    deletions = 0,
                    status = SessionDiffStatus.MODIFIED,
                )
            )
        )

        h.disk[h.abs(file)] = ""
        h.applySessionDiffFiles(
            listOf(
                SessionDiffFile(
                    file = h.abs(file),
                    before = aiContent,
                    after = "",
                    additions = 0,
                    deletions = 1,
                    status = SessionDiffStatus.MODIFIED,
                )
            )
        )

        val hunks = h.hunksFor(file)
        assertEquals(1, hunks.size, "later AI deletion should produce a deletion hunk")
        assertEquals(listOf(aiContent), hunks.single().removedLines)
        assertTrue(hunks.single().addedLines.isEmpty(), "later AI deletion should not produce added lines")
        assertEquals(setOf(h.abs(file)), h.liveHunkFiles(), "later AI deletion should remain live")
    }

    // -------------------------------------------------------------------------
    // Once a file is visible in a session's file list, a later AI turn that
    // reports the same file must not make it disappear just because the file on
    // disk already matches the latest known AI content. The user still expects
    // the file to remain listed for review and for the 3-panel diff viewer.
    //
    // MANUAL VERIFICATION:
    //   1. Select a session where plan.md appears in the Files list.
    //   2. Ask the AI to make a small edit to plan.md.
    //   3. plan.md must remain visible in the Files list after the edit finishes.
    //      If it disappears until plugin state is reset, this invariant is violated.
    // -------------------------------------------------------------------------
    @Test
    fun `file reported by live diff must remain in session list when content matches latest AI state`() {
        val file = "plan.md"
        val original = "# Plan\n"
        val aiContent = "# Plan\n\nTrace reproduction marker: test.\n"

        // Initial AI turn makes the file visible in the session list.
        h.disk[h.abs(file)] = aiContent
        h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))
        assertEquals(1, h.trackedFileCount(), "file should be tracked after the first AI edit")

        // A later live message diff reports the same file, but local disk already
        // matches the latest AI content known to the plugin.
        h.applySessionDiffFiles(
            listOf(
                SessionDiffFile(
                    file = h.abs(file),
                    before = original,
                    after = aiContent,
                    additions = 1,
                    deletions = 0,
                    status = SessionDiffStatus.MODIFIED,
                )
            ),
        )

        assertEquals(
            1,
            h.trackedFileCount(),
            "file reported by a live diff must remain in the session list, got: ${h.trackedFileCount()}",
        )
    }

    // -------------------------------------------------------------------------
    // After an IDE restart, the plugin reloads session history from the server
    // but must not restore inline green/red highlights. Inline highlights are a
    // runtime signal — they only appear after a new turn runs in the current
    // IDE session. Reloading history is for file counts and diff preview only.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to modify a file. Green highlights appear in the editor.
    //   2. Restart the IDE.
    //   3. Open the same file — no green highlights should be visible.
    //      The session should still appear in the session panel with a file count,
    //      but the editor must be clean until a new turn runs.
    // -------------------------------------------------------------------------
    @Test
    fun `historical reload after restart does not restore inline highlights`() {
        val file = "note.md"

        // Simulate a live turn that produced inline highlights before restart.
        h.disk[h.abs(file)] = "line1\n"
        h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))
        assertEquals(setOf(h.abs(file)), h.liveHunkFiles(), "pre-restart: live highlights present")

        // Simulate IDE restart: create fresh state store and replay the session
        // diff as a historical load (fromHistory=true), exactly as the plugin does
        // when it calls /session/{id}/diff on startup.
        val freshStore = StateStore()
        val freshLock = Any()
        val revision = freshStore.reserveRevisionForSessionDiffApply(
            stateLock = freshLock,
            sessionId = h.sessionId,
            expectedGeneration = h.generation,
            currentGeneration = { h.generation },
        )!!
        val computer = SessionDiffApplyComputer(
            contentReader = { absPath -> h.disk[absPath] ?: "" },
            hunkComputer = { fileDiff, sid ->
                if (fileDiff.before == fileDiff.after) emptyList()
                else listOf(DiffHunk(fileDiff.file, 0, emptyList(), listOf(fileDiff.after), sid))
            },
            log = NoOpLogger,
            tracer = NoOpDiffTracer,
        )
        val event = SessionDiffSnapshot(
            sessionId = h.sessionId,
            files = listOf(
                SessionDiffFile(
                    file = h.abs(file),
                    before = "",
                    after = "line1\n",
                    additions = 1,
                    deletions = 0,
                    status = SessionDiffStatus.MODIFIED,
                )
            ),
        )
        val computedState = computer.compute(
            projectBase = h.projectBase,
            event = event,
            fromHistory = true,
        )
        freshStore.commitSessionDiffApply(
            stateLock = freshLock,
            sessionId = h.sessionId,
            revision = revision,
            fromHistory = true,
            computedState = computedState,
            nowMillis = 0L,
            expectedGeneration = h.generation,
            currentGeneration = { h.generation },
        )

        // After historical reload: file count is tracked but inline highlights are gone.
        val liveAfterReload = freshStore.liveHunksBySessionAndFile[h.sessionId]?.keys ?: emptySet()
        val cumAfterReload = freshStore.hunksBySessionAndFile[h.sessionId]?.keys ?: emptySet()

        assertTrue(
            liveAfterReload.isEmpty(),
            "post-restart historical reload must not restore inline highlights, got liveHunkFiles: $liveAfterReload"
        )
        assertEquals(
            setOf(h.abs(file)), cumAfterReload,
            "cumulative hunk state must be populated for file count / diff preview"
        )
    }

    // -------------------------------------------------------------------------
    // After a turn that only modifies one file, only that file should show green
    // or red inline highlights in the editor. Files touched by previous turns
    // must not continue to show highlights just because they were changed
    // earlier in the session.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to write content to note1.md in one turn.
    //   2. In a second turn, ask the AI to write content to note2.md.
    //   3. Open note1.md — it must NOT have any green highlights.
    //      Only note2.md should show green highlights after turn 2.
    // -------------------------------------------------------------------------
    @Test
    fun `inline highlights only show for the most recent turn's files`() {
        val file1 = "note1.md"
        val file2 = "note2.md"
        val file3 = "note3.md"

        // Turn 1: touch note1.md
        h.disk[h.abs(file1)] = "line1\n"
        h.applySessionDiff(listOf(file1 to SessionDiffStatus.MODIFIED))
        assertEquals(setOf(h.abs(file1)), h.liveHunkFiles(), "after turn 1: only note1.md should be live")

        // Turn 2: message diff reports note2.md only.
        h.disk[h.abs(file2)] = "line2\n"
        h.applySessionDiff(
            listOf(
                file2 to SessionDiffStatus.MODIFIED,
            )
        )
        assertEquals(
            setOf(h.abs(file2)),
            h.liveHunkFiles(),
            "after turn 2: only note2.md should be live — note1.md was not touched this turn",
        )
        assertEquals(2, h.trackedFileCount(), "after turn 2: cumulative file state should retain note1.md")

        // Turn 3: message diff reports note3.md only.
        h.disk[h.abs(file3)] = "line3\n"
        h.applySessionDiff(
            listOf(
                file3 to SessionDiffStatus.ADDED,
            )
        )
        assertEquals(
            setOf(h.abs(file3)),
            h.liveHunkFiles(),
            "after turn 3: only note3.md should be live — note1.md and note2.md were not touched this turn",
        )
        assertEquals(3, h.trackedFileCount(), "after turn 3: cumulative file state should retain older files")
    }

    // -------------------------------------------------------------------------
    // A historical snapshot may load after a live turn, for example after the live
    // apply triggers a hierarchy refresh. That snapshot must update cumulative
    // file state without clearing the current turn's inline highlights.
    // -------------------------------------------------------------------------
    @Test
    fun `historical diff apply preserves existing live hunks`() {
        val file = "note.md"
        h.disk[h.abs(file)] = "line1\n"
        h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))
        assertEquals(setOf(h.abs(file)), h.liveHunkFiles(), "live turn should create inline hunks")

        h.applyHistoricalSessionDiffFiles(
            listOf(
                SessionDiffFile(
                    file = h.abs(file),
                    before = "",
                    after = "line1\n",
                    additions = 1,
                    deletions = 0,
                    status = SessionDiffStatus.MODIFIED,
                )
            )
        )

        assertEquals(
            setOf(h.abs(file)),
            h.liveHunkFiles(),
            "historical load after a live turn must not clear current inline hunks",
        )
    }

    // -------------------------------------------------------------------------
    // A sub-agent live diff can arrive before the session hierarchy refresh that
    // tells the plugin child.parentID == root. While root is selected, that diff
    // is not part of the strict root family yet. Once hierarchy metadata arrives,
    // the same live files and inline hunks must become visible without requiring
    // another child diff event.
    // -------------------------------------------------------------------------
    @Test
    fun `root selected child live diff becomes visible after hierarchy parent metadata arrives`() {
        val projectBase = "/project"
        val rootSessionId = "ses_root"
        val childSessionId = "ses_child"
        val file = "live-subagents/alpha.txt"
        val absFile = "$projectBase/$file"
        val content = "alpha from sub-agent"
        val harness = CoreDiffStateHarness(projectBase)

        harness.applyLiveMessageDiff(
            sessionId = childSessionId,
            diff = SessionDiffSnapshot(
                sessionId = childSessionId,
                files = listOf(
                    SessionDiffFile(
                        file = file,
                        before = "",
                        after = content,
                        additions = 1,
                        deletions = 0,
                        status = SessionDiffStatus.MODIFIED,
                    )
                ),
            ),
            readContent = { path -> if (path == absFile) content else "" },
        )

        val beforeHierarchy = harness.selectRootAndVisibleState(
            rootSessionId = rootSessionId,
            sessions = listOf(session(rootSessionId)),
        )
        assertTrue(
            absFile !in beforeHierarchy.liveVisibleFiles,
            "child live file should not be visible before hierarchy provides parentID",
        )
        assertTrue(
            beforeHierarchy.liveHunksByFile[absFile].orEmpty().isEmpty(),
            "child live hunks should not be visible before hierarchy provides parentID",
        )

        val afterHierarchy = harness.selectRootAndVisibleState(
            rootSessionId = rootSessionId,
            sessions = listOf(
                session(rootSessionId),
                session(childSessionId, parentId = rootSessionId),
            ),
        )

        assertEquals(
            setOf(absFile),
            afterHierarchy.visibleFiles.intersect(setOf(absFile)),
            "root file list should include child diff after hierarchy provides parentID",
        )
        assertEquals(
            setOf(absFile),
            afterHierarchy.liveVisibleFiles.intersect(setOf(absFile)),
            "root live-visible files should include child diff after hierarchy provides parentID",
        )
        val liveHunk = afterHierarchy.liveHunksByFile[absFile]?.singleOrNull()
        assertEquals(childSessionId, liveHunk?.sessionId)
        assertEquals(listOf(content), liveHunk?.addedLines)
    }

    // -------------------------------------------------------------------------
    // Each turn's diff must be computed relative to the server-provided file
    // content at the start of that turn, not the original file. If it does not,
    // lines changed in earlier turns will appear highlighted in later turns.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to append a line to a file, then append another in a second turn.
    //   2. After turn 2, only the line from turn 2 should be highlighted green —
    //      the line from turn 1 should no longer be highlighted.
    // -------------------------------------------------------------------------
    @Test
    fun `multi-turn baseline advances correctly`() {
        val file = "note.md"

        h.disk[h.abs(file)] = "line1\n"
        h.applySessionDiffFiles(
            listOf(
                SessionDiffFile(
                    file = h.abs(file),
                    before = "",
                    after = "line1\n",
                    additions = 1,
                    deletions = 0,
                    status = SessionDiffStatus.MODIFIED,
                )
            )
        )
        assertEquals("", h.baseline(file), "turn 1 baseline should be empty (file was new)")

        h.disk[h.abs(file)] = "line1\nline2\n"
        h.applySessionDiffFiles(
            listOf(
                SessionDiffFile(
                    file = h.abs(file),
                    before = "line1\n",
                    after = "line1\nline2\n",
                    additions = 1,
                    deletions = 0,
                    status = SessionDiffStatus.MODIFIED,
                )
            )
        )
        assertEquals("line1\n", h.baseline(file), "turn 2 baseline should be turn 1's final content")
    }

    private fun session(id: String, parentId: String? = null): Session = Session(
        id = id,
        projectID = null,
        directory = null,
        parentID = parentId,
        title = id,
        version = null,
        time = SessionTime(created = 0L, updated = 0L, compacting = null),
        summary = null,
        share = null,
    )
}
