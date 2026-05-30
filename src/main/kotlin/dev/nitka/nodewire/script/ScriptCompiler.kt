package dev.nitka.nodewire.script

/**
 * Compiler SPI — core's seam to the (optional) Kotlin-compiler-backed script
 * backend. Core declares this interface and a registry; the `:scripting` addon
 * mod implements it (via `ScriptHost`) and registers itself on mod init.
 *
 * Core NEVER references the Kotlin compiler directly — it only knows this
 * interface and the registry. When the addon is absent, [ScriptCompilerRegistry]
 * stays empty and the Script Node degrades to read-only.
 */
interface ScriptCompiler {
    fun compileToModule(source: String): ScriptCompileResult

    /**
     * Compile + evaluate a bare expression and return its value. Used by the
     * startup probe and the smoke test. Result is expressed in core types so
     * the thin loader (`ScriptHost`) never has to reference
     * `kotlin.script.experimental.*` (which is loaded only inside the backend's
     * isolated URLClassLoader).
     */
    fun evalSource(source: String): ScriptEvalResult
}

/**
 * Result of an [ScriptCompiler.evalSource] request. [Value] carries the boxed
 * expression value (may be `null`); [Failure] carries human-readable diagnostics.
 */
sealed interface ScriptEvalResult {
    data class Value(val value: Any?) : ScriptEvalResult
    data class Failure(val diagnostics: List<String>) : ScriptEvalResult
}

/**
 * Result of a compile request. [Success] carries the live [ScriptModule] (its
 * top-level declarations already ran, registering pins/state); [Failure]
 * carries human-readable diagnostics for the node card.
 */
sealed interface ScriptCompileResult {
    data class Success(val module: ScriptModule) : ScriptCompileResult
    data class Failure(val diagnostics: List<String>) : ScriptCompileResult
}

/**
 * Process-wide registration point for the active [ScriptCompiler].
 *
 * The addon mod sets [compiler] in its constructor; core reads it through
 * [isAvailable] before offering compile actions. `@Volatile` because mod-init
 * (writer) and the editor/server tick (readers) run on different threads.
 */
object ScriptCompilerRegistry {
    @Volatile
    var compiler: ScriptCompiler? = null

    val isAvailable: Boolean
        get() = compiler != null
}
