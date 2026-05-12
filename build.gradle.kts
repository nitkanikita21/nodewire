import net.minecraftforge.gradle.userdev.UserDevExtension

plugins {
    id("net.minecraftforge.gradle") version "[6.0.16,6.2)"
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
    maven("https://maven.minecraftforge.net/")
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

configure<UserDevExtension> {
    mappings(mappingsChannel, mappingsVersion)

    runs {
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", "${projectDir}/build/createSrgToMcp/output.srg")
            // Mixin's RemappingReferenceMapper reads this legacy GradleStart property
            // for the SRG→MCP file path, not the modern mixin.env.* one.
            property("net.minecraftforge.gradle.GradleStart.srg.srg-mcp", "${projectDir}/build/createSrgToMcp/output.srg")
            mods { create(modId) { source(sourceSets["main"]) } }
        }
        create("server") {
            workingDirectory(project.file("run/server"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", "${projectDir}/build/createSrgToMcp/output.srg")
            property("net.minecraftforge.gradle.GradleStart.srg.srg-mcp", "${projectDir}/build/createSrgToMcp/output.srg")
            mods { create(modId) { source(sourceSets["main"]) } }
        }
    }
}

// kotlin-stdlib-common was merged into kotlin-stdlib in Kotlin 2.0+, but FG6's
// restricted classpath can't fetch it — exclude to avoid resolution errors.
configurations.all {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
}

dependencies {
    minecraft("net.minecraftforge:forge:${minecraftVersion}-${forgeVersion}")

    // Kotlin for Forge — language loader (KFF 4.12 needs Kotlin 2.2, so we pin 4.11)
    implementation("thedarkcolour:kotlinforforge:${kffVersion}")

    // Valkyrien Skies 2 — physics & ship API
    implementation(fg.deobf("org.valkyrienskies:valkyrienskies-120-forge:2.4.10+a7a0898ae1"))

    // Create 6.0.8 + transitive deps (per Create wiki's official dev recipe).
    // :slim excludes nested JarInJar mods so we control their versions explicitly.
    implementation(fg.deobf("com.simibubi.create:create-1.20.1:6.0.8-289:slim"))
    implementation(fg.deobf("net.createmod.ponder:Ponder-Forge-1.20.1:1.0.91"))
    compileOnly(fg.deobf("dev.engine-room.flywheel:flywheel-forge-api-1.20.1:1.0.5"))
    runtimeOnly(fg.deobf("dev.engine-room.flywheel:flywheel-forge-1.20.1:1.0.5"))
    implementation(fg.deobf("com.tterrag.registrate:Registrate:MC1.20-1.3.3"))
    implementation("io.github.llamalad7:mixinextras-forge:0.4.1")

    // JEI — recipe viewer (compile API + runtime impl)
    compileOnly(fg.deobf("mezz.jei:jei-1.20.1-forge-api:15.20.0.129"))
    compileOnly(fg.deobf("mezz.jei:jei-1.20.1-common-api:15.20.0.129"))
    runtimeOnly(fg.deobf("mezz.jei:jei-1.20.1-forge:15.20.0.129"))

    // EMI — alternative recipe viewer for testing
    runtimeOnly(fg.deobf("dev.emi:emi-forge:1.1.22+1.20.1"))

    // --- Compose UI framework (Phase 1 of dev/nitka/nodewire/ui) ---
    // compose-runtime: tree composition + recomposition only (no Skiko, no compose.ui).
    // Excludes strip transitive Kotlin/kotlinx; KFF 4.11.0 already bundles kotlin-stdlib
    // and kotlinx-coroutines-core 1.8.1 at runtime, and we re-add coroutines below for
    // dev/test scope only.
    implementation("org.jetbrains.compose.runtime:runtime:1.7.0") {
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
    implementation(files("libs/yoga-1.0.0-j17.jar"))

    // Unit testing (Phase 1 smoke test for Yoga).
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    archiveClassifier.set("")
    finalizedBy("reobfJar")
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}
