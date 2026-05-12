pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
        maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin for Forge" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "nodewire"
