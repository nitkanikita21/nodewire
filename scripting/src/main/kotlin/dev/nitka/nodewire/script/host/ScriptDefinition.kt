package dev.nitka.nodewire.script.host

import dev.nitka.nodewire.script.ScriptModule
import java.io.File
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.updateClasspath

/**
 * The script definition for a Nodewire inline script (`*.nw.kts`).
 *
 * The compile config is an **ergonomics + IDE-parity** layer, NOT the security
 * boundary (the [dev.nitka.nodewire.script.sandbox.SandboxClassLoader] is).
 *  - `defaultImports` makes the `import dev.nitka.nodewire.script.*` line
 *    optional in-world.
 *  - `implicitReceivers(ScriptModule)` makes `this` = [ScriptModule] in the
 *    body, so `input(...)` / `output(...)` / `tick { }` resolve unqualified.
 *  - `updateClasspath(...)` supplies an explicit, narrow classpath built from
 *    the jars ScriptHost extracted (`nodewire.script.libsDir`). We must NEVER
 *    use `dependenciesFromCurrentContext` — under NeoForge's
 *    SecureModuleClassLoader it yields an empty/garbage classpath.
 */
@KotlinScript(
    fileExtension = "nw.kts",
    compilationConfiguration = NwScriptCompilationConfig::class,
)
abstract class NwScript

object NwScriptCompilationConfig : ScriptCompilationConfiguration({
    defaultImports("dev.nitka.nodewire.script.*")
    implicitReceivers(ScriptModule::class)
    // The script API's `input<T>` / `output<T>` are `inline fun`s compiled at
    // JVM target 21; the scripting compiler defaults to 1.8 and refuses to
    // inline target-21 bytecode ("Cannot inline bytecode built with JVM target
    // 21 …"). Pin both the structured key and the raw flag to 21.
    compilerOptions("-jvm-target", "21")
    jvm {
        jvmTarget("21")
        // {script-api jar, kotlin-stdlib jar, kotlin-script-runtime jar} — the
        // EXTRACTED real jars in the dir ScriptHost set via `nodewire.script.libsDir`,
        // plus the backend's own code source. NEVER dependenciesFromCurrentContext
        // (empty/garbage under SecureModuleClassLoader).
        updateClasspath(scriptCompileClasspath())
    }
})

/**
 * Compile classpath for the script body, resolved from the extracted jars dir
 * [ScriptHost] passes via `nodewire.script.libsDir`. Mirrors
 * [ScriptBackend.scriptClasspath] but is self-contained so the statically
 * constructed [NwScriptCompilationConfig] needs no live backend instance.
 */
private fun scriptCompileClasspath(): List<File> = buildList {
    fun jarOf(c: Class<*>): File? =
        runCatching { File(c.protectionDomain.codeSource.location.toURI()) }.getOrNull()

    jarOf(ScriptModule::class.java)?.let(::add)
    jarOf(NwScript::class.java)?.let(::add)

    val dir = System.getProperty(ScriptBackend.LIBS_DIR_PROP)?.let(::File)
    if (dir != null) {
        for (name in listOf("kotlin-stdlib.jar", "kotlin-script-runtime.jar", "script-api.jar")) {
            val f = File(dir, name)
            if (f.exists() && f.length() > 0L) add(f)
        }
    }
}.distinct()
