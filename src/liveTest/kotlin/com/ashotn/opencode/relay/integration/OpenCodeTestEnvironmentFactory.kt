package com.ashotn.opencode.relay.integration

import com.ashotn.opencode.relay.api.health.HealthApiClient
import com.ashotn.opencode.relay.api.transport.ApiResult
import java.nio.file.Files
import java.nio.file.Path
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

object OpenCodeTestEnvironmentFactory {

    private const val AUTH_JSON_ENV = "OPENCODE_TEST_AUTH_JSON"

    fun create(version: String, allowTask: Boolean = false): OpenCodeTestEnvironment {
        val projectRoot = Path.of("").toAbsolutePath().normalize()
        val opencodeBin = resolveOpenCodeBinary(projectRoot, version)

        val installedVersion = readVersion(opencodeBin)
        require(installedVersion == version) {
            "Expected OpenCode version $version at $opencodeBin but found $installedVersion"
        }

        val runsRoot = projectRoot.resolve(".cache/opencode-test/runs/$version")
        runsRoot.createDirectories()

        val scenarioRoot = Files.createTempDirectory(runsRoot, "scenario-")
        val repoRoot = scenarioRoot.resolve("repo")
        val homeRoot = scenarioRoot.resolve("home")
        val xdgConfigHome = scenarioRoot.resolve("xdg-config")
        val xdgDataHome = scenarioRoot.resolve("xdg-data")
        val xdgCacheHome = scenarioRoot.resolve("xdg-cache")
        val xdgStateHome = scenarioRoot.resolve("xdg-state")
        val artifactsRoot = scenarioRoot.resolve("artifacts")
        val authPath = xdgDataHome.resolve("opencode/auth.json")
        val authJson = loadAuthJson()

        listOf(
            repoRoot,
            homeRoot,
            xdgConfigHome,
            xdgDataHome,
            xdgCacheHome,
            xdgStateHome,
            artifactsRoot,
        ).forEach { it.createDirectories() }

        initializeGitRepo(repoRoot)
        repoRoot.resolve("README.md").writeText("# OpenCode Test Repo\n")
        authPath.parent.createDirectories()
        authPath.writeText(authJson)

        val permission = if (allowTask) {
            "{\"edit\":\"allow\",\"bash\":\"deny\",\"task\":\"allow\",\"webfetch\":\"deny\",\"doom_loop\":\"deny\",\"external_directory\":\"deny\"}"
        } else {
            "{\"edit\":\"allow\",\"bash\":\"deny\",\"webfetch\":\"deny\",\"doom_loop\":\"deny\",\"external_directory\":\"deny\"}"
        }

        val env = linkedMapOf(
            "HOME" to homeRoot.toString(),
            "XDG_CONFIG_HOME" to xdgConfigHome.toString(),
            "XDG_DATA_HOME" to xdgDataHome.toString(),
            "XDG_CACHE_HOME" to xdgCacheHome.toString(),
            "XDG_STATE_HOME" to xdgStateHome.toString(),
            "OPENCODE_TEST_HOME" to homeRoot.toString(),
            "OPENCODE_DISABLE_AUTOUPDATE" to "true",
            "OPENCODE_DISABLE_LSP_DOWNLOAD" to "true",
            "OPENCODE_PERMISSION" to permission,
        )

        return OpenCodeTestEnvironment(
            version = version,
            scenarioRoot = scenarioRoot,
            repoRoot = repoRoot,
            artifactsRoot = artifactsRoot,
            authPath = authPath,
            opencodeBin = opencodeBin,
            env = env,
        )
    }

    private fun loadAuthJson(): String =
        System.getenv(AUTH_JSON_ENV)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "Missing required $AUTH_JSON_ENV environment variable for OpenCode test auth.",
            )

    private fun resolveOpenCodeBinary(projectRoot: Path, version: String): Path {
        val binRoot = projectRoot.resolve(".cache/opencode-test/bins/$version")
        val opencodeBin = binRoot.resolve("node_modules/.bin/opencode")
        if (!opencodeBin.exists()) {
            installOpenCodeVersion(binRoot, version)
        }
        require(opencodeBin.exists()) {
            "OpenCode $version is not installed at $opencodeBin"
        }
        return opencodeBin
    }

    private fun installOpenCodeVersion(binRoot: Path, version: String) {
        binRoot.createDirectories()
        val process = ProcessBuilder("npm", "install", "--prefix", binRoot.toString(), "opencode-ai@$version")
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Failed to install OpenCode $version into $binRoot: $output"
        }
    }

    private fun readVersion(opencodeBin: Path): String {
        val process = ProcessBuilder(opencodeBin.toString(), "--version")
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Failed to read OpenCode version from $opencodeBin: $output"
        }
        return output
    }

    private fun initializeGitRepo(repoRoot: Path) {
        val process = ProcessBuilder("git", "-c", "init.defaultBranch=main", "init", repoRoot.toString())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Failed to initialize git repo at $repoRoot: $output"
        }
    }
}

class OpenCodeTestEnvironment(
    val version: String,
    val scenarioRoot: Path,
    val repoRoot: Path,
    val artifactsRoot: Path,
    val authPath: Path,
    val opencodeBin: Path,
    val env: Map<String, String>,
) : AutoCloseable {

    private val healthApiClient = HealthApiClient()
    private var server: OpenCodeTestServer? = null

    fun startServer(timeoutMs: Long = 15_000): OpenCodeTestServer {
        check(server == null) { "Scenario environment already has a running server" }

        val port = ServerSocket(0).use { it.localPort }
        val stdoutLog = artifactsRoot.resolve("opencode-stdout.log").toFile()
        val stderrLog = artifactsRoot.resolve("opencode-stderr.log").toFile()

        val process = ProcessBuilder(opencodeBin.toString(), "serve", "--port", port.toString())
            .directory(repoRoot.toFile())
            .redirectOutput(stdoutLog)
            .redirectError(stderrLog)
            .apply {
                environment().putAll(env)
            }
            .start()

        val startedServer = OpenCodeTestServer(
            process = process,
            port = port,
            stdoutLog = stdoutLog.toPath(),
            stderrLog = stderrLog.toPath(),
        )

        try {
            waitForHealthy(startedServer, timeoutMs)
        } catch (t: Throwable) {
            startedServer.close()
            throw t
        }

        server = startedServer
        return startedServer
    }

    private fun waitForHealthy(server: OpenCodeTestServer, timeoutMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (!server.process.isAlive) {
                val stdout = server.stdoutLog.toFile().takeIf { it.exists() }?.readText() ?: ""
                val stderr = server.stderrLog.toFile().takeIf { it.exists() }?.readText() ?: ""
                throw IllegalStateException(
                    "OpenCode server exited before becoming healthy. stdout=[$stdout] stderr=[$stderr]",
                )
            }

            when (val result = healthApiClient.isHealthy(server.port)) {
                is ApiResult.Success -> if (result.value) return
                is ApiResult.Failure -> Unit
            }

            Thread.sleep(100)
        }

        val stdout = server.stdoutLog.toFile().takeIf { it.exists() }?.readText() ?: ""
        val stderr = server.stderrLog.toFile().takeIf { it.exists() }?.readText() ?: ""
        throw IllegalStateException(
            "Timed out waiting for OpenCode server health on port ${server.port}. stdout=[$stdout] stderr=[$stderr]",
        )
    }

    override fun close() {
        server?.close()
        server = null
        scenarioRoot.deleteRecursively()
    }

    fun diagnosticsSummary(maxLogLines: Int = 40): String = buildString {
        appendLine("scenarioRoot=$scenarioRoot")
        appendLine("repoRoot=$repoRoot")
        appendLine("artifactsRoot=$artifactsRoot")
        server?.let { startedServer ->
            appendLine("serverPort=${startedServer.port}")
            appendLine("stdoutLog=${startedServer.stdoutLog}")
            appendLine("stderrLog=${startedServer.stderrLog}")
            appendLine("stdoutTail=<<${readTail(startedServer.stdoutLog, maxLogLines)}>>")
            appendLine("stderrTail=<<${readTail(startedServer.stderrLog, maxLogLines)}>>")
        }
    }

    private fun readTail(path: Path, maxLines: Int): String {
        if (!path.exists()) return "<missing>"
        val lines = Files.readAllLines(path)
        return lines.takeLast(maxLines).joinToString("\\n")
    }

    private fun Path.deleteRecursively() {
        if (!exists()) return
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                path.deleteIfExists()
            }
        }
    }
}

class OpenCodeTestServer(
    val process: Process,
    val port: Int,
    val stdoutLog: Path,
    val stderrLog: Path,
) : AutoCloseable {

    override fun close() {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
}
