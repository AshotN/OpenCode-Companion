package com.ashotn.opencode.relay

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenCodeRelayPromptPluginTest {

    private val defaultConfigDirectoryProvider = OpenCodeRelayPromptPlugin.configDirectoryProvider
    private val defaultResourceTextProvider = OpenCodeRelayPromptPlugin.resourceTextProvider
    private val defaultIdeDescriptionProvider = OpenCodeRelayPromptPlugin.ideDescriptionProvider
    private val defaultPluginVersionProvider = OpenCodeRelayPromptPlugin.pluginVersionProvider
    private val tempDirectories = mutableListOf<Path>()

    @After
    fun tearDown() {
        OpenCodeRelayPromptPlugin.configDirectoryProvider = defaultConfigDirectoryProvider
        OpenCodeRelayPromptPlugin.resourceTextProvider = defaultResourceTextProvider
        OpenCodeRelayPromptPlugin.ideDescriptionProvider = defaultIdeDescriptionProvider
        OpenCodeRelayPromptPlugin.pluginVersionProvider = defaultPluginVersionProvider
        tempDirectories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `configure writes plugin file and sets config dir`() {
        val configDirectory = createTempDirectory()
        val pluginText = "export const TestPlugin = async () => ({})\n"
        OpenCodeRelayPromptPlugin.configDirectoryProvider = { configDirectory }
        OpenCodeRelayPromptPlugin.resourceTextProvider = { pluginText }
        val processBuilder = ProcessBuilder("opencode")
        removeConfigDirEnvironment(processBuilder)

        val result = OpenCodeRelayPromptPlugin.configure(processBuilder, enabled = true)

        assertIs<OpenCodeRelayPromptPlugin.InjectionResult.Injected>(result)
        assertEquals(configDirectory, result.configDirectory)
        assertEquals(configDirectory.toString(), processBuilder.environment()[OpenCodeRelayPromptPlugin.CONFIG_DIR_ENV])
        assertEquals(pluginText, Files.readString(configDirectory.resolve("plugins/opencode-relay-prompt.js")))
    }

    @Test
    fun `configure does not overwrite existing config dir`() {
        val configDirectory = createTempDirectory()
        OpenCodeRelayPromptPlugin.configDirectoryProvider = { configDirectory }
        val processBuilder = ProcessBuilder("opencode")
        removeConfigDirEnvironment(processBuilder)
        processBuilder.environment()[OpenCodeRelayPromptPlugin.CONFIG_DIR_ENV] = "/custom/opencode-config"

        val result = OpenCodeRelayPromptPlugin.configure(processBuilder, enabled = true)

        assertIs<OpenCodeRelayPromptPlugin.InjectionResult.SkippedExistingConfigDir>(result)
        assertEquals("/custom/opencode-config", processBuilder.environment()[OpenCodeRelayPromptPlugin.CONFIG_DIR_ENV])
        assertFalse(Files.exists(configDirectory.resolve("plugins/opencode-relay-prompt.js")))
    }

    @Test
    fun `configure replaces blank existing config dir`() {
        val configDirectory = createTempDirectory()
        OpenCodeRelayPromptPlugin.configDirectoryProvider = { configDirectory }
        OpenCodeRelayPromptPlugin.resourceTextProvider = { "export const TestPlugin = async () => ({})\n" }
        val processBuilder = ProcessBuilder("opencode")
        removeConfigDirEnvironment(processBuilder)
        processBuilder.environment()[OpenCodeRelayPromptPlugin.CONFIG_DIR_ENV] = ""

        val result = OpenCodeRelayPromptPlugin.configure(processBuilder, enabled = true)

        assertIs<OpenCodeRelayPromptPlugin.InjectionResult.Injected>(result)
        assertEquals(configDirectory.toString(), processBuilder.environment()[OpenCodeRelayPromptPlugin.CONFIG_DIR_ENV])
        assertTrue(Files.exists(configDirectory.resolve("plugins/opencode-relay-prompt.js")))
    }

    @Test
    fun `configure skips work when disabled`() {
        val configDirectory = createTempDirectory()
        OpenCodeRelayPromptPlugin.configDirectoryProvider = { configDirectory }
        val processBuilder = ProcessBuilder("opencode")
        removeConfigDirEnvironment(processBuilder)

        val result = OpenCodeRelayPromptPlugin.configure(processBuilder, enabled = false)

        assertEquals(OpenCodeRelayPromptPlugin.InjectionResult.Disabled, result)
        assertFalse(processBuilder.environment().containsKey(OpenCodeRelayPromptPlugin.CONFIG_DIR_ENV))
        assertFalse(Files.exists(configDirectory.resolve("plugins/opencode-relay-prompt.js")))
    }

    @Test
    fun `configure renders bundled prompt metadata`() {
        val configDirectory = createTempDirectory()
        OpenCodeRelayPromptPlugin.configDirectoryProvider = { configDirectory }
        OpenCodeRelayPromptPlugin.resourceTextProvider = {
            "const IDE_GUIDANCE = __OPENCODE_RELAY_IDE_GUIDANCE__\n"
        }
        OpenCodeRelayPromptPlugin.ideDescriptionProvider = { "IntelliJ IDEA 2026.1 (build IU-261.1)" }
        OpenCodeRelayPromptPlugin.pluginVersionProvider = { "test-plugin-version" }
        val processBuilder = ProcessBuilder("opencode")
        removeConfigDirEnvironment(processBuilder)

        OpenCodeRelayPromptPlugin.configure(processBuilder, enabled = true)

        val pluginText = Files.readString(configDirectory.resolve("plugins/opencode-relay-prompt.js"))
        val guidance = extractIdeGuidance(pluginText)
        assertTrue(guidance.contains("You are running inside IntelliJ IDEA 2026.1 (build IU-261.1) through OpenCode Relay (plugin version test-plugin-version)."))
        assertTrue(guidance.contains("./path/to/File.kt:42"))
        assertTrue(guidance.contains("./path/to/File.kt without one"))
        assertTrue(guidance.contains("./path/to/File.kt:42 (lines 42-48)"))
        assertTrue(guidance.contains("file:///absolute/path"))
        assertTrue(guidance.contains("do not use Markdown links, #L anchors, line-range suffixes, or plain absolute paths"))
        assertFalse(pluginText.contains("__OPENCODE_RELAY_IDE_GUIDANCE__"))
    }

    @Test
    fun `configure renders prompt metadata as escaped javascript string`() {
        val configDirectory = createTempDirectory()
        OpenCodeRelayPromptPlugin.configDirectoryProvider = { configDirectory }
        OpenCodeRelayPromptPlugin.resourceTextProvider = {
            "const IDE_GUIDANCE = __OPENCODE_RELAY_IDE_GUIDANCE__\n"
        }
        OpenCodeRelayPromptPlugin.ideDescriptionProvider = { "IntelliJ \"IDEA\"\nUltimate" }
        OpenCodeRelayPromptPlugin.pluginVersionProvider = { "1\\2" }
        val processBuilder = ProcessBuilder("opencode")
        removeConfigDirEnvironment(processBuilder)

        OpenCodeRelayPromptPlugin.configure(processBuilder, enabled = true)

        val pluginText = Files.readString(configDirectory.resolve("plugins/opencode-relay-prompt.js"))
        val guidance = extractIdeGuidance(pluginText)
        assertTrue(pluginText.startsWith("const IDE_GUIDANCE = \""))
        assertTrue(guidance.contains("IntelliJ \"IDEA\"\nUltimate"))
        assertTrue(guidance.contains("plugin version 1\\2"))
    }

    private fun extractIdeGuidance(pluginText: String): String {
        val literal = pluginText
            .removePrefix("const IDE_GUIDANCE = ")
            .substringBefore("\n")
        return JsonParser.parseString(literal).asString
    }

    private fun createTempDirectory(): Path = Files.createTempDirectory("opencode-relay-test").also {
        tempDirectories.add(it)
    }

    private fun removeConfigDirEnvironment(processBuilder: ProcessBuilder) {
        processBuilder.environment().keys
            .filter { it.equals(OpenCodeRelayPromptPlugin.CONFIG_DIR_ENV, ignoreCase = true) }
            .toList()
            .forEach { processBuilder.environment().remove(it) }
    }
}
