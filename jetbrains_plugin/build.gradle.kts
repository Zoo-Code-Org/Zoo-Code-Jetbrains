// Copyright 2009-2025 Weibo, Inc.
// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: APACHE2.0
// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.intellij.tasks.RunPluginVerifierTask

// Convenient for reading variables from gradle.properties
fun properties(key: String) = providers.gradleProperty(key)

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.8.10"
    id("org.jetbrains.intellij") version "1.13.3"
    id("org.jlleitschuh.gradle.ktlint") version "11.6.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.4"
}

apply(from = "genPlatform.gradle")

// Use Java/Kotlin toolchains instead of per-task source/target
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

// Toolchain launcher used by verification tasks that start a plain JVM.
val verificationJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
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
//    - Requires platform.zip to exist, which can be retrieved via git-lfs or generated with genPlatform.gradle.
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

    val sandboxDir = intellij.pluginName.get()

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

        // The platform.zip file required for release mode is associated with the code in ../base/vscode, currently using version 1.100.0. If upgrading this code later
        // Need to modify the vscodeVersion value in gradle.properties, then execute the task named genPlatform, which will generate a new platform.zip file for submission
        // To support new architectures, modify according to the logic in genPlatform.gradle script
        if (debugMode == "release") {
            // Check if platform.zip file exists and is larger than 1MB, otherwise throw exception
            val platformZip = File("platform.zip")
            if (platformZip.exists() && platformZip.length() >= 1024 * 1024) {
                // Extract platform.zip to the platform subdirectory under the project build directory
                val platformDir = File("${project.buildDir}/platform")
                platformDir.mkdirs()
                logger.lifecycle("[prepareSandbox] Extracting platform.zip -> ${platformDir.absolutePath}")
                copy {
                    from(zipTree(platformZip))
                    into(platformDir)
                }
                copyOps.add(platformZip.absolutePath to platformDir.absolutePath)
            } else {
                throw IllegalStateException("platform.zip file does not exist or is smaller than 1MB. This file is supported through git lfs and needs to be obtained through git lfs")
            }

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
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.4")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    pluginName.set("ZooCode")
    version.set(properties("platformVersion"))
    type.set(properties("platformType"))

    plugins.set(
        listOf(
            "com.intellij.java",
            // Add JCEF support
            "org.jetbrains.plugins.terminal"
        )
    )
}

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
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    patchPluginXml {
        version.set(properties("pluginVersion"))
        sinceBuild.set(properties("pluginSinceBuild"))
        untilBuild.set("")
    }

    // One binary must keep 233 support and stay compatible with the 2026.3 terminal
    // changes. Verify against the newest supported platform; failures are limited to
    // hard compatibility problems so deprecated-API notices do not hide them.
    //
    // The gradle-intellij 1.x task resolves ideVersions from release feeds only, and
    // IC-263.5701.42 (idea263Build property) is an EAP build that no feed lists. CI
    // (release.yml) downloads the exact build and passes it as
    // -PverifierLocalIde=<unpacked IDE directory>. Local runs have two clear paths:
    //   ./gradlew runPluginVerifier -PverifierLocalIde=<unpacked ideaIC-263.5701.42 dir>
    //   ./gradlew runPluginVerifier -PverifierIdeVersion=IC-<release feed build>
    runPluginVerifier {
        // Verifier releases: https://repo1.maven.org/maven2/org/jetbrains/intellij/plugins/verifier-cli/
        verifierVersion.set("1.410")
        val idea263Build = providers.gradleProperty("idea263Build").orElse("263.5701.42")
        val explicitVersions = providers.gradleProperty("verifierIdeVersion").orNull
        ideVersions.set(listOf(explicitVersions ?: "IC-${idea263Build.get()}"))
        failureLevel.set(
            listOf(
                RunPluginVerifierTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                RunPluginVerifierTask.FailureLevel.INVALID_PLUGIN
            )
        )
        val localIde = providers.gradleProperty("verifierLocalIde").orNull
        if (localIde != null) {
            ideVersions.set(emptyList())
            localPaths.set(listOf(file(localIde)))
        } else if (explicitVersions == null) {
            doFirst {
                throw GradleException(
                    "runPluginVerifier needs an IDE target. IC-${idea263Build.get()} is an EAP build that " +
                        "gradle-intellij cannot resolve from release feeds. Download " +
                        "ideaIC-${idea263Build.get()}-EAP-SNAPSHOT.zip, unpack it, and pass " +
                        "-PverifierLocalIde=<unpacked directory> (this is what release.yml does for CI), " +
                        "or verify a release feed build with -PverifierIdeVersion=IC-<version>.",
                )
            }
        }
    }

    // Regression gate: a packaged method reference to LocalTerminalDirectRunner
    // .createProcess(ShellStartupOptions) breaks the same binary on IntelliJ 2026.3,
    // where that method returns java.lang.Process instead of PtyProcess, so a 2023.3
    // call site does not resolve. The 2023.3 path and the 2026.3 path both start the
    // process through TerminalInstance.startTerminalProcess.
    //
    // The packaged plugin jar inside the sandbox: instrumentation may rename it with
    // an "instrumented-" prefix, and patchPluginXml writes the release descriptor into
    // exactly this copy.
    fun packagedPluginJar(): File {
        val archiveName = jar.get().archiveFileName.get()
        val libDir = File(File(prepareSandbox.get().destinationDir, intellij.pluginName.get()), "lib")
        val candidates = libDir.listFiles { file -> file.name.endsWith(archiveName) }
            ?: throw GradleException("Sandbox lib directory not found: $libDir")
        return candidates.firstOrNull { it.name.startsWith("instrumented-") }
            ?: candidates.firstOrNull()
            ?: throw GradleException("No packaged plugin jar ($archiveName) found in $libDir")
    }

    // The scan delegates to smoke/Smoke263DispatchCheck.java --scan-jar so the banned
    // reference list and the constant-pool parsing live in one place. The checker is
    // linked against the 233 platform jars already on the compile classpath; this task
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
            project.exec {
                commandLine(
                    verificationJavaLauncher.get().executablePath.asFile.absolutePath,
                    "-cp",
                    configurations.getByName("compileClasspath").asPath,
                    checker.asFile.absolutePath,
                    "--self-test",
                )
            }
            val pluginJar = packagedPluginJar()
            project.exec {
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
        description = "Prove the 2026.3 createTtyConnector dispatch against an unpacked ideaIC 263.5701.42"
        val checker = layout.projectDirectory.file("smoke/Smoke263DispatchCheck.java")
        val idea263Build = providers.gradleProperty("idea263Build").orElse("263.5701.42")
        val ideDir = providers.gradleProperty("smokeLocalIde")
            .orElse(layout.buildDirectory.dir(idea263Build.map { "ideaIC-$it" }).map { it.asFile.absolutePath })
        // The checker reads the first jar as bytes and loads the other five. Track only
        // this jar set so unrelated files in the unpacked IDE cannot invalidate the task.
        val ideJarPaths = listOf(
            "plugins/terminal/lib/terminal.jar",
            "lib/intellij.libraries.pty4j.jar",
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
            project.exec {
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
