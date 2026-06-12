// nodewire_scripting — optional addon mod.
//
// It exists solely to carry the Kotlin compiler (kotlin-scripting /
// kotlin-compiler-embeddable, ~50-90 MB) so the core `nodewire` mod stays
// small. Core declares the `ScriptCompiler` SPI + a registry; this module
// implements it (ScriptHost) and registers itself on mod init. When this
// addon is absent, core's Script Node is read-only.
//
// The compiler is NOT shaded into the module (that breaks under NeoForge's
// union:// resource filesystem). Instead it is bundled as REAL jars under
// nodewire-compiler/ and loaded at runtime by ScriptHost via a dedicated
// URLClassLoader over the extracted jars (standard embedding pattern).

plugins {
    id("net.neoforged.moddev")
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

val neoForgeVer: String by project
val kffVer: String by project
val modGroup: String by project
val modVersion: String by project

group = modGroup
version = modVersion
base.archivesName.set("nodewire_scripting")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

repositories {
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.parchmentmc.org/")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    mavenCentral()
}

neoForge {
    version = neoForgeVer

    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }

    // No own dev runs — the optional addon is loaded into CORE's runs instead
    // (core's `./gradlew runClient` registers nodewire_scripting), so the dev
    // client has core's full mod deps (Create/Sable/...) present. Keeping the
    // cross-project reference one-directional (core -> :scripting) avoids
    // evaluation-order tangles.
    mods {
        register("nodewire_scripting") {
            sourceSet(sourceSets["main"])
        }
    }
}

// URLClassLoader embedding (standard pattern). The Kotlin compiler is NOT shaded
// into this module's output (that fails under NeoForge: the compiler does
// `getResource` on its own classes, and NeoForge serves mod resources from a
// `union://` filesystem that cannot extract a single `.class`). Instead we ship
// the whole compiler closure + stdlib/reflect + our backend + the core facade as
// REAL JAR FILES under `nodewire-compiler/` (a resource dir). At runtime
// `ScriptHost` extracts them to a temp dir and loads them through a dedicated
// `URLClassLoader` over those extracted jars, where `getResource` returns normal
// `jar://` URLs.
//
// `compilerLibs` (transitive) = kotlin-scripting-jvm-host (drags the full closure:
// scripting-jvm/common, scripting-compiler(-impl)-embeddable, kotlin-compiler-
// embeddable, kotlin-daemon-embeddable, kotlin-script-runtime, trove4j) +
// stdlib + reflect + script-runtime. All copied verbatim — no split-package
// excludes needed, since the compiler lives in its OWN classloader, not the
// module layer.
val compilerLibs by configurations.creating

/** JOML for the script COMPILE classpath (see jomlJar task below). */
val jomlForScripts by configurations.creating

val kotlinScriptVer = "2.0.20"

dependencies {
    // The core mod: ScriptModule, ScriptType, the ScriptCompiler SPI + registry.
    // Non-transitive: the moved backend only links core's own classes (+ Minecraft,
    // which this module gets independently via its neoForge{} block). Pulling
    // core's transitive 3rd-party mod deps (Create/JEI/EMI/CC/…) would drag in
    // repos this module doesn't declare and isn't needed to compile/run scripts.
    implementation(project(":")) { isTransitive = false }

    // Kotlin for NeoForge — language loader (KFF) shared with core.
    implementation("thedarkcolour:kotlinforforge-neoforge:$kffVer")

    // Compile-time only: ScriptHost links kotlin.script.experimental.* and the
    // jvm-host API. The actual classes come from the shade at runtime — declaring
    // them compileOnly keeps them off the published runtime classpath (the shade
    // is the single runtime source of truth) while still resolving the imports.
    compileOnly("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinScriptVer")
    compileOnly("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinScriptVer")
    compileOnly("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinScriptVer")

    // The compiler closure that lands under nodewire-compiler/ as real jars and
    // gets loaded by ScriptHost's URLClassLoader. jvm-host drags the whole
    // closure; stdlib/reflect/script-runtime are added explicitly so the
    // extracted dir is self-contained (the URLClassLoader has no parent kotlin.*).
    compilerLibs("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinScriptVer")
    compilerLibs("org.jetbrains.kotlin:kotlin-stdlib:$kotlinScriptVer")
    compilerLibs("org.jetbrains.kotlin:kotlin-reflect:$kotlinScriptVer")
    compilerLibs("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinScriptVer")

    // Matches the joml MC 1.21.1 ships; compile-classpath only (runtime links
    // against the boot-layer joml via the sandbox's VIA_MOD delegation).
    jomlForScripts("org.joml:joml:1.10.5")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── URLClassLoader payload under nodewire-compiler/ ──────────────────────────
//
// Everything the runtime URLClassLoader (and the script compile classpath)
// needs, packaged as REAL jars under a single resource dir. Three producers:
//   1. compilerLibs copy — the kotlin compiler closure + stdlib/reflect/runtime.
//   2. backend.jar       — this module's host/** + sandbox/** classes.
//   3. script-api.jar    — core's dev.nitka.nodewire.script.** facade.
// A generated index.txt enumerates every jar (classpath resource dir listing is
// unreliable under jar/union loaders, so the loader reads the index instead).
val compilerResDir = layout.buildDirectory.dir("generated/compilerLibs/nodewire-compiler")

// 1. Copy the resolved compiler closure verbatim (normalised names).
val bundleCompilerLibs = tasks.register<Copy>("bundleCompilerLibs") {
    from(compilerLibs)
    into(compilerResDir)
    rename("""(.+?)-\d.*\.jar""", "$1.jar") // kotlin-compiler-embeddable-2.0.20.jar -> kotlin-compiler-embeddable.jar
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// 2. backend.jar = the compiler-USING code (loaded by the URLClassLoader).
// Source from the COMPILE outputs directly (not `classes`/`output`, which pull
// processResources → the generated compiler dir → a task cycle).
val backendKotlin = tasks.named(
    "compileKotlin",
    org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class,
)
val backendJar = tasks.register<Jar>("backendJar") {
    archiveFileName.set("backend.jar")
    destinationDirectory.set(compilerResDir)
    from(backendKotlin.flatMap { it.destinationDirectory }) {
        include("dev/nitka/nodewire/script/host/**")
        include("dev/nitka/nodewire/script/sandbox/**")
    }
}

// 3. script-api.jar = core's facade (the script body's compile classpath; core
// is union:// at runtime so its classes can't be read off the live classpath).
// Sourced from core's compileKotlin output dir (lazy task ref → no cross-project
// eager evaluation / circular dependency). The facade is pure Kotlin.
val coreKotlinClasses = project(":").tasks.named(
    "compileKotlin",
    org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class,
)
val scriptApiJar = tasks.register<Jar>("scriptApiJar") {
    archiveFileName.set("script-api.jar")
    destinationDirectory.set(compilerResDir)
    from(coreKotlinClasses.flatMap { it.destinationDirectory }) {
        include("dev/nitka/nodewire/script/**")
        // Without the .kotlin_module map K2 resolves CLASSES from this jar but
        // NOT top-level functions/properties (star-import lookups go through
        // the module map) — script-facing entry points are interface/receiver
        // MEMBERS by convention (ScriptModule.state, VideoCanvas.ui — both
        // bitten by this). Ship the map anyway so a future top-level works.
        include("META-INF/*.kotlin_module")
    }
}

// Convenience: drop the freshly-built script-api.jar into a standalone IDE
// authoring template so a user can open that folder in IntelliJ and get
// autocomplete for *.nw.kts scripts. The destination is NOT hardcoded — point it
// at your template's libs dir via a Gradle property (CLI or ~/.gradle/gradle.properties):
//   ./gradlew :scripting:exportScriptApi -PscriptApiExportDir=/path/to/template/libs
// Defaults to this module's build/script-api (machine-independent, stays in-repo).
tasks.register<Copy>("exportScriptApi") {
    dependsOn(scriptApiJar)
    from(scriptApiJar.flatMap { it.archiveFile })
    val exportDir = providers.gradleProperty("scriptApiExportDir")
        .orElse(layout.buildDirectory.dir("script-api").map { it.asFile.absolutePath })
    into(exportDir)
}

// 3b. joml.jar — real JOML for script math. In PRODUCTION MC loads joml as a
// JPMS module on the boot layer; referencing that modular jar by codeSource
// path made K2 reject it ("Cannot access class 'org.joml.Vector3d'" on the
// in-game compile, 2026-06-12, while dev/test compiles passed). Bundle a
// plain CLASSPATH copy with module-info stripped. Runtime linkage still goes
// to the boot-layer joml through the sandbox (VIA_MOD), so there is no class
// identity split — this jar is compile-time only.
val jomlJar = tasks.register<Jar>("jomlJar") {
    archiveFileName.set("joml.jar")
    destinationDirectory.set(compilerResDir)
    from({ jomlForScripts.map { zipTree(it) } }) {
        exclude("module-info.class")
        exclude("META-INF/versions/**")
    }
}

// 4. index.txt = every jar filename under nodewire-compiler/ (one per line).
val writeCompilerIndex = tasks.register("writeCompilerIndex") {
    dependsOn(bundleCompilerLibs, backendJar, scriptApiJar, jomlJar)
    val dir = compilerResDir
    outputs.file(dir.map { it.file("index.txt") })
    doLast {
        val d = dir.get().asFile
        val names = d.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
            ?.map { it.name }?.sorted().orEmpty()
        d.resolve("index.txt").writeText(names.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.named("main") {
    resources.srcDir(
        files(layout.buildDirectory.dir("generated/compilerLibs")).builtBy(writeCompilerIndex),
    )
}

// Make the core mod's compile/runtime classpath (Minecraft, graph types, etc.)
// available to this module's unit tests — same trick core uses. The smoke test
// imports net.minecraft.nbt.CompoundTag + dev.nitka.nodewire.graph.PinValue.
configurations.named("testCompileClasspath").configure {
    extendsFrom(configurations.compileClasspath.get())
}
configurations.named("testRuntimeClasspath").configure {
    extendsFrom(configurations.runtimeClasspath.get())
}

// The moved ScriptHostSmokeTest exercises ScriptModule's `internal` surface
// (specsIn/specsOut/tickBlock + the internal pushInputs/pullOutputs/loadState/
// saveState extensions in PinBridge). `internal` is Gradle-module-scoped, so a
// test in THIS module can't see core's internals by default. Wire core's main
// Kotlin output as a friend path — the canonical Kotlin mechanism (-Xfriend-paths)
// for granting internal visibility across a compilation boundary, without
// widening core's API or duplicating any class.
val coreMainKotlin = project(":").tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class)
tasks.named("compileTestKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).configure {
    friendPaths.from(coreMainKotlin.flatMap { it.destinationDirectory })
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
