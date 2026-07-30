package com.ashotn.opencode.relay

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties

internal object OpenCodeRelayPromptPlugin {

    internal const val CONFIG_DIR_ENV = "OPENCODE_CONFIG_DIR"
    private const val RESOURCE_PATH = "/opencode-relay/plugins/opencode-relay-prompt.js"
    private const val VERSION_RESOURCE_PATH = "/opencode-relay/plugin.properties"
    private const val VERSION_PROPERTY = "pluginVersion"
    private const val PLUGIN_RELATIVE_PATH = "plugins/opencode-relay-prompt.js"
    private const val IDE_GUIDANCE_PLACEHOLDER = "__OPENCODE_RELAY_IDE_GUIDANCE__"

    internal var configDirectoryProvider: () -> Path = {
        Path.of(PathManager.getSystemPath(), "opencode-relay", "opencode-config")
    }
    internal var resourceTextProvider: () -> String = { bundledPluginText() }
    internal var ideDescriptionProvider: () -> String = { currentIdeDescription() }
    internal var pluginVersionProvider: () -> String = { currentPluginVersion() }

    sealed interface InjectionResult {
        data object Disabled : InjectionResult
        data class Injected(val configDirectory: Path) : InjectionResult
        data class SkippedExistingConfigDir(val environmentName: String) : InjectionResult
        data class Failed(val message: String) : InjectionResult
    }

    fun configure(processBuilder: ProcessBuilder, enabled: Boolean): InjectionResult {
        if (!enabled) return InjectionResult.Disabled

        val environment = processBuilder.environment()
        val existingConfigDirKey = environment.keys.firstOrNull { it.equals(CONFIG_DIR_ENV, ignoreCase = true) }
        if (existingConfigDirKey != null && !environment[existingConfigDirKey].isNullOrBlank()) {
            return InjectionResult.SkippedExistingConfigDir(existingConfigDirKey)
        }

        return try {
            val configDirectory = materialize()
            environment[existingConfigDirKey ?: CONFIG_DIR_ENV] = configDirectory.toString()
            InjectionResult.Injected(configDirectory)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            log.warn("Failed to install OpenCode Relay prompt plugin", e)
            InjectionResult.Failed(message)
        }
    }

    internal fun materialize(): Path {
        val configDirectory = configDirectoryProvider()
        val pluginFile = configDirectory.resolve(PLUGIN_RELATIVE_PATH)
        Files.createDirectories(pluginFile.parent)
        Files.writeString(
            pluginFile,
            renderPluginText(resourceTextProvider()),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return configDirectory
    }

    internal fun renderPluginText(template: String): String {
        if (!template.contains(IDE_GUIDANCE_PLACEHOLDER)) return template
        return template.replace(IDE_GUIDANCE_PLACEHOLDER, gson.toJson(ideGuidance()))
    }

    internal fun ideGuidance(): String =
        """You are running inside ${ideDescriptionProvider()} through OpenCode Relay (plugin version ${pluginVersionProvider()}).

When citing files, emit plain project-relative paths using JetBrains terminal link syntax. Use ./path/to/File.kt:42 for a specific line and ./path/to/File.kt without one. For a range, link the first line and describe the range, for example ./path/to/File.kt:42 (lines 42-48). For a file outside the project, use a file:///absolute/path URL. Always include the ./ prefix for project-relative paths; do not use Markdown links, #L anchors, line-range suffixes, or plain absolute paths.
"""

    private fun currentIdeDescription(): String {
        val applicationInfo = ApplicationInfo.getInstance()
        return "${applicationInfo.fullApplicationName} (build ${applicationInfo.build.asString()})"
    }

    private fun currentPluginVersion(): String =
        OpenCodeRelayPromptPlugin::class.java.getResourceAsStream(VERSION_RESOURCE_PATH)
            ?.use { stream ->
                Properties().apply { load(stream) }.getProperty(VERSION_PROPERTY)
            }
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

    private fun bundledPluginText(): String {
        val stream = OpenCodeRelayPromptPlugin::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: error("Missing bundled OpenCode Relay plugin resource: $RESOURCE_PATH")
        return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val log = logger<OpenCodeRelayPromptPlugin>()
}
