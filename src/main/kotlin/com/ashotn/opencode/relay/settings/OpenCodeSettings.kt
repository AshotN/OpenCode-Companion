package com.ashotn.opencode.relay.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "OpenCodeRelaySettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class OpenCodeSettings : PersistentStateComponent<OpenCodeSettings.State> {

    companion object {
        const val DEFAULT_SERVER_AUTH_USERNAME: String = "opencode"

        fun getInstance(project: Project): OpenCodeSettings =
            project.getService(OpenCodeSettings::class.java)
    }

    data class EnvironmentVariable(
        var name: String = "",
        var value: String = "",
    )

    enum class TerminalEngine {
        /** JBTerminalWidget (classic terminal plugin, works on all supported IDE versions). */
        CLASSIC,

        /** TerminalToolWindowTabsManager (reworked terminal, requires IntelliJ 2025.3+). */
        REWORKED,
    }

    data class State(
        var serverPort: Int = 4096,
        var serverHostname: String = "127.0.0.1",
        var serverMdnsEnabled: Boolean = false,
        var serverMdnsDomain: String = "opencode.local",
        var serverCorsOrigins: String = "",
        var serverAuthUsername: String = DEFAULT_SERVER_AUTH_USERNAME,
        var protectPluginLaunchedServerWithAuth: Boolean = false,
        var serverEnvironmentVariables: MutableList<EnvironmentVariable> = mutableListOf(),
        var executablePath: String = "",
        var inlineDiffEnabled: Boolean = true,
        var relayPromptInjectionEnabled: Boolean = true,
        var diffTraceEnabled: Boolean = false,
        var diffTraceHistoryEnabled: Boolean = false,
        var inlineTerminalEnabled: Boolean = true,
        var sessionsSectionVisible: Boolean = true,
        var terminalEngine: TerminalEngine = TerminalEngine.CLASSIC,
        var braveModeEnabled: Boolean = false,
        var jetBrainsMcpWarningEnabled: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state.deepCopy()
    }

    var serverPort: Int
        get() = state.serverPort
        set(value) {
            state.serverPort = value
        }

    var serverHostname: String
        get() = state.serverHostname
        set(value) {
            state.serverHostname = value
        }

    var serverMdnsEnabled: Boolean
        get() = state.serverMdnsEnabled
        set(value) {
            state.serverMdnsEnabled = value
        }

    var serverMdnsDomain: String
        get() = state.serverMdnsDomain
        set(value) {
            state.serverMdnsDomain = value
        }

    var serverCorsOrigins: String
        get() = state.serverCorsOrigins
        set(value) {
            state.serverCorsOrigins = value
        }

    var serverAuthUsername: String
        get() = state.serverAuthUsername
        set(value) {
            state.serverAuthUsername = value
        }

    var protectPluginLaunchedServerWithAuth: Boolean
        get() = state.protectPluginLaunchedServerWithAuth
        set(value) {
            state.protectPluginLaunchedServerWithAuth = value
        }

    var serverEnvironmentVariables: List<EnvironmentVariable>
        get() = state.serverEnvironmentVariables.map { it.copy() }
        set(value) {
            state.serverEnvironmentVariables = value.map { it.copy() }.toMutableList()
        }

    var executablePath: String
        get() = state.executablePath
        set(value) {
            state.executablePath = value
        }

    var inlineDiffEnabled: Boolean
        get() = state.inlineDiffEnabled
        set(value) {
            state.inlineDiffEnabled = value
        }

    var relayPromptInjectionEnabled: Boolean
        get() = state.relayPromptInjectionEnabled
        set(value) {
            state.relayPromptInjectionEnabled = value
        }

    var diffTraceEnabled: Boolean
        get() = state.diffTraceEnabled
        set(value) {
            state.diffTraceEnabled = value
        }

    var diffTraceHistoryEnabled: Boolean
        get() = state.diffTraceHistoryEnabled
        set(value) {
            state.diffTraceHistoryEnabled = value
        }

    var inlineTerminalEnabled: Boolean
        get() = state.inlineTerminalEnabled
        set(value) {
            state.inlineTerminalEnabled = value
        }

    var sessionsSectionVisible: Boolean
        get() = state.sessionsSectionVisible
        set(value) {
            state.sessionsSectionVisible = value
        }

    var terminalEngine: TerminalEngine
        get() = state.terminalEngine
        set(value) {
            state.terminalEngine = value
        }

    var braveModeEnabled: Boolean
        get() = state.braveModeEnabled
        set(value) {
            state.braveModeEnabled = value
        }

    var jetBrainsMcpWarningEnabled: Boolean
        get() = state.jetBrainsMcpWarningEnabled
        set(value) {
            state.jetBrainsMcpWarningEnabled = value
        }

}

fun OpenCodeSettings.State.deepCopy(): OpenCodeSettings.State = copy(
    serverEnvironmentVariables = serverEnvironmentVariables.map { it.copy() }.toMutableList(),
)

fun OpenCodeSettings.snapshot(): OpenCodeSettingsSnapshot = state.toSnapshot()

fun OpenCodeSettings.State.toSnapshot(): OpenCodeSettingsSnapshot = OpenCodeSettingsSnapshot(
    serverPort = serverPort,
    serverHostname = serverHostname,
    serverMdnsEnabled = serverMdnsEnabled,
    serverMdnsDomain = serverMdnsDomain,
    serverCorsOrigins = serverCorsOrigins,
    serverAuthUsername = serverAuthUsername,
    protectPluginLaunchedServerWithAuth = protectPluginLaunchedServerWithAuth,
    serverEnvironmentVariables = serverEnvironmentVariables.map { it.copy() },
    executablePath = executablePath,
    inlineDiffEnabled = inlineDiffEnabled,
    relayPromptInjectionEnabled = relayPromptInjectionEnabled,
    diffTraceEnabled = diffTraceEnabled,
    diffTraceHistoryEnabled = diffTraceHistoryEnabled,
    inlineTerminalEnabled = inlineTerminalEnabled,
    sessionsSectionVisible = sessionsSectionVisible,
    terminalEngine = terminalEngine,
    braveModeEnabled = braveModeEnabled,
    jetBrainsMcpWarningEnabled = jetBrainsMcpWarningEnabled,
)

fun OpenCodeSettings.processEnvironmentVariables(overrides: Map<String, String> = emptyMap()): Map<String, String> =
    serverEnvironmentVariables.associate { it.name to it.value } + overrides
