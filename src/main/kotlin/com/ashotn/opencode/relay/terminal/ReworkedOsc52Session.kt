@file:Suppress("UnstableApiUsage")

package com.ashotn.opencode.relay.terminal

import com.intellij.codeWithMe.ClientId
import com.intellij.codeWithMe.ClientIdContextElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns a prestarted Reworked Terminal session whose observable connector exposes raw output.
 * JetBrains does not provide this hook through TerminalView, so access is isolated and fail-open.
 */
internal class ReworkedOsc52Session private constructor(
    private val scope: CoroutineScope,
    private val listenerDisposable: Disposable,
) : Disposable {
    private val disposed = AtomicBoolean()

    fun invokeOnTermination(listener: () -> Unit) {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            if (!disposed.get()) listener()
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        try {
            Disposer.dispose(listenerDisposable)
        } finally {
            scope.cancel()
        }
    }

    companion object {
        private const val SESSIONS_MANAGER_CLASS = "com.intellij.terminal.frontend.session.TerminalSessionsManager"
        private const val CONNECTOR_LISTENER_CLASS = "com.intellij.terminal.frontend.session.TtyConnectorListener"
        private val logger = Logger.getInstance(ReworkedOsc52Session::class.java)

        fun tryStart(
            project: Project,
            startupOptions: ShellStartupOptions,
            tabBuilder: Any,
            clipboardWriter: (String) -> Unit,
        ): ReworkedOsc52Session? {
            val listenerDisposable = Disposer.newDisposable("OpenCode reworked terminal OSC52 listener")
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + ClientIdContextElement(ClientId.localId)
            )
            val session = ReworkedOsc52Session(scope, listenerDisposable)

            return try {
                val classLoader = ReworkedOsc52Session::class.java.classLoader
                val managerClass = Class.forName(SESSIONS_MANAGER_CLASS, true, classLoader)
                val listenerClass = Class.forName(CONNECTOR_LISTENER_CLASS, true, classLoader)
                val manager = managerClass
                    .getMethod("getInstance", Project::class.java)
                    .invoke(null, project)
                val startResult = managerClass
                    .getMethod("startSession", ShellStartupOptions::class.java, CoroutineScope::class.java)
                    .invoke(manager, startupOptions, scope)
                val sessionId = startResult.javaClass.getMethod("getSessionId").invoke(startResult)
                val connector = startResult.javaClass.getMethod("getTtyConnector").invoke(startResult)
                val handler = Osc52ClipboardHandler(clipboardWriter)
                val listener = Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                ) { proxy, method, args ->
                    when (method.name) {
                        "charsRead" -> {
                            val chars = args?.get(0) as CharArray
                            val offset = args[1] as Int
                            val length = args[2] as Int
                            handler.process(String(chars, offset, length))
                            null
                        }

                        "equals" -> proxy === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "OpenCode OSC52 terminal connector listener"
                        else -> null
                    }
                }
                connector.javaClass
                    .getMethod("addListener", Disposable::class.java, listenerClass)
                    .invoke(connector, listenerDisposable, listener)

                val sessionIdMethod = tabBuilder.javaClass.declaredMethods.firstOrNull { method ->
                    method.name == "sessionId" && method.parameterCount == 1
                } ?: error("Reworked terminal tab builder does not expose sessionId")
                sessionIdMethod.isAccessible = true
                sessionIdMethod.invoke(tabBuilder, sessionId)
                session
            } catch (failure: Throwable) {
                val cause = (failure as? InvocationTargetException)?.targetException ?: failure
                try {
                    session.dispose()
                } catch (cleanupFailure: Throwable) {
                    cause.addSuppressed(cleanupFailure)
                    rethrowCriticalFailure(cleanupFailure)
                }

                rethrowCriticalFailure(cause)

                logger.warn("OSC52 interception is unavailable for this Reworked Terminal version", cause)
                null
            }
        }
    }
}

private fun rethrowCriticalFailure(failure: Throwable) {
    when (failure) {
        is CancellationException, is ControlFlowException -> throw failure
        is InterruptedException -> {
            Thread.currentThread().interrupt()
            throw failure
        }

        is Error -> if (failure !is LinkageError) throw failure
    }
}
