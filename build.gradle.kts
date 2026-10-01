import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.jvm")
    id("rpc") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("dev.detekt") apply false
}

group = "com.forketyfork"
version = providers.gradleProperty("pluginVersion").get()

val intellijPlatformVersion = providers.gradleProperty("intellijPlatformVersion").get()

fun latestChangelog(): String {
    val changelog = file("CHANGELOG.md")
    if (!changelog.exists()) return "Initial version"
    val lines = changelog.readLines()
    val start = lines.indexOfFirst { it.matches(Regex("""^## \[\d+.*""")) }
    if (start == -1) return "Initial version"
    val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## [") }
    val section = if (end == -1) lines.drop(start + 1) else lines.subList(start + 1, start + 1 + end)
    return section.joinToString("\n").trim().ifEmpty { "Initial version" }
}

subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")
    apply(plugin = "rpc")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "dev.detekt")

    group = rootProject.group
    version = rootProject.version

    // The platform loads content module `walkthrough.<x>` from `lib/modules/walkthrough.<x>.jar`,
    // so each composed module jar must be named after its descriptor (e.g. backend-mcp -> walkthrough.backend.mcp).
    tasks.withType<ComposedJarTask>().configureEach {
        archiveFileName.set("walkthrough.${project.name.replace('-', '.')}.jar")
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        "compileOnly"(libs.findLibrary("kotlinx-serialization-core-jvm").get())
        "compileOnly"(libs.findLibrary("kotlinx-serialization-json-jvm").get())

        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testImplementation"(libs.findLibrary("opentest4j").get())
        "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
        "testRuntimeOnly"(libs.findLibrary("junit4").get())

        "detektPlugins"(libs.findLibrary("detekt-rules-ktlint-wrapper").get())
        "detektPlugins"(libs.findLibrary("detekt-compose-rules").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // The JVM target follows the IntelliJ Platform (Java 25 for 2026.2); IPGP configures it.

    extensions.configure<DetektExtension> {
        source.setFrom("src/main/kotlin", "src/test/kotlin")
        parallel = true
        config.setFrom(rootProject.files("detekt.yml"))
        buildUponDefaultConfig = true
        basePath.set(projectDir)
    }

    tasks.withType<Detekt>().configureEach {
        reports {
            sarif.required.set(true)
            markdown.required.set(true)
        }
    }

    // The default `:detekt` task runs without type resolution, which silences rules like
    // `UnnecessaryFullyQualifiedName`, `IgnoredReturnValue`, `UselessCallOnNotNull`, etc. Wire the
    // type-resolving per-source-set tasks into the aggregate `detekt` task so `just lint` / CI pick
    // them up without changing entry points.
    tasks.matching { it.name == "detekt" }.configureEach {
        dependsOn("detektMain", "detektTest")
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(intellijPlatformVersion)

        pluginModule(implementation(project(":shared")))
        pluginModule(implementation(project(":frontend")))
        pluginModule(implementation(project(":backend")))
        pluginModule(implementation(project(":backend-mcp")))
    }
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }

        changeNotes = latestChangelog()
    }

    caching {
        ides {
            enabled.set(true)
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// `runIde` stays a classic monolithic IDE (frontend and backend modules in one JVM);
// `runIdeSplitMode` / `runIdeBackend` / `runIdeFrontend` run the Host + JetBrains Client pair.
tasks.named<RunIdeTask>("runIde") {
    splitMode = false
}
