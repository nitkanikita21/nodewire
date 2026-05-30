package dev.nitka.nodewire.script.host

import dev.nitka.nodewire.script.ScriptCompileResult
import dev.nitka.nodewire.script.ScriptCompiler
import dev.nitka.nodewire.script.ScriptEvalResult
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
 * The compiler **backend** — all logic that links `kotlin.script.experimental.*`
 * and the embedded Kotlin compiler.
 *
 * This class is bundled inside the addon jar as `nodewire-compiler/backend.jar`
 * and is loaded at runtime by [ScriptHost]'s dedicated [java.net.URLClassLoader]
 * over the **extracted real compiler jars** (outside NeoForge's `union://`
 * filesystem). The URLClassLoader isolation is what makes
 * `KotlinCoreEnvironment`'s `getResource` self-lookups resolve to normal
 * `jar://` URLs.
 *
 * [ScriptHost] reflectively instantiates this via its public no-arg
 * constructor and casts to core's [ScriptCompiler] (a parent-loaded, hence
 * single-identity, interface).
 */
class ScriptBackend : ScriptCompiler {

    // Compiler self-resource / IDEA-IO fallback must be armed before the host
    // (and KotlinCoreEnvironment) is constructed. Inside the backend so
    // ScriptHost never references this compiler-internal class.
    init {
        // setIdeaIoUseFallback() — arms the compiler's IDEA-IO fallback so its
        // VFS works headless. Called reflectively: the class lives in
        // kotlin-compiler-embeddable, which is a runtime URLClassLoader jar, not
        // a compileOnly dep of this module.
        runCatching {
            val cls = Class.forName(
                "org.jetbrains.kotlin.cli.common.environment.UtilKt",
                true,
                ScriptBackend::class.java.classLoader,
            )
            cls.getMethod("setIdeaIoUseFallback").invoke(null)
        }
        bundledScriptLibs()
    }

    private val host = BasicJvmScriptingHost()

    // ── core SPI ─────────────────────────────────────────────────────────

    override fun compileToModule(source: String): ScriptCompileResult {
        val r = compileModule(source)
        val module = r.valueOrNull()
        return if (module != null) ScriptCompileResult.Success(module)
        else ScriptCompileResult.Failure(r.reports.map { it.message })
    }

    override fun evalSource(source: String): ScriptEvalResult {
        val r = evalSourceRaw(source)
        return when (r) {
            is ResultWithDiagnostics.Success -> ScriptEvalResult.Value(r.value)
            is ResultWithDiagnostics.Failure -> ScriptEvalResult.Failure(r.reports.map { it.message })
        }
    }

    // ── compile classpath (for the compiled script) ──────────────────────

    /**
     * Explicit compile classpath for the script body = the EXTRACTED real jars
     * (kotlin-stdlib, kotlin-script-runtime, script-api) plus the live
     * facade/backend code sources. The extracted libs dir is supplied by
     * [ScriptHost] via the `nodewire.script.libsDir` system property.
     */
    fun scriptClasspath(): List<File> = buildList {
        fun jarOf(c: Class<*>): File? =
            runCatching { File(c.protectionDomain.codeSource.location.toURI()) }.getOrNull()

        jarOf(ScriptModule::class.java)?.let(::add) // core facade output (dev) — null under union://
        jarOf(ScriptBackend::class.java)?.let(::add) // backend.jar (this code)
        addAll(bundledScriptLibs()) // stdlib + script-runtime + script-api (extracted jars)
    }.distinct()

    /**
     * The extracted jars the compiled script links against: kotlin-stdlib,
     * kotlin-script-runtime and script-api.jar (core's facade), located in the
     * dir [ScriptHost] extracted them to. Also points `kotlin.java.stdlib.jar`
     * at the extracted stdlib so the host's own `KotlinJars` lookup succeeds
     * under NeoForge (where `java.home` has no kotlin stdlib).
     */
    private fun bundledScriptLibs(): List<File> {
        val dirPath = System.getProperty(LIBS_DIR_PROP)
            ?: return emptyList()
        val dir = File(dirPath)
        val libs = SCRIPT_CLASSPATH_JARS.mapNotNull { name ->
            val f = File(dir, name)
            if (f.exists() && f.length() > 0L) f else null
        }
        libs.firstOrNull { it.name == "kotlin-stdlib.jar" }?.let {
            System.setProperty("kotlin.java.stdlib.jar", it.absolutePath)
        }
        libs.firstOrNull { it.name == "kotlin-script-runtime.jar" }?.let {
            System.setProperty("kotlin.script.runtime.jar", it.absolutePath)
        }
        return libs
    }

    /**
     * The guard the compiled script links against. `modLoader` =
     * [ScriptBackend]'s own loader (the URLClassLoader); its VIA_MOD lookups
     * delegate parent-first to core's facade, preserving host identity for
     * `PinValue` / `Redstone` / `ScriptModule`.
     */
    private fun newGuard(): SandboxClassLoader =
        SandboxClassLoader(ScriptBackend::class.java.classLoader)

    private val compileConfig by lazy {
        createJvmCompilationConfigurationFromTemplate<NwScript>()
    }

    fun compile(source: String): ResultWithDiagnostics<CompiledScript> =
        withModuleTccl {
            host.runInCoroutineContext { host.compiler(source.toScriptSource(), compileConfig) }
        }

    fun compileModule(source: String): ResultWithDiagnostics<ScriptModule> {
        val compiled = compile(source)
        val script = compiled.valueOrNull()
            ?: return ResultWithDiagnostics.Failure(compiled.reports)

        val module = NwScriptInstance()
        val evalConfig = ScriptEvaluationConfiguration {
            implicitReceivers(module)
            jvm { baseClassLoader(newGuard()) }
        }

        val evalResult = withModuleTccl {
            host.runInCoroutineContext { host.evaluator(script, evalConfig) }
        }
        return when (evalResult) {
            is ResultWithDiagnostics.Success -> {
                val rv = evalResult.value.returnValue
                if (rv is ResultValue.Error) {
                    ResultWithDiagnostics.Failure(evalResult.reports + rv.error.asDiagnostics())
                } else {
                    ResultWithDiagnostics.Success(module, evalResult.reports)
                }
            }
            is ResultWithDiagnostics.Failure -> ResultWithDiagnostics.Failure(evalResult.reports)
        }
    }

    fun evalSourceRaw(source: String): ResultWithDiagnostics<Any?> {
        val compiled = compile(source)
        val script = compiled.valueOrNull()
            ?: return ResultWithDiagnostics.Failure(compiled.reports)
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
            t.contextClassLoader = ScriptBackend::class.java.classLoader
            block()
        } finally {
            t.contextClassLoader = prev
        }
    }

    companion object {
        const val LIBS_DIR_PROP = "nodewire.script.libsDir"
        private val SCRIPT_CLASSPATH_JARS =
            listOf("kotlin-stdlib.jar", "kotlin-script-runtime.jar", "script-api.jar")
    }
}

/** Concrete [ScriptModule] used as the implicit receiver for the compiled body. */
private class NwScriptInstance : ScriptModule()
