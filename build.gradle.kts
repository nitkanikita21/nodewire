plugins {
    // ModDevGradle (non-legacy) — for NeoForge 1.20.2+ / 1.21.x.
    // The .legacyforge plugin was for Forge 1.20.1 (now retired in master).
    id("net.neoforged.moddev") version "2.0.141"
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    // Compose Compiler Gradle Plugin pairs with the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    `java-library`
    idea
}

val modId: String by project
val modName: String by project
val modVersion: String by project
val modGroup: String by project
val modAuthors: String by project
val modDescription: String by project

val mcVer: String by project
val neoForgeVer: String by project
val kffVer: String by project

group = modGroup
version = modVersion
base.archivesName.set(modId)

java {
    // NeoForge 1.21.1 requires Java 21.
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

repositories {
    maven("https://maven.neoforged.net/releases/")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    maven("https://maven.createmod.net")             // Create, Ponder, Flywheel
    maven("https://maven.ithundxr.dev/snapshots")    // Registrate (1.21.x is under snapshots maven)
    maven("https://maven.architectury.dev/")         // Architectury (transitive via Create)
    maven("https://maven.blamejared.com/")           // JEI
    maven("https://maven.terraformersmc.com/")       // EMI
    maven("https://maven.ryanhcode.dev/releases")    // Sable
    // JetBrains Compose dev maven (multiplatform compose-runtime, etc.)
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    // Google maven — Compose runtime transitively needs androidx.annotation
    google()
    mavenCentral()
    // Modrinth maven — fallback for mods only published there.
    exclusiveContent {
        forRepository { maven { url = uri("https://api.modrinth.com/maven") } }
        filter { includeGroup("maven.modrinth") }
    }
    // Curse Maven — for Create Aeronautics (not on independent maven).
    exclusiveContent {
        forRepository { maven { url = uri("https://www.cursemaven.com") } }
        filter { includeGroup("curse.maven") }
    }
}

neoForge {
    version = neoForgeVer

    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }

    validateAccessTransformers = true

    runs {
        register("client") {
            client()
            systemProperty("forge.logging.markers", "REGISTRIES")
            systemProperty("forge.logging.console.level", "debug")
            // JPMS isolates kotlin.stdlib (bundled by KFF) from kotlinx.coroutines.core
            // (separate module). The stdlib's DebugProbesKt.probeCoroutineCreated does a
            // direct call into kotlinx.coroutines.debug.internal.DebugProbesImpl on every
            // `launch {}` — IllegalAccessError at JVM module-resolution time, not at
            // runtime opt-in. --add-reads explicitly grants stdlib read access to
            // coroutines so the bridge resolves.
            jvmArguments.add("--add-reads=kotlin.stdlib=kotlinx.coroutines.core")
        }
        register("server") {
            server()
            systemProperty("forge.logging.markers", "REGISTRIES")
            systemProperty("forge.logging.console.level", "debug")
            jvmArguments.add("--add-reads=kotlin.stdlib=kotlinx.coroutines.core")
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
        }
    }
}

// Create 6.0.10-280's POM declares a transitive dependency on
// `dev.architectury:architectury-neoforge:13d.0.8`, which doesn't exist on
// any maven (the real artifact is `13.0.8`, no `d`). Substitute it so
// Gradle can resolve.
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("dev.architectury:architectury-neoforge:13d.0.8"))
            .using(module("dev.architectury:architectury-neoforge:13.0.8"))
            .because("Create 6.0.10-280 POM has a typo'd Architectury version")
    }
}

// KFF bundles kotlin-stdlib at runtime — same JPMS constraint as before.
// compose-runtime + yoga are still SHADED into the mod jar so they live in
// our module and can see KFF's kotlin.* exports.
val shadedLibs by configurations.creating

dependencies {
    shadedLibs("org.jetbrains.compose.runtime:runtime:1.7.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    shadedLibs(files("libs/yoga-1.0.0-j17.jar"))
}

val extractedShadedLibs = layout.buildDirectory.dir("shadedLibs")

val extractShadedLibs = tasks.register<Sync>("extractShadedLibs") {
    from({ shadedLibs.map { zipTree(it) } })
    into(extractedShadedLibs)
    exclude("META-INF/MANIFEST.MF", "META-INF/maven/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

sourceSets.named("main") {
    output.dir(mapOf("builtBy" to extractShadedLibs), extractedShadedLibs)
}

dependencies {
    // Kotlin for NeoForge — language loader on NeoForge.
    implementation("thedarkcolour:kotlinforforge-neoforge:${kffVer}")

    // NOTE: NeoForge mod jars are pre-Mojang-mapped — use standard
    // implementation / compileOnly / runtimeOnly (no modImplementation,
    // that was a legacyForge-only DSL to trigger SRG → Mojang remap).

    // --- Sable Companion — safe-default API stubs that Sable replaces at runtime ---
    // The companion artifact provides the SableCompanion.INSTANCE singleton with
    // no-op defaults when Sable itself isn't installed; when Sable is present, it
    // overrides the impl via service-loader. Compiling/running against Companion
    // alone is sufficient for both the modded-with-Sable and vanilla-NeoForge case.
    // Artifact ID embeds the MC version. 1.21.1 → -common-1.21.1, latest 1.6.0.
    implementation("dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0")

    // --- Create Aeronautics 1.2.1 (via Modrinth maven) ---
    // Curse Maven rejects this project with 403 (monetized status on
    // CurseForge), so we pull from Modrinth instead. Aircraft built by
    // Aeronautics ARE Sable sub-levels, so they're already claimed
    // transparently by SableSubLevelBackend — no additional backend code
    // needed for v1. The dep is kept compileOnly + runtimeOnly so we can
    // call into Aeronautics-specific APIs later (signal sources on
    // aircraft, propeller hooks, etc.) without making it a hard dep.
    compileOnly("maven.modrinth:create-aeronautics:1.2.1+mc1.21.1")
    runtimeOnly("maven.modrinth:create-aeronautics:1.2.1+mc1.21.1")

    // --- Create: Tweaked Controllers 1.2.7 (NeoForge 1.21.1) ---
    // Project id 898849, file id 7958165 (Apr 20 2026 NeoForge build).
    compileOnly("curse.maven:create-tweaked-controllers-898849:7958165")
    runtimeOnly("curse.maven:create-tweaked-controllers-898849:7958165")

    // --- Create 6.0.10 for NeoForge 1.21.1 + transitive deps ---
    // :slim + isTransitive = false: skip Create's POM-declared optional deps
    // (CC:Tweaked, Architectury, etc.) so we control versions explicitly.
    // Per the official Create wiki recipe for 1.21.1.
    implementation("com.simibubi.create:create-${mcVer}:6.0.10-280:slim") {
        isTransitive = false
    }
    implementation("net.createmod.ponder:ponder-neoforge:1.0.82+mc${mcVer}")
    compileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-${mcVer}:1.0.6")
    runtimeOnly("dev.engine-room.flywheel:flywheel-neoforge-${mcVer}:1.0.6")
    implementation("com.tterrag.registrate:Registrate:MC1.21-1.3.0+67")

    // MixinExtras — NeoForge variant.
    implementation("io.github.llamalad7:mixinextras-neoforge:0.4.1")
    compileOnly("io.github.llamalad7:mixinextras-common:0.4.1")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.4.1")

    // JEI — recipe viewer (NeoForge variant for 1.21.1)
    compileOnly("mezz.jei:jei-${mcVer}-neoforge-api:19.21.0.247")
    compileOnly("mezz.jei:jei-${mcVer}-common-api:19.21.0.247")
    runtimeOnly("mezz.jei:jei-${mcVer}-neoforge:19.21.0.247")

    // EMI — NeoForge variant for 1.21.1
    compileOnly("dev.emi:emi-neoforge:1.1.18+${mcVer}")
    runtimeOnly("dev.emi:emi-neoforge:1.1.18+${mcVer}")

    // --- Compose UI framework (unchanged) ---
    compileOnly("org.jetbrains.compose.runtime:runtime:1.7.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Yoga — locally vendored. NOTE: NeoForge 1.21.1 runs on Java 21, so
    // the j17 rebuild is no longer required — the upstream Java-21 jar
    // would work. We keep libs/yoga-1.0.0-j17.jar for now to minimize
    // simultaneous changes; can switch to the upstream artifact later.
    compileOnly(files("libs/yoga-1.0.0-j17.jar"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Make Minecraft classes available to unit tests (same trick as on Forge).
configurations.named("testCompileClasspath").configure {
    extendsFrom(configurations.compileClasspath.get())
}
configurations.named("testRuntimeClasspath").configure {
    extendsFrom(configurations.runtimeClasspath.get())
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<ProcessResources>("processResources") {
    val replacements = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to modVersion,
        "mod_authors" to modAuthors,
        "mod_description" to modDescription,
        "minecraft_version_range" to project.property("minecraft_version_range") as String,
        "neoforge_version_range" to project.property("neoforge_version_range") as String,
        "loader_version_range" to project.property("loader_version_range") as String,
        "kff_version_range" to project.property("kff_version_range") as String,
        "create_version_range" to project.property("create_version_range") as String,
    )
    inputs.properties(replacements)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta")) {
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
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}
