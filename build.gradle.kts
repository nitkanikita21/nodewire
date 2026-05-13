plugins {
    // ModDevGradle Legacy — successor to ForgeGradle 6, supports Forge 1.20.1.
    // Properly configures Mixin refmap remap (via modImplementation), unlike FG6.
    id("net.neoforged.moddev.legacyforge") version "2.0.141"
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    // Compose Compiler Gradle Plugin (standalone since Kotlin 2.0). Version
    // is the Kotlin version it pairs with — does not itself add a runtime dep.
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
val forgeVer: String by project
val kffVer: String by project

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
    maven("https://maven.neoforged.net/releases/")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    maven("https://maven.valkyrienskies.org/")
    maven("https://maven.createmod.net")
    maven("https://maven.ithundxr.dev/mirror")
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/")
    maven("https://maven.tterrag.com/")
    maven("https://maven.blamejared.com/")
    maven("https://maven.terraformersmc.com/")
    // JetBrains Compose dev maven (multiplatform compose-runtime, etc.)
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    // Google maven — Compose runtime transitively needs androidx.annotation
    google()
    mavenCentral()
}

legacyForge {
    enable {
        forgeVersion = "$mcVer-$forgeVer"
    }

    parchment {
        minecraftVersion.set("1.20.1")
        mappingsVersion.set("2023.09.03")
    }

    runs {
        register("client") {
            client()
            systemProperty("forge.logging.markers", "REGISTRIES")
            systemProperty("forge.logging.console.level", "debug")
        }
        register("server") {
            server()
            systemProperty("forge.logging.markers", "REGISTRIES")
            systemProperty("forge.logging.console.level", "debug")
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
        }
    }
}

// KFF bundles kotlin-stdlib at runtime. We must NOT let any other dep pull it onto
// the classpath again, or modlauncher complains about duplicate module exports.
// ModDev isolates the mod's JPMS module from KFF's module — compose-runtime and
// yoga (non-mod libs) can't see kotlin.* exported by KFF, and we can't put kotlin
// on additionalRuntimeClasspath without hitting JPMS duplicate-export errors.
// Solution: SHADE compose-runtime + yoga classes directly into the nodewire jar
// (and into the dev sourceSet output) so they're part of OUR module — they then
// transparently see KFF's kotlin-stdlib via cross-module exports.
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
    // Kotlin for Forge — language loader (KFF 4.12 needs Kotlin 2.2, so we pin 4.11)
    implementation("thedarkcolour:kotlinforforge:${kffVer}")

    // Valkyrien Skies 2 — physics & ship API.
    // `modImplementation` auto-remaps SRG → Mojang names via ModDevGradle.
    modImplementation("org.valkyrienskies:valkyrienskies-120-forge:2.4.10+a7a0898ae1")

    // Create 6.0.8 + transitive deps (per Create wiki's official dev recipe).
    // :slim excludes nested JarInJar mods so we control their versions explicitly.
    modImplementation("com.simibubi.create:create-1.20.1:6.0.8-289:slim")
    modImplementation("net.createmod.ponder:Ponder-Forge-1.20.1:1.0.91")
    modCompileOnly("dev.engine-room.flywheel:flywheel-forge-api-1.20.1:1.0.5")
    modRuntimeOnly("dev.engine-room.flywheel:flywheel-forge-1.20.1:1.0.5")
    modImplementation("com.tterrag.registrate:Registrate:MC1.20-1.3.3")
    implementation("io.github.llamalad7:mixinextras-forge:0.4.1")

    // JEI — recipe viewer (compile API + runtime impl)
    modCompileOnly("mezz.jei:jei-1.20.1-forge-api:15.20.0.129")
    modCompileOnly("mezz.jei:jei-1.20.1-common-api:15.20.0.129")
    modRuntimeOnly("mezz.jei:jei-1.20.1-forge:15.20.0.129")

    // EMI — alternative recipe viewer for testing
    modRuntimeOnly("dev.emi:emi-forge:1.1.22+1.20.1")

    // --- Compose UI framework ---
    // compose-runtime: tree composition + recomposition only (no Skiko, no compose.ui).
    // Excludes strip transitive Kotlin/kotlinx; KFF 4.11.0 already bundles kotlin-stdlib
    // and kotlinx-coroutines-core 1.8.1 at runtime, and we re-add coroutines below for
    // dev/test scope only.
    // Like yoga: needs both `implementation` (compile) and `additionalRuntimeClasspath`
    // (dev runtime) because ModDev isolates non-mod library classpaths.
    // Compile-only: compose-runtime classes are SHADED into our jar at runtime
    // (see `extractShadedLibs` task above). compileOnly avoids them being on the
    // classpath twice.
    compileOnly("org.jetbrains.compose.runtime:runtime:1.7.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    // Coroutines: compileOnly because KFF 4.11.0 bundles 1.8.1 at runtime; adding it
    // as `implementation` would put two copies on Forge's classpath. Tests need it
    // explicitly since KFF isn't on the test classpath.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Flexbox layout — pure Java, no native code. Vendored locally because the
    // Maven Central release of org.appliedenergistics.yoga:yoga:1.0.0 ships Java 21
    // bytecode and we're on Java 17. The jar in libs/ is built from the same upstream
    // (https://github.com/AppliedEnergistics/yoga, master @ v1.0.0) with the toolchain
    // patched to JavaLanguageVersion.of(17); no source changes (records compile fine
    // under Java 17). Build steps in docs/superpowers/notes/2026-05-13-yoga-rebuild.md.
    // Yoga: `implementation` for compile classpath, `additionalRuntimeClasspath` for
    // dev runs (ModDev isolates the mod's runtime classpath), `jarJar` for embedding
    // into the built jar so it's available in production. All three are needed for
    // local jars on ModDev legacyforge — see ModDevGradle README.
    // Yoga: compile-only because classes are SHADED into the mod jar via
    // `extractShadedLibs` (see top of this file). No `additionalRuntimeClasspath`
    // or `jarJar` needed.
    compileOnly(files("libs/yoga-1.0.0-j17.jar"))

    // Unit testing (Phase 1 smoke test for Yoga).
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Make Minecraft classes available to unit tests. The legacyForge plugin
// adds the MC artifact to `compileClasspath` only — graph-model tests use
// NBT classes (CompoundTag, ListTag) and ResourceLocation, all of which are
// plain Java that doesn't need a full MC bootstrap to instantiate.
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
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}
