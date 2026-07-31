package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies that [ClassicTuiPanel] kills the terminal process on [ClassicTuiPanel.stop]
 * and [ClassicTuiPanel.dispose].
 *
 * Uses [ClassicTuiPanel.processOverride] to inject a real `sleep 3600` OS process
 * without needing live terminal infrastructure. Assertions are purely OS-level PID checks.
 */
class ClassicTuiPanelProcessLeakTest : BasePlatformTestCase() {

    fun `test stop kills the terminal process`() {
        val process = spawnSleepProcess()
        val pid = process.pid()
        val panel = ClassicTuiPanel(project, testRootDisposable, processOverride = process)

        ApplicationManager.getApplication().invokeAndWait { panel.startIfNeeded() }
        assertTrue("pre: process must be alive (pid=$pid)", isAlive(pid))

        panel.stop()

        waitForDeath(pid)
        assertFalse("process must be dead after stop() (pid=$pid)", isAlive(pid))
    }

    fun `test dispose kills the terminal process`() {
        val process = spawnSleepProcess()
        val pid = process.pid()
        val panelDisposable = Disposer.newDisposable()
        val panel = ClassicTuiPanel(project, panelDisposable, processOverride = process)

        ApplicationManager.getApplication().invokeAndWait { panel.startIfNeeded() }
        assertTrue("pre: process must be alive (pid=$pid)", isAlive(pid))

        Disposer.dispose(panelDisposable) // triggers panel.dispose() → tearDown()

        waitForDeath(pid)
        assertFalse("process must be dead after dispose() (pid=$pid)", isAlive(pid))
    }

    private fun spawnSleepProcess(): Process =
        ProcessBuilder("sleep", "3600").redirectErrorStream(true).start()

    private fun isAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).orElse(null)?.isAlive ?: false

    private fun waitForDeath(pid: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isAlive(pid) && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }
}
