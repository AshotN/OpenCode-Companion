plugins {
    id("java")
    id("java-test-fixtures")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

fun extractReleasedChangeNotes(changelog: String, version: String): String {
    val sectionPattern = Regex(
        pattern = """(?ms)^## \[${Regex.escape(version)}\](?: - [^\r\n]*)?\r?\n(.*?)(?=^## \[|\z)""",
    )
    return sectionPattern.find(changelog)?.groupValues?.get(1)?.trim().orEmpty()
}

group = "com.ashotn"
val pluginVersion = providers.gradleProperty("pluginVersion").get()
val isCiBuild = providers.environmentVariable("CI")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
    .get()
val isReleaseBuild = providers.environmentVariable("GITHUB_REF_TYPE")
    .map { it == "tag" }
    .orElse(false)
    .get()
val githubSha = providers.environmentVariable("GITHUB_SHA").orNull

version = when {
    isReleaseBuild -> pluginVersion
    isCiBuild && githubSha != null -> "$pluginVersion-${githubSha.takeLast(7)}"
    isCiBuild -> pluginVersion
    else -> "$pluginVersion-local"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val liveTest by sourceSets.creating {
    java.srcDir("src/liveTest/kotlin")
    resources.srcDir("src/liveTest/resources")
    compileClasspath += sourceSets["main"].output + sourceSets["testFixtures"].output
    runtimeClasspath += output + compileClasspath
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3.3")
        pluginVerifier()
        bundledPlugin("org.jetbrains.plugins.terminal")
        compatiblePlugin("com.intellij.mcpServer")
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":")))
    add(liveTest.implementationConfigurationName, testFixtures(project(":")))
    intellijPlatform {
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

configurations[liveTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[liveTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

intellijPlatform {
    instrumentCode = false

    pluginConfiguration {
        id = "com.ashotn.opencode-relay"
        name = "OpenCode Relay"
        version = project.version.toString()
        changeNotes = providers
            .fileContents(layout.projectDirectory.file("CHANGELOG.md"))
            .asText
            .map { changelog -> extractReleasedChangeNotes(changelog, pluginVersion) }

        vendor {
            name = "Ashot Nazaryan"
        }

        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2025.3.3")
        }
    }

    signing {
        // Configure signing for production releases
    }

    publishing {
        // Configure publishing to JetBrains Marketplace
    }
}

val sandboxProject = layout.buildDirectory.dir("sandbox-project").get().asFile
val idea262Home = providers.gradleProperty("idea262Home")
    .orElse(providers.environmentVariable("IDEA_262_HOME"))
    .orElse(providers.provider {
        "${System.getProperty("user.home")}/.local/share/JetBrains/Toolbox/apps/intellij-idea"
    })

val runIde262 by intellijPlatformTesting.runIde.registering {
    localPath = layout.dir(idea262Home.map(::file))
    sandboxDirectory = layout.buildDirectory.dir("idea-sandbox-262")

    task {
        description = "Runs the plugin in a local IntelliJ IDEA 2026.2 sandbox."
        doFirst { sandboxProject.mkdirs() }
        args(sandboxProject.absolutePath)
    }
}

fun mainOutputFriendPaths(): String =
    sourceSets["main"].output.classesDirs.files.joinToString(",") { it.absolutePath }

tasks {
    processResources {
        filesMatching("opencode-relay/plugin.properties") {
            expand("pluginVersion" to project.version.toString())
        }
    }

    runIde {
        // Open a blank project automatically, bypassing the welcome screen
        doFirst { sandboxProject.mkdirs() }
        args(sandboxProject.absolutePath)
    }

    register<Test>("liveTest") {
        val baseTestTask = named<Test>("test").get()
        val testOutputFiles = sourceSets["test"].output.files
        description = "Runs the live OpenCode integration tests."
        group = "verification"
        testClassesDirs = liveTest.output.classesDirs
        // Reuse the IntelliJ/Gson runtime that the plugin's regular test task assembles,
        // but keep src/test outputs off the live-test runtime so live tests don't depend on unit-test code.
        classpath = files(
            liveTest.runtimeClasspath,
            baseTestTask.classpath.filter { it !in testOutputFiles },
        )
        listOf("OPENCODE_TEST_VERSIONS", "OPENCODE_TEST_AUTH_JSON").forEach { name ->
            System.getenv(name)?.let { value ->
                environment(name, value)
            }
        }
        shouldRunAfter(test)
        useJUnit()
    }

    named("compileLiveTestKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class) {
        compilerOptions.freeCompilerArgs.add(
            "-Xfriend-paths=${mainOutputFriendPaths()}",
        )
    }

    named("compileTestFixturesKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class) {
        compilerOptions.freeCompilerArgs.add(
            "-Xfriend-paths=${mainOutputFriendPaths()}",
        )
    }
}

kotlin {
    jvmToolchain(21)
}
