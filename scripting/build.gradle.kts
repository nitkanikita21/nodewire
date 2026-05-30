// nodewire_scripting — optional addon mod.
//
// It exists solely to carry the Kotlin compiler (kotlin-scripting /
// kotlin-compiler-embeddable, ~50-90 MB) so the core `nodewire` mod stays
// small. Core declares the `ScriptCompiler` SPI + a registry; this module
// implements it (ScriptHost) and registers itself on mod init. When this
// addon is absent, core's Script Node is read-only.
//
// Stage 1 of the split: module compiles + depends on core. Shading the
// compiler into the jar (extractShadedLibs-style) and the JPMS wiring is
// Stage 3 (Layer B); the multi-mod dev run is Stage 4.

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

// Stage 3 (Layer B): SHADE the Kotlin compiler + scripting host into THIS
// module's own output, exactly the way core shades compose-runtime/yoga.
//
// Why shading (not runtimeOnly): kotlin-compiler-embeddable's BuiltInsLoader is
// discovered through a ServiceLoader that uses the DEFINING class's classloader
// (not the thread-context loader). Under NeoForge's module layer that means the
// impl class AND its `META-INF/services/...BuiltInsLoader` file must live in the
// SAME module as the caller — i.e. our module output. So we extract the whole
// compiler closure into build/shadedLibs and add it to the main sourceSet output.
//
// `kotlin-scripting-jvm-host` is the single root that drags the full transitive
// closure: scripting-jvm, scripting-common, scripting-compiler(-impl)-embeddable,
// kotlin-compiler-embeddable, kotlin-daemon-embeddable, kotlin-script-runtime,
// trove4j. kotlin-compiler-embeddable 2.0.20 already self-relocates its bundled
// intellij/guava/asm under org.jetbrains.kotlin.* — no relocation needed here.
//
// stdlib + reflect are EXCLUDED: KFF provides those at runtime in their own JPMS
// module. Shading a second copy of kotlin.* would split-package against KFF and
// break the module layer.
val shadedLibs by configurations.creating
val scriptLibs by configurations.creating { isTransitive = false }

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

    // The shade root. The jvm-host artifact pulls the whole compiler closure.
    // Exclude stdlib/reflect — KFF owns those at runtime; a second copy would
    // JPMS-split against kotlin.* (see note above).
    shadedLibs("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinScriptVer") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }

    // kotlin-stdlib + script-runtime the COMPILED SCRIPT links against, shipped as
    // plain JAR FILES (not module classes — that splits with KFF). Resolved here so
    // bundleScriptLibs can copy them into the addon resources. isTransitive=false →
    // only these two jars resolve (no kotlin-stdlib-common/annotations drag-in).
    scriptLibs("org.jetbrains.kotlin:kotlin-stdlib:$kotlinScriptVer")
    scriptLibs("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinScriptVer")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Extract the compiler closure into the main sourceSet output, mirroring core's
// extractShadedLibs Sync. CRITICAL: do NOT exclude META-INF/services — the
// BuiltInsLoader (and the compiler's other ServiceLoader-discovered services)
// are looked up there. We only strip manifests/maven metadata/jar signatures.
val extractedShadedLibs = layout.buildDirectory.dir("shadedLibs")

val extractShadedLibs = tasks.register<Sync>("extractShadedLibs") {
    from({ shadedLibs.map { zipTree(it) } })
    into(extractedShadedLibs)
    exclude("META-INF/MANIFEST.MF", "META-INF/maven/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // JPMS: NeoForge derives this module's descriptor by scanning package dirs;
    // any segment that is a Java keyword is rejected ("Invalid package name:
    // 'native' is not a Java identifier") and the whole module layer fails to
    // build. The shaded kotlin-compiler ships several `native` packages
    // (Kotlin/Native target, FIR native backend/checkers, jansi native libs) —
    // none used by JVM script compilation — so drop every `native` package.
    exclude("**/native/**")
    // Same JPMS reason, split-package axis: KFF already ships kotlinx.coroutines
    // as its own module, so a second copy from the compiler closure fails module
    // resolution ("Modules kotlinx.coroutines.core and nodewire_scripting export
    // package kotlinx.coroutines.scheduling"). Drop ours; the compiler links
    // KFF's copy at runtime (the addon is an automatic module → reads it).
    exclude("kotlinx/coroutines/**")
    // compiler-embeddable also bundles a partial kotlin stdlib/reflect
    // (kotlin.collections/ranges/internal/coroutines/annotation/reflect) — KFF
    // ships those as the kotlin.stdlib module, so they split-package too. Drop
    // every kotlin.* package EXCEPT kotlin.script.* (the scripting API, which KFF
    // does NOT provide and the host needs). The compiler links KFF's stdlib.
    exclude { e ->
        e.path.startsWith("kotlin/") &&
            e.path != "kotlin/script" &&
            !e.path.startsWith("kotlin/script/")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

sourceSets.named("main") {
    output.dir(mapOf("builtBy" to extractShadedLibs), extractedShadedLibs)
}

// kotlin-stdlib + script-runtime the COMPILED SCRIPT links against. Shipped
// as plain JAR FILES (resources) under nodewire-script-libs/ — NOT module
// classes (that splits with KFF). ScriptHost extracts them at runtime and
// feeds them to the script compile classpath, because the compiler cannot
// infer the stdlib under NeoForge (CodeSource of kotlin.Unit is union://).
val bundleScriptLibs = tasks.register<Copy>("bundleScriptLibs") {
    from(scriptLibs)
    into(layout.buildDirectory.dir("generated/scriptLibs/nodewire-script-libs"))
    rename("""(.+?)-\d.*\.jar""", "$1.jar") // kotlin-stdlib-2.0.20.jar -> kotlin-stdlib.jar
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
sourceSets.named("main") {
    resources.srcDir(files(layout.buildDirectory.dir("generated/scriptLibs")).builtBy(bundleScriptLibs))
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
