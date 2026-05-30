import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    // ModDevGradle (non-legacy) — for NeoForge 1.20.2+ / 1.21.x.
    // The .legacyforge plugin was for Forge 1.20.1 (now retired in master).
    // Versions are declared once in settings.gradle.kts pluginManagement so the
    // :scripting addon subproject can apply the same plugins without a version.
    id("net.neoforged.moddev")
    id("org.jetbrains.kotlin.jvm")
    // Compose Compiler Gradle Plugin pairs with the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose")
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
    maven("https://maven.squiddev.cc/")              // CC: Tweaked
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

// The optional :scripting addon is loaded into core's dev runs (neoForge.mods
// below) so `./gradlew runClient` exercises the Script Node with the full mod
// deps (Create/Sable/...) present. Force :scripting to evaluate first so its
// main sourceSet is available when the mods block reads it.
evaluationDependsOn(":scripting")

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
            // Fire the Script Node JPMS validation probe (see NodewireScripting) in dev.
            systemProperty("nodewire.scriptprobe", "true")
            // IDE-attached coroutines-debug agent retransforms kotlin.stdlib's
            // DebugProbesKt so it dispatches into kotlinx.coroutines.debug.internal
            // .DebugProbesImpl. Both live in KFF's PLUGIN layer as separate JPMS
            // modules; stdlib's module-info doesn't `requires` coroutines, so any
            // `launch{}` throws IllegalAccessError. JpmsBridge adds the reads edge
            // reflectively from an UNNAMED-module helper class; we just need
            // java.base/java.lang open to ALL-UNNAMED for setAccessible to pass.
            // The named-module flavor (`=nodewire`) is silently ignored anyway
            // because nodewire isn't in the boot layer at JVM startup.
            jvmArguments.add("--add-opens=java.base/java.lang=ALL-UNNAMED")
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
        // Optional addon, loaded alongside core in dev runs (cross-project
        // sourceSet — ModDevGradle puts its output on the dev mod classpath
        // without a project dependency, so there's no core -> :scripting cycle).
        register("nodewire_scripting") {
            sourceSet(project(":scripting").sourceSets.getByName("main"))
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
// our module and can see KFF's kotlin.* exports. Yoga is ALSO bundled by
// other mods (ldlib2, AE2-derivatives) — JPMS rejects two modules
// exporting the same package, so we ASM-relocate Yoga's package into
// dev.nitka.nodewire.shaded.yoga before shading. compose-runtime ships
// under androidx.compose.* which is not bundled elsewhere in the modpack
// ecosystem we target, so it stays in its original package.
val shadedLibs by configurations.creating

dependencies {
    shadedLibs("org.jetbrains.compose.runtime:runtime:1.7.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    shadedLibs(files("libs/yoga-1.0.0-j17.jar"))
}

// ASM is available via the buildscript classpath — ModDevGradle brings it
// in. We add it explicitly here too as a belt-and-braces, since we use
// the asm-commons SimpleRemapper / ClassRemapper from the build script
// body (not from a task class).
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.ow2.asm:asm:9.7.1")
        classpath("org.ow2.asm:asm-commons:9.7.1")
    }
}

val yogaOriginalPath = "org/appliedenergistics/yoga"
val yogaRelocatedPath = "dev/nitka/nodewire/shaded/yoga"

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

// After `jar` packs everything (our classes + shaded yoga + compose-runtime),
// ASM-relocate every reference to org.appliedenergistics.yoga.* into
// dev.nitka.nodewire.shaded.yoga.*. That way our jar exports the latter
// path (private to us) instead of the former (also exported by ldlib2's
// jarjar'd yoga and other AE2-derived mods), and JPMS no longer rejects
// the modpack with a split-package error.
//
// Done as an in-place rewrite of the final jar so both shaded yoga classes
// AND our own classes (which import org.appliedenergistics.yoga directly
// in source) end up referencing the same renamed package.
tasks.named<Jar>("jar") {
    doLast {
        val src = archiveFile.get().asFile
        val tmp = File(src.parentFile, src.name + ".tmp")
        // SimpleRemapper only does exact-match — to rename every class
        // under a package prefix we need a custom Remapper that replaces
        // the prefix anywhere it appears in an internal name.
        val remapper = object : org.objectweb.asm.commons.Remapper() {
            override fun map(internalName: String): String =
                if (internalName.startsWith("$yogaOriginalPath/"))
                    yogaRelocatedPath + internalName.removePrefix(yogaOriginalPath)
                else internalName
        }
        ZipFile(src).use { zip ->
            ZipOutputStream(tmp.outputStream()).use { out ->
                val entries = zip.entries().toList()
                for (e in entries) {
                    val outName = if (e.name.startsWith("$yogaOriginalPath/")) {
                        yogaRelocatedPath + e.name.removePrefix(yogaOriginalPath)
                    } else e.name
                    out.putNextEntry(ZipEntry(outName))
                    zip.getInputStream(e).use { input ->
                        if (e.name.endsWith(".class")) {
                            // Disambiguate: use the byte-array constructor.
                            val bytes: ByteArray = input.readBytes()
                            val reader = org.objectweb.asm.ClassReader(bytes)
                            val writer = org.objectweb.asm.ClassWriter(0)
                            reader.accept(
                                org.objectweb.asm.commons.ClassRemapper(writer, remapper),
                                0,
                            )
                            out.write(writer.toByteArray())
                        } else {
                            input.copyTo(out)
                        }
                    }
                    out.closeEntry()
                }
            }
        }
        src.delete()
        tmp.renameTo(src)
    }
}

dependencies {
    // Kotlin for NeoForge — language loader on NeoForge.
    implementation("thedarkcolour:kotlinforforge-neoforge:${kffVer}")

    // NOTE: NeoForge mod jars are pre-Mojang-mapped — use standard
    // implementation / compileOnly / runtimeOnly (no modImplementation,
    // that was a legacyForge-only DSL to trigger SRG → Mojang remap).

    // --- Sable Companion — compile-time API + safe no-op defaults ---
    // Artifact ID embeds the MC version: -common-1.21.1, latest 1.6.0. Without
    // Sable installed, SableCompanion.INSTANCE returns no-op stubs.
    compileOnly("dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0")

    // --- Sable itself — runtime, required by Create Aeronautics anyway ---
    // Aeronautics declares Sable as a hard dependency, so the dev runtime has to
    // ship Sable; pinning 1.2.2 (latest 1.21.1 NeoForge build). Sable replaces
    // the Companion impl via Gradle capability resolution, so live sub-level
    // queries actually return real data at runtime.
    runtimeOnly("maven.modrinth:sable:1.2.2+mc1.21.1")

    // --- Create Aeronautics 1.2.1 (via Modrinth maven) ---
    // Curse Maven rejects this project with 403 (monetized status on
    // CurseForge), so we pull from Modrinth instead. Aircraft built by
    // Aeronautics ARE Sable sub-levels, so they're already claimed
    // transparently by SableSubLevelBackend — no additional backend code
    // needed for v1. The dep isr kept compileOnly + runtimeOnly so we can
    // call into Aeronautics-specific APIs later (signal sources on
    // aircraft, propeller hooks, etc.) without making it a hard dep.
    compileOnly("maven.modrinth:create-aeronautics:1.2.1+mc1.21.1")
    runtimeOnly("maven.modrinth:create-aeronautics:1.2.1+mc1.21.1")

    // --- Create: Tweaked Controllers 1.2.7 (NeoForge 1.21.1) ---
    // Project id 898849, file id 7958165 (Apr 20 2026 NeoForge build).
    compileOnly("curse.maven:create-tweaked-controllers-898849:7958165")
    runtimeOnly("curse.maven:create-tweaked-controllers-898849:7958165")

    // CC: Tweaked — peripheral integration (mod gated at runtime; module
    // compiles against the jar so source references resolve). squiddev's
    // -forge artifact is the NeoForge-compatible build for 1.21.1 (ships a
    // neoforge.mods.toml-style descriptor and references
    // net.neoforged.neoforge.capabilities.BlockCapability via
    // dan200.computercraft.api.peripheral.PeripheralCapability).
    val ccVer = "1.119.0"
    compileOnly("cc.tweaked:cc-tweaked-${mcVer}-forge:${ccVer}")
    runtimeOnly("cc.tweaked:cc-tweaked-${mcVer}-forge:${ccVer}")
    testImplementation("cc.tweaked:cc-tweaked-${mcVer}-forge:${ccVer}")

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

    // NOTE: the Kotlin-compiler-backed script backend lives in the optional
    // `:scripting` addon module — core only declares the `ScriptCompiler` SPI
    // (dev.nitka.nodewire.script.ScriptCompiler) and never references
    // kotlin.script.experimental.* directly.

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
