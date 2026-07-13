pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        // Shared (non-per-version) property in stonecutter.properties.toml -
        // declared here so every per-version node's build.gradle.kts can just
        // write `id("net.fabricmc.fabric-loom-remap")` without repeating the version.
        id("net.fabricmc.fabric-loom-remap") version "1.17-SNAPSHOT"
    }
}

plugins {
    // Multi-version tooling. Only Yarn-mapped versions (1.20.1-1.21.11) are in
    // scope - no mapping-namespace migration needed, so loom-back-compat
    // (which exists for the 26.1+ official-mappings transition) is skipped.
    id("dev.kikugie.stonecutter") version "0.9.6"
}

stonecutter {
    create(rootProject) {
        versions("1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6", "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11")
        vcsVersion = "1.20.1"
    }
}

rootProject.name = "campfyre"
