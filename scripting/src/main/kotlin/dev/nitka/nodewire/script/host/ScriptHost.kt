package dev.nitka.nodewire.script.host

import dev.nitka.nodewire.script.ScriptCompileResult
import dev.nitka.nodewire.script.ScriptCompiler
import dev.nitka.nodewire.script.ScriptModule
import dev.nitka.nodewire.script.sandbox.SandboxClassLoader
import java.io.File
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.asDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Server-side entry point for compiling and running Nodewire scripts.
 *
 * This is the Layer-A spike surface: compile a script string with the
 * sandboxed compile config (§6.1) + the guarding classloader (§6.2), run it
 * against a fresh [ScriptModule] receiver, and hand back the live module so a
 * caller can push inputs / tick / pull outputs.
 *
 * Async compile, hash cache, error surfacing, and the `tickEvaluator` /
 * `NodeType` wiring (§7) are deliberately out of scope here — this proves the
 * compile+run+sandbox path works in-JVM before the JPMS/shading work (Layer B).
 */
object ScriptHost : ScriptCompiler {

    private val BUNDLED_SCRIPT_LIBS = listOf("kotlin-stdlib.jar", "kotlin-script-runtime.jar")

    // Extract the bundled stdlib and set `kotlin.java.stdlib.jar` BEFORE the host
    // (hence the compiler's lazy KotlinJars stdlib lookup) is constructed, so it
    // can never resolve+cache a null first.
    init {
        bundledScriptLibs()
    }

    private val host = BasicJvmScriptingHost()

    /**
     * SPI bridge: adapt the host's [ResultWithDiagnostics] return to core's
     * compiler-agnostic [ScriptCompileResult]. Core calls this through the
     * [ScriptCompiler] interface without ever touching `kotlin.script.experimental`.
     */
    override fun compileToModule(source: String): ScriptCompileResult {
        val r = compileModule(source)
        val module = r.valueOrNull()
        return if (module != null) ScriptCompileResult.Success(module)
        else ScriptCompileResult.Failure(r.reports.map { it.message })
    }

    /**
     * Explicit compile classpath, resolved via [CodeSource] — works under
     * NeoForge's SecureModuleClassLoader where `getUrls()` / `java.class.path`
     * do not. In tests (flat classpath) these resolve to the `build/classes`
     * dirs / stdlib jar, which is exactly what the compiler needs.
     *
     * Anchors on [ScriptModule] (the script-facing API: `input`/`output`/
     * `tick`/`eval`/`state`, `Redstone`, `ScriptType`) which lives in the CORE
     * module, NOT in this addon. Pre-split the API and [ScriptHost] shared one
     * module so anchoring on `ScriptHost` worked; after moving the backend here,
     * `ScriptHost`'s CodeSource points at the addon output (no API), so the
     * script body would fail to resolve `input`/`output`/… [ScriptHost]'s own
     * module is added too so any future addon-side script symbols resolve.
     */
    fun scriptClasspath(): List<File> = buildList {
        fun jarOf(c: Class<*>): File? =
            runCatching { File(c.protectionDomain.codeSource.location.toURI()) }.getOrNull()

        jarOf(ScriptModule::class.java)?.let(::add) // core module — script API lives here
        jarOf(ScriptHost::class.java)?.let(::add) // this addon module
        jarOf(Unit::class.java)?.let(::add) // kotlin-stdlib (null under NeoForge — union:// CodeSource)
        addAll(bundledScriptLibs()) // kotlin-stdlib + script-runtime, shipped as resources
    }.distinct()

    /**
     * The compiled script links against kotlin-stdlib + kotlin-script-runtime.
     * Under NeoForge the compiler cannot infer the stdlib jar ([Unit]'s CodeSource
     * is a `union://` URL → [scriptClasspath]'s `jarOf(Unit)` returns null), so we
     * ship those two jars as plain JAR-FILE resources under `nodewire-script-libs/`
     * (packed as files, NOT exploded class packages — exploding would split-package
     * against KFF's `kotlin.stdlib` module). Extract once to a stable per-version
     * cache dir and hand the real File paths to the script compile classpath.
     */
    private fun bundledScriptLibs(): List<File> {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "nodewire-script-libs-2.0.20")
        dir.mkdirs()
        val libs = BUNDLED_SCRIPT_LIBS.mapNotNull { name ->
            val out = java.io.File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                val stream = ScriptHost::class.java.classLoader.getResourceAsStream("nodewire-script-libs/$name")
                    ?: return@mapNotNull null
                stream.use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            out
        }
        // The JVM scripting host resolves the stdlib through `KotlinJars` (the
        // `kotlin.java.stdlib.jar` property, else inference from `java.home`),
        // INDEPENDENTLY of updateClasspath. Under NeoForge `java.home` has no
        // kotlin stdlib, so it fails with "Unable to find kotlin stdlib". Point
        // the property at our extracted jar so the host's own lookup succeeds.
        libs.firstOrNull { it.name == "kotlin-stdlib.jar" }?.let {
            System.setProperty("kotlin.java.stdlib.jar", it.absolutePath)
        }
        libs.firstOrNull { it.name == "kotlin-script-runtime.jar" }?.let {
            System.setProperty("kotlin.script.runtime.jar", it.absolutePath)
        }
        return libs
    }

    /** The guard the compiled script links against — borrows host Classes by identity. */
    private fun newGuard(): SandboxClassLoader =
        SandboxClassLoader(ScriptHost::class.java.classLoader)

    private val compileConfig by lazy {
        createJvmCompilationConfigurationFromTemplate<NwScript>()
    }

    /**
     * Compile [source]. Returns the compiler result (Success carries the
     * [CompiledScript]; Failure carries diagnostics for the node card).
     *
     * The compiler/evaluator `invoke`s are `suspend`; [host.runInCoroutineContext]
     * is the host's own non-suspend bridge, so we don't add a load-bearing
     * `runBlocking` dependency on the script-compile path (§8).
     *
     * Wrapped in a TCCL swap to the nodewire module loader — defensive against
     * host code reading TCCL even though `BuiltInsLoader` uses `Class.classLoader`.
     */
    fun compile(source: String): ResultWithDiagnostics<CompiledScript> =
        withModuleTccl {
            host.runInCoroutineContext { host.compiler(source.toScriptSource(), compileConfig) }
        }

    /**
     * Compile [source] and instantiate a live [ScriptModule] under the sandbox
     * guard. The script's top-level declarations register pins/state into the
     * returned module; the caller then drives ticks against it.
     *
     * Returns Failure (with diagnostics) if compilation OR evaluation fails.
     */
    fun compileModule(source: String): ResultWithDiagnostics<ScriptModule> {
        val compiled = compile(source)
        val script = compiled.valueOrNull()
            ?: return ResultWithDiagnostics.Failure(compiled.reports)

        val module = NwScriptInstance()
        val evalConfig = ScriptEvaluationConfiguration {
            implicitReceivers(module)
            jvm {
                // The decisive sandbox knob: compiled script classes load with
                // the guard as their parent, so every symbol they link is
                // allowlist-checked. If set wrong, classes would route through
                // the app loader and silently bypass the guard.
                baseClassLoader(newGuard())
            }
        }

        val evalResult = withModuleTccl {
            host.runInCoroutineContext { host.evaluator(script, evalConfig) }
        }
        return when (evalResult) {
            is ResultWithDiagnostics.Success -> {
                val rv = evalResult.value.returnValue
                if (rv is ResultValue.Error) {
                    // The evaluator swallows the construction failure into
                    // ResultValue.Error.error — surface it as a diagnostic so
                    // the node card (and the spike's tests) can see the real
                    // cause (e.g. a denied class in the script's <clinit>).
                    ResultWithDiagnostics.Failure(evalResult.reports + rv.error.asDiagnostics())
                } else {
                    ResultWithDiagnostics.Success(module, evalResult.reports)
                }
            }
            is ResultWithDiagnostics.Failure -> ResultWithDiagnostics.Failure(evalResult.reports)
        }
    }

    /**
     * Layer-B probe helper: compile + evaluate a bare expression and return its
     * value (or Failure + diagnostics). Used by `ScriptStartupProbe`.
     */
    fun evalSource(source: String): ResultWithDiagnostics<Any?> {
        val compiled = compile(source)
        val script = compiled.valueOrNull()
            ?: return ResultWithDiagnostics.Failure(compiled.reports)
        // The template always declares an implicit receiver (ScriptModule), so
        // the generated constructor takes one even for a bare expression.
        val evalConfig = ScriptEvaluationConfiguration {
            implicitReceivers(NwScriptInstance())
            jvm { baseClassLoader(newGuard()) }
        }
        val res = withModuleTccl {
            host.runInCoroutineContext { host.evaluator(script, evalConfig) }
        }
        return when (res) {
            is ResultWithDiagnostics.Success ->
                ResultWithDiagnostics.Success((res.value.returnValue as? ResultValue.Value)?.value, res.reports)
            is ResultWithDiagnostics.Failure -> ResultWithDiagnostics.Failure(res.reports)
        }
    }

    private inline fun <T> withModuleTccl(block: () -> T): T {
        val t = Thread.currentThread()
        val prev = t.contextClassLoader
        return try {
            t.contextClassLoader = ScriptHost::class.java.classLoader
            block()
        } finally {
            t.contextClassLoader = prev
        }
    }
}

/** Concrete [ScriptModule] used as the implicit receiver for the compiled body. */
private class NwScriptInstance : ScriptModule()
