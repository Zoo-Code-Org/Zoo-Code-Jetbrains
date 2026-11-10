// Copyright 2009-2025 Weibo, Inc.
// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: APACHE2.0
// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.process.ExecOperations
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Convenient for reading variables from gradle.properties
fun properties(key: String) = providers.gradleProperty(key)

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.20.0"
    id("org.jlleitschuh.gradle.ktlint") version "11.6.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.4"
}

apply(from = "genPlatform.gradle")

// Use Java/Kotlin toolchains instead of per-task source/target
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

// Toolchain launcher used by verification tasks that start a plain JVM.
val verificationJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

// ------------------------------------------------------------
// The 'debugMode' setting controls how plugin resources are prepared during the build process.
// It supports the following three modes:
//
// 1. "idea" — Local development mode (used for debugging VSCode plugin integration)
//    - Copies theme resources from src/main/resources/themes to:
//        ../debug-resources/<vscodePlugin>/src/integrations/theme/default-themes/
//    - Automatically creates a .env file, which the Extension Host (Node.js side) reads at runtime.
//    - Enables the VSCode plugin to load resources from this directory for integration testing.
//    - Typically used when running IntelliJ with an Extension Host for live debugging and hot-reloading.
//
// 2. "release" — Production build mode (used to generate deployment artifacts)
//    - Packages platform.zip, which the genPlatform task generates from the VS Code
//      distributions pinned by vscodeVersion in gradle.properties. The archive is not
//      stored in git; when it is missing or invalid, prepareSandbox depends on
//      genPlatform and the archive is generated before the sandbox is prepared.
//    - This file includes the full runtime environment for VSCode plugins (e.g., node_modules, platform.txt).
//    - The zip is extracted to build/platform/, and its node_modules take precedence over other dependencies.
//    - Copies compiled extension_host outputs (dist, package.json, node_modules) and plugin resources.
//    - The result is a fully self-contained package ready for deployment across platforms.
//
// 3. "none" (default) — Lightweight mode (used for testing and CI)
//    - Does not rely on platform.zip or prepare VSCode runtime resources.
//    - Only copies the plugin's core assets such as themes.
//    - Useful for early-stage development, static analysis, unit tests, and continuous integration pipelines.
//
// How to configure:
//   - Set via gradle argument: -PdebugMode=idea / release / none
//     Example: ./gradlew prepareSandbox -PdebugMode=idea
//   - Defaults to "none" if not explicitly set.
// ------------------------------------------------------------
// Extra properties (Kotlin DSL style)
val ext = project.extensions.extraProperties
ext.set("debugMode", findProperty("debugMode") ?: "none")
ext.set("debugResource", project.projectDir.resolve("../debug-resources").absolutePath)
ext.set("vscodePlugin", findProperty("vscodePlugin") ?: "zoo-code")

// Strongly-typed providers (avoid stringly-typed ext lookups during configuration)
val debugModeProp = providers.gradleProperty("debugMode").orElse("none")
val vscodePluginProp = providers.gradleProperty("vscodePlugin").orElse("zoo-code")

fun Sync.prepareSandbox() {
    // Read once during configuration; values also wired as task inputs below
    val debugMode = debugModeProp.get()
    val vsCodePluginName = vscodePluginProp.get()

    // ---- Copy logging helpers ----
    val copyOps = mutableListOf<Pair<String, String>>() // (src, dest)

    fun resolvedDestPath(rawDest: String, destinationDir: File): File {
        return if (File(rawDest).isAbsolute) File(rawDest) else File(destinationDir, rawDest)
    }

    fun Sync.copyAndTrack(
        src: Any,
        dest: String,
        createDestIfMissing: Boolean = false,
        configure: CopySpec.() -> Unit = {}
    ) {
        if (createDestIfMissing) {
            val destFile = resolvedDestPath(dest, destinationDir)
            if (!destFile.exists()) {
                destFile.mkdirs()
                logger.lifecycle("[prepareSandbox] Created missing destination directory: ${destFile.absolutePath}")
            }
        }
        logger.lifecycle("[prepareSandbox] Scheduling copy: $src -> $dest")
        val destFile = resolvedDestPath(dest, destinationDir)
        if (File(dest).isAbsolute) {
            // Absolute dest: perform copy outside of this Sync's destinationDir
            doLast {
                logger.lifecycle("[prepareSandbox] Executing external copy: $src -> ${destFile.absolutePath}")
                project.copy {
                    from(src)
                    into(destFile)
                    configure.invoke(this)
                }
            }
        } else {
            // Relative dest: use this Sync's copy spec
            from(src) {
                into(dest)
                configure.invoke(this)
            }
        }
        copyOps.add(src.toString() to dest)
    }

    fun Sync.copyNodeModulesFiltered(srcDir: String, destDir: String, patterns: List<String>) {
        copyAndTrack(srcDir, destDir) {
            patterns.forEach { include(it) }
        }
    }

    doFirst {
        logger.lifecycle("[prepareSandbox] Starting with debugMode='${debugMode}'")
    }

    // Set duplicate strategy to include files, with later sources taking precedence
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    val sandboxDir = "ZooCode"

    // Themes
    val themesDir = "${project.projectDir.absolutePath}/src/main/resources/themes/"
    if (!File(themesDir).exists()) {
        throw IllegalStateException("missing themes dir")
    }

    // Common
    val vscodePluginDir = File("./plugins/${vsCodePluginName}")
    if (!vscodePluginDir.exists()) {
        throw IllegalStateException("missing plugin dir")
    }

    val depPatterns = mutableListOf<String>()
    val depfile = File("prodDep.txt")
    if (!depfile.exists()) {
        throw IllegalStateException("missing prodDep.txt")
    }
    depfile.readLines().let {
        it.forEach { line ->
            depPatterns.add(line.replace('\\', '/').substringAfterLast("node_modules/") + "/**")
        }
    }

    if (debugMode == "idea") {
        val debugResourceDir = layout.projectDirectory.dir("../debug-resources")
        if (!debugResourceDir.asFile.exists()) {
            mkdir(debugResourceDir)
        }
        val debugDir = debugResourceDir.asFile.absolutePath
        copyAndTrack("${vscodePluginDir.path}/extension","${debugDir}/${vsCodePluginName}", createDestIfMissing = true)
        if (File("${debugDir}/${vsCodePluginName}/src").exists()) {
            copyAndTrack(themesDir, "${debugDir}/${vsCodePluginName}/src/integrations/theme/default-themes/")
        } else {
            copyAndTrack(themesDir, "${debugDir}/${vsCodePluginName}/integrations/theme/default-themes/")

        }
        copyAndTrack(themesDir, "${debugDir}/themes/", createDestIfMissing = true)
        copyAndTrack("../extension_host/dist", "${debugDir}/runtime/", createDestIfMissing = true)
        copyAndTrack("../extension_host/package.json", "${debugDir}/runtime/")
        copyNodeModulesFiltered("../extension_host/node_modules", "${debugDir}/node_modules/", depPatterns)
    } else {
        copyAndTrack("../extension_host/dist", "${sandboxDir}/runtime/")
        copyAndTrack("../extension_host/package.json", "${sandboxDir}/runtime/")
        copyNodeModulesFiltered("../extension_host/node_modules", "${sandboxDir}/node_modules/", depPatterns)
        copyAndTrack("${vscodePluginDir.path}/extension", "${sandboxDir}/${vsCodePluginName}")
        copyAndTrack("src/main/resources/themes/", "${sandboxDir}/${vsCodePluginName}/integrations/theme/default-themes/")
        copyAndTrack("src/main/resources/themes/", "${sandboxDir}/themes/")

        // The platform.zip archive holds the merged node_modules of the four VS Code
        // distributions pinned by vscodeVersion in gradle.properties. It is not stored
        // in git: genPlatform builds it, and prepareSandbox depends on that task when
        // the archive is missing or invalid (for example a stale Git LFS pointer left
        // by an older checkout). To support new architectures, modify genPlatform.gradle.
        if (debugMode == "release") {
            // Resolved against the project directory: the daemon working directory
            // is not the project directory.
            val platformZip = project.file("platform.zip")
            fun usable() = platformZip.isFile && platformZip.length() >= 1024 * 1024
            val platformDir = File("${project.buildDir}/platform")
            fun extractPlatform() {
                check(usable()) {
                    "genPlatform did not produce a valid platform.zip at ${platformZip.absolutePath}. " +
                        "Check the genPlatform task output, network access to update.code.visualstudio.com, " +
                        "and vscodeVersion in gradle.properties."
                }
                platformDir.mkdirs()
                logger.lifecycle("[prepareSandbox] Extracting platform.zip -> ${platformDir.absolutePath}")
                project.copy {
                    from(zipTree(platformZip))
                    into(platformDir)
                }
            }
            if (!usable()) {
                logger.lifecycle("[prepareSandbox] platform.zip is missing or invalid; genPlatform will generate it before the sandbox is prepared")
                dependsOn(project.tasks.named("genPlatform"))
            }
            // Always extract during execution: clean may remove build/platform.
            doFirst { extractPlatform() }
            copyOps.add(platformZip.absolutePath to platformDir.absolutePath)

            copyAndTrack(File(project.buildDir, "platform/platform.txt"), "${sandboxDir}/")
            // Copy platform node_modules last to ensure it takes precedence over extension_host node_modules
            copyAndTrack(File(project.buildDir, "platform/node_modules"), "${sandboxDir}/node_modules")
        }

        doLast {
            File("${destinationDir}/${sandboxDir}/${vsCodePluginName}/.env").createNewFile()
        }
    }

    doLast {
        logger.lifecycle("[prepareSandbox] Completed with debugMode='${debugMode}'. Summary:")
        copyOps.forEach { (src, dest) ->
            val target = resolvedDestPath(dest, destinationDir)
            val ok = if (target.exists()) {
                if (target.isDirectory) target.list()?.isNotEmpty() == true else true
            } else false
            val status = if (ok) "SUCCESS" else "FAIL"
            logger.lifecycle("[prepareSandbox] ${status}: ${src} -> ${target.absolutePath}")
        }
    }
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("platformLocalIde").orNull
        if (localIde != null) local(file(localIde).absolutePath)
        else create {
            type.set(IntelliJPlatformType.entries.single { it.code == properties("platformType").get() })
            version = properties("platformVersion").get()
            useInstaller = false
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier("1.410")
    }
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.4")
}

// The IDE supplies Kotlin; do not package older transitive runtimes from OkHttp/Okio.
configurations.implementation {
    exclude(group = "org.jetbrains.kotlin")
}

// A single modern release line; older IDEs must use an earlier plugin release.
intellijPlatform {
    projectName.set("ZooCode")
    // The plugin contributes no settings Configurable to index.
    buildSearchableOptions.set(false)
    pluginConfiguration {
        version.set(properties("pluginVersion"))
        ideaVersion {
            sinceBuild.set(properties("pluginSinceBuild"))
            untilBuild.set(properties("pluginUntilBuild"))
        }
    }
    pluginVerification {
        failureLevel.set(listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
        ))
        verificationReportsDirectory.set(layout.dir(
            providers.gradleProperty("verifierReportsDir").map { file(it) }
                .orElse(layout.buildDirectory.dir("reports/pluginVerifier").map { it.asFile })
        ))
        ides {
            val localIde = providers.gradleProperty("verifierLocalIde").orNull
            // The dependency helper otherwise resolves relative paths from the
            // Gradle daemon's working directory, not this project directory.
            if (localIde != null) local(file(localIde).absolutePath)
            else create {
                type.set(IntelliJPlatformType.entries.single { it.code == properties("platformType").get() })
                version = properties("platformVersion").get()
                useInstaller = false
            }
        }
    }
}

// Gradle 9 removed Project.exec; use the supported injected process service.
abstract class ProcessServices @Inject constructor(val execOperations: ExecOperations)
val processServices = objects.newInstance<ProcessServices>()

tasks {

    // Create task for generating configuration files
    register("generateConfigProperties") {
        description = "Generate properties file containing plugin configuration"
        doLast {
            val configDir = File("$projectDir/src/main/resources/org/zoocode/jetbrains/plugin/config")
            configDir.mkdirs()

            val configFile = File(configDir, "plugin.properties")
            configFile.writeText("debug.mode=${ext.get("debugMode")}")
            configFile.appendText("\n")
            configFile.appendText("debug.resource=${ext.get("debugResource")}")
            println("Configuration file generated: ${configFile.absolutePath}")
        }
    }

    prepareSandbox {
        // Wire task inputs for build cache/key stability
        inputs.property("build_mode", debugModeProp)
        inputs.property("vscode_plugin", vscodePluginProp)
        prepareSandbox()
    }

    // Generate configuration file before compilation
    withType<JavaCompile> {
        dependsOn("generateConfigProperties")
    }

    // Set the JVM compatibility versions
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        dependsOn("generateConfigProperties")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            languageVersion.set(KotlinVersion.KOTLIN_2_2)
            apiVersion.set(KotlinVersion.KOTLIN_2_2)
        }
    }

    // Regression gate: reject legacy terminal process calls and internal PTY APIs.
    // The runner starts our process through TerminalInstance.startTerminalProcess.
    //
    // The packaged plugin jar inside the sandbox: instrumentation may rename it with
    // an "instrumented-" prefix, and patchPluginXml writes the release descriptor into
    // exactly this copy.
    fun packagedPluginJar(): File {
        val archiveName = project.tasks.named("composedJar", org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask::class.java).get().archiveFileName.get()
        val libDir = File(File(prepareSandbox.get().destinationDir, "ZooCode"), "lib")
        val candidates = libDir.listFiles { file -> file.name.endsWith(archiveName) }
            ?: throw GradleException("Sandbox lib directory not found: $libDir")
        return candidates.firstOrNull { it.name.startsWith("instrumented-") }
            ?: candidates.firstOrNull()
            ?: throw GradleException("No packaged plugin jar ($archiveName) found in $libDir")
    }

    // The scan delegates to smoke/Smoke263DispatchCheck.java --scan-jar so the banned
    // reference list and the constant-pool parsing live in one place. The checker is
    // linked against the target platform jars already on the compile classpath; this task
    // never needs the 2026.3 IDE. The scan target is the instrumented jar in the
    // sandbox, the exact bytes that the Plugin Verifier checks and that the published
    // zip packages. The task first runs the checker --self-test, which feeds it
    // synthetic clean and banned jars, so a scanner regression fails this task. The
    // marker output makes the task up-to-date when the sandbox and the checker are
    // unchanged.
    register("verifyPackagedBytecode") {
        group = "verification"
        description =
            "Reject packaged references to terminal APIs removed in IntelliJ 2026.3 and to internal LocalPtyOptions API"
        val checker = layout.projectDirectory.file("smoke/Smoke263DispatchCheck.java")
        inputs.files(prepareSandbox.map { it.destinationDir })
        inputs.file(checker)
        outputs.file(layout.buildDirectory.file("verification/packaged-bytecode.ok"))
        dependsOn(prepareSandbox)
        doLast {
            processServices.execOperations.exec {
                commandLine(
                    verificationJavaLauncher.get().executablePath.asFile.absolutePath,
                    "-cp",
                    configurations.getByName("compileClasspath").asPath,
                    checker.asFile.absolutePath,
                    "--self-test",
                )
            }
            val pluginJar = packagedPluginJar()
            processServices.execOperations.exec {
                commandLine(
                    verificationJavaLauncher.get().executablePath.asFile.absolutePath,
                    "-cp",
                    configurations.getByName("compileClasspath").asPath,
                    checker.asFile.absolutePath,
                    "--scan-jar",
                    pluginJar.absolutePath,
                )
            }
            layout.buildDirectory.file("verification/packaged-bytecode.ok").get().asFile.writeText(pluginJar.path)
        }
    }
    named("check") {
        dependsOn("verifyPackagedBytecode")
    }

    // Headless 2026.3 proof that the Plugin Verifier cannot give: the platform entry
    // point createTtyConnector(ShellStartupOptions) exists and is overridable, the
    // override in TerminalInstance.createCustomRunner has the identical descriptor on
    // a direct LocalTerminalDirectRunner subclass so the virtual dispatch binds, no
    // packaged class references the removed createProcess descriptor, and
    // RawOutputTtyConnector decodes and forwards output when linked against this IDE
    // build's jediterm and pty4j.
    register("smoke263Dispatch") {
        group = "verification"
        description = "Prove the 2026.3 createTtyConnector dispatch against an unpacked ideaIC 263.7307.112"
        val checker = layout.projectDirectory.file("smoke/Smoke263DispatchCheck.java")
        val idea263Build = providers.gradleProperty("idea263Build").orElse("263.7307.112")
        val ideDir = providers.gradleProperty("smokeLocalIde")
            .orElse(layout.buildDirectory.dir(idea263Build.map { "ideaIC-$it" }).map { it.asFile.absolutePath })
        // The checker reads the first jar as bytes and loads the other five. Track only
        // this jar set so unrelated files in the unpacked IDE cannot invalidate the task.
        val ideJarPaths = listOf(
            "plugins/terminal/lib/terminal.jar",
            "plugins/pty4j-plugin/lib/modules/intellij.libraries.pty4j.jar",
            "lib/intellij.libraries.jediterm.core.jar",
            "lib/intellij.libraries.jediterm.ui.jar",
            "lib/util-8.jar",
            "lib/util_rt.jar",
        )
        inputs.files(prepareSandbox.map { it.destinationDir })
        inputs.file(checker)
        inputs.files(ideDir.map { ideDirectory -> ideJarPaths.map { File(ideDirectory, it) } })
        inputs.property("idea263Build", idea263Build)
        outputs.file(layout.buildDirectory.file("verification/smoke263-dispatch.ok"))
        dependsOn(prepareSandbox)
        doLast {
            val ideDirectory = file(ideDir.get())
            check(ideDirectory.isDirectory) {
                "Unpacked 2026.3 IDE not found at $ideDirectory. Unpack " +
                    "ideaIC-${idea263Build.get()}-EAP-SNAPSHOT.zip there or pass " +
                    "-PsmokeLocalIde=<directory>."
            }
            val ideJars = ideJarPaths.map { ideDirectory.resolve(it) }
            ideJars.forEach {
                check(it.isFile) {
                    "Expected 2026.3 IDE jar missing: $it. The ideaIC layout changed and the " +
                        "smoke checker needs updated jar locations."
                }
            }
            // The checker classpath holds only jars that the connector links against and
            // that ship as Java 8 or Java 11 bytecode. The 2026.3 platform classes are
            // Java 25 bytecode; the checker reads them as bytes and must never load them.
            // util-8 carries the platform Logger, AppExecutorUtil and the Kotlin runtime
            // that the connector needs at class-init time.
            val terminalJar = ideJars.first()
            val pluginJar = packagedPluginJar()
            val classpath = ideJars.drop(1) + pluginJar
            processServices.execOperations.exec {
                commandLine(
                    verificationJavaLauncher.get().executablePath.asFile.absolutePath,
                    "-cp",
                    classpath.joinToString(File.pathSeparator),
                    checker.asFile.absolutePath,
                    pluginJar.absolutePath,
                    terminalJar.absolutePath,
                )
            }
            val smokeMarker = layout.buildDirectory.file("verification/smoke263-dispatch.ok").get().asFile
            smokeMarker.writeText("${idea263Build.get()} ${pluginJar.absolutePath}")
        }
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

// Configure ktlint
ktlint {
    version.set("0.50.0")
    debug.set(false)
    verbose.set(true)
    android.set(false)
    outputToConsole.set(true)
    outputColorName.set("RED")
    ignoreFailures.set(false)
    enableExperimentalRules.set(false)
    filter {
        exclude("**/generated/**")
        include("**/kotlin/**")
    }
}

// Run unit tests without a display; Swing components used by the regression
// tests must initialize in headless mode
tasks.named<Test>("test") {
    systemProperty("java.awt.headless", "true")
}

// Configure detekt
detekt {
    toolVersion = "1.23.4"
    config.setFrom(file("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false

    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(true)
        sarif.required.set(true)
        md.required.set(true)
    }
}
