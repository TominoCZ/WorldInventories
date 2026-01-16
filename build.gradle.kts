import org.gradle.internal.os.OperatingSystem
import org.jetbrains.gradle.ext.Application
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings

repositories {
   mavenCentral()
}

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.3"
}

val java_version: String by project
val patchline: String by project
val includes_pack: String by project
val load_user_mods: String by project

val hytaleHome: String = if (project.hasProperty("hytale_home")) {
    project.property("hytale_home") as String
} else {
    val os = OperatingSystem.current()
    when {
        os.isWindows -> "E:/Games/Hytale"
        os.isMacOsX -> "${System.getProperty("user.home")}/Library/Application Support/Hytale"
        os.isLinux -> {
            val flatpakPath = "${System.getProperty("user.home")}/.var/app/com.hypixel.HytaleLauncher/data/Hytale"
            if (file(flatpakPath).exists()) flatpakPath
            else "${System.getProperty("user.home")}/.local/share/Hytale"
        }
        else -> throw GradleException("Your Hytale install could not be detected automatically. If you are on an unsupported platform or using a custom install location, please define the install location using the hytale_home property.")
    }
}

if (!file(hytaleHome).exists()) {
    throw GradleException("Failed to find Hytale at the expected location. Please make sure you have installed the game. The expected location can be changed using the hytale_home property. Currently looking in $hytaleHome")
}

kotlin {
    jvmToolchain(java_version.toInt())
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(java_version))
    }
    withSourcesJar()
    withJavadocJar()
}

// Quiet warnings about missing Javadocs.
tasks.withType<Javadoc> {
    options {
        (this as StandardJavadocDocletOptions).addStringOption("Xdoclint:-missing", "-quiet")
    }
}

// Adds the Hytale server as a build dependency, allowing you to reference and
// compile against their code. This requires you to have Hytale installed using
// the official launcher for now.
dependencies {
    compileOnly(files("$hytaleHome/install/$patchline/package/game/latest/Server/HytaleServer.jar"))
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

// Configure JAR to bundle Kotlin stdlib
tasks.jar {
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Create the working directory to run the server if it does not already exist.
val serverRunDir = file("$projectDir/run").apply {
    if (!exists()) {
        mkdirs()
    }
}

// Updates the manifest.json file with the latest properties defined in the
// build.properties file. Currently we update the version and if packs are
// included with the plugin.
val updatePluginManifest by tasks.registering {
    val manifestFile = file("src/main/resources/manifest.json")
    doLast {
        if (!manifestFile.exists()) {
            throw GradleException("Could not find manifest.json at ${manifestFile.path}!")
        }
        @Suppress("UNCHECKED_CAST")
        val manifestJson = groovy.json.JsonSlurper().parse(manifestFile) as MutableMap<String, Any>
        manifestJson["Version"] = version
        manifestJson["IncludesAssetPack"] = includes_pack.toBoolean()
        manifestFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(manifestJson)))
    }
}

// Makes sure the plugin manifest is up to date.
tasks.named("processResources") {
    dependsOn(updatePluginManifest)
}

fun createServerRunArguments(srcDir: String): String {
    var programParameters = "--allow-op --disable-sentry --assets=\"$hytaleHome/install/$patchline/package/game/latest/Assets.zip\""
    val modPaths = mutableListOf<String>()
    if (includes_pack.toBoolean()) {
        modPaths.add(srcDir)
    }
    if (load_user_mods.toBoolean()) {
        modPaths.add("$hytaleHome/UserData/Mods")
    }
    if (modPaths.isNotEmpty()) {
        programParameters += " --mods=\"${modPaths.joinToString(",")}\""
    }
    return programParameters
}

// Creates a run configuration in IDEA that will run the Hytale server with
// your plugin and the default assets.
idea.project.settings.runConfigurations {
    create<Application>("HytaleServer") {
        mainClass = "com.hypixel.hytale.Main"
        moduleName = "${project.idea.module.name}.main"
        programParameters = createServerRunArguments(sourceSets.main.get().kotlin.srcDirs.first().parentFile.absolutePath)
        workingDirectory = serverRunDir.absolutePath
    }
}

// Creates a launch.json file for VSCode with the same configuration
val generateVSCodeLaunch by tasks.registering {
    val vscodeDir = file("$projectDir/.vscode")
    val launchFile = file("$vscodeDir/launch.json")
    doLast {
        if (!vscodeDir.exists()) {
            vscodeDir.mkdirs()
        }
        val programParams = createServerRunArguments("\${workspaceFolder}")
        val launchConfig = mapOf(
            "version" to "0.2.0",
            "configurations" to listOf(
                mapOf(
                    "type" to "java",
                    "name" to "HytaleServer",
                    "request" to "launch",
                    "mainClass" to "com.hypixel.hytale.Main",
                    "args" to programParams,
                    "cwd" to "\${workspaceFolder}/run"
                )
            )
        )
        launchFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(launchConfig)))
    }
}
