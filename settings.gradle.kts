@file:Suppress("UnstableApiUsage")

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }

    // Gradle can't read the version catalog inside pluginManagement, so these stay literal.
    // Keep Kotlin, the Compose compiler plugin and the serialization plugin on the Kotlin version
    // the `rpc` plugin is built for (2.4.0-RC-0.1 -> Kotlin 2.4.0, the IntelliJ 2026.2 line).
    plugins {
        id("rpc") version "2.4.0-RC-0.1"
        id("org.jetbrains.kotlin.jvm") version "2.4.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
        id("dev.detekt") version "2.0.0-alpha.6"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

rootProject.name = "walkthrough-plugin"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include("shared")
include("frontend")
include("backend")
include("backend-mcp")
