import net.minecraftforge.gradle.userdev.UserDevExtension

plugins {
    id("net.minecraftforge.gradle") version "[6.0.16,6.2)"
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    `java-library`
    idea
}

val modId: String by project
val modName: String by project
val modVersion: String by project
val modGroup: String by project
val modAuthors: String by project
val modDescription: String by project

val minecraftVersion: String by project
val forgeVersion: String by project
val mappingsChannel: String by project
val mappingsVersion: String by project
val kffVersion: String by project

group = modGroup
version = modVersion
base.archivesName.set(modId)

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

kotlin {
    jvmToolchain(17)
}

repositories {
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
    maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin for Forge" }
    maven("https://maven.valkyrienskies.org/") { name = "Valkyrien Skies" }
    maven("https://maven.tterrag.com/") { name = "tterrag (Create)" }
    maven("https://maven.blamejared.com/") { name = "BlameJared (JEI, Jade)" }
    maven("https://maven.architectury.dev/") { name = "Architectury" }
    maven("https://cursemaven.com") {
        name = "CurseMaven"
        content { includeGroup("curse.maven") }
    }
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
    mavenCentral()
}

configure<UserDevExtension> {
    mappings(mappingsChannel, mappingsVersion)

    runs {
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            property("forge.enabledGameTestNamespaces", modId)
            mods { create(modId) { source(sourceSets["main"]) } }
        }
        create("server") {
            workingDirectory(project.file("run/server"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            mods { create(modId) { source(sourceSets["main"]) } }
        }
    }
}

dependencies {
    "minecraft"("net.minecraftforge:forge:${minecraftVersion}-${forgeVersion}")

    // Kotlin for Forge — Kotlin runtime + entrypoint loader
    implementation("thedarkcolour:kotlinforforge:${kffVersion}")

    // Valkyrien Skies 2 — hard runtime dep, used at compile-time for ship APIs
    // (exact coordinate confirmed on first build; cursemaven fallback below if needed)
    implementation(fg.deobf("org.valkyrienskies:valkyrienskies-120-forge:2.4.10+a7a0898ae1"))

    // Create — hard runtime dep
    // Try tterrag first; if 6.0.8 isn't published, swap to the cursemaven coordinate
    implementation(fg.deobf("com.simibubi.create:create-1.20.1:0.5.1.j-all"))
    // Fallback (uncomment if above unresolvable):
    // implementation(fg.deobf("curse.maven:create-328085:5667133"))

    // JEI — compile-only, for future integration
    compileOnly(fg.deobf("mezz.jei:jei-1.20.1-forge-api:15.20.0.106"))
    compileOnly(fg.deobf("mezz.jei:jei-1.20.1-common-api:15.20.0.106"))
    runtimeOnly(fg.deobf("mezz.jei:jei-1.20.1-forge:15.20.0.106"))

    // Jade — compile-only, for tooltips later
    // compileOnly(fg.deobf("curse.maven:jade-324717:5444008"))
}

tasks.named<ProcessResources>("processResources") {
    val replacements = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to modVersion,
        "mod_authors" to modAuthors,
        "mod_description" to modDescription,
        "minecraft_version_range" to project.property("minecraft_version_range") as String,
        "forge_version_range" to project.property("forge_version_range") as String,
        "loader_version_range" to project.property("loader_version_range") as String,
        "kff_version_range" to project.property("kff_version_range") as String,
        "vs_version_range" to project.property("vs_version_range") as String,
        "create_version_range" to project.property("create_version_range") as String,
    )
    inputs.properties(replacements)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) {
        expand(replacements)
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Specification-Title" to modId,
            "Specification-Vendor" to modAuthors,
            "Specification-Version" to "1",
            "Implementation-Title" to modName,
            "Implementation-Version" to modVersion,
            "Implementation-Vendor" to modAuthors,
        )
    }
    archiveClassifier.set("")
    finalizedBy("reobfJar")
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}
