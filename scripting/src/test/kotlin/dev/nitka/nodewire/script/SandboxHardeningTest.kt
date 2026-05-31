package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.script.host.ScriptHost
import dev.nitka.nodewire.script.sandbox.SandboxClassLoader
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 2c hardening #2 — proves the [dev.nitka.nodewire.script.sandbox.SandboxClassLoader]
 * deny-list additions close the two reflective escape surfaces the review
 * flagged, WITHOUT breaking the ordinary script machinery that piggybacks on
 * the same underlying packages:
 *
 *  - `java.lang.invoke.MethodHandles` (+ its nested `MethodHandles$Lookup`) is
 *    denied — the reflective handle-factory API. Lambda/`invokedynamic`
 *    bootstrap (`LambdaMetafactory`, `MethodType`, `MethodHandle`, `CallSite`,
 *    `StringConcatFactory`) is supplied by the JVM at link time and does NOT
 *    route through this loader, so `tick { … }` still works.
 *  - `kotlin.reflect.full.*` is denied — the reflective *calling* surface.
 *    `T::class`, `var x by state(…)` (`KProperty` delegation) and `reified`
 *    codegen stay in `kotlin.reflect` / `kotlin.reflect.jvm.internal`, so they
 *    still link.
 */
class SandboxHardeningTest {

    private fun diag(r: ScriptCompileResult): String =
        (r as? ScriptCompileResult.Failure)?.diagnostics?.joinToString("\n") ?: "(success)"

    private fun ScriptCompileResult.moduleOrNull(): ScriptModule? =
        (this as? ScriptCompileResult.Success)?.module

    private fun sandboxRejected(r: ScriptCompileResult): Boolean =
        (r as? ScriptCompileResult.Failure)?.diagnostics
            ?.any { it.contains("not permitted") || it.contains("ClassNotFound") } ?: false

    private fun isDeniedClassError(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is ClassNotFoundException || e is NoClassDefFoundError) return true
            e = e.cause
        }
        return false
    }

    private fun assertDenied(src: String, label: String) {
        val r = ScriptHost.compileToModule(src)
        println("[sandbox-hardening] $label reports:\n${diag(r)}")
        val denied: Boolean = when (r) {
            is ScriptCompileResult.Success -> {
                val threw = runCatching { r.module.tickBlock!!.invoke() }.exceptionOrNull()
                println("[sandbox-hardening] $label runtime throwable: $threw")
                assertNotNull(threw, "$label should have thrown at link/run time")
                isDeniedClassError(threw!!)
            }
            is ScriptCompileResult.Failure -> sandboxRejected(r)
        }
        assertTrue(denied, "$label must be rejected by the sandbox:\n${diag(r)}")
    }

    // (a) — the benign baseline: a state delegate (`var by state`) + a lambda
    // (`tick { }`) + a reified pin type (`output<Int>`) still compile AND run.
    @Test fun stateDelegateAndLambdaStillWork() {
        val src = """
            val out = output<Int>("out")
            var n by state(0)
            tick {
                n = n + 7
                out.value = n
            }
        """.trimIndent()
        val r = ScriptHost.compileToModule(src)
        println("[sandbox-hardening] state-delegate+lambda reports:\n${diag(r)}")
        assertTrue(r is ScriptCompileResult.Success, "baseline script must compile:\n${diag(r)}")
        val module = r.moduleOrNull()!!
        module.tickBlock!!.invoke()
        assertEquals(PinValue.Int(7), module.pullOutputs()["out"])
        module.tickBlock!!.invoke()
        assertEquals(PinValue.Int(14), module.pullOutputs()["out"])
    }

    // (b1) — MethodHandles.lookup() (the reflective factory) is denied at link time.
    @Test fun sandboxDeniesMethodHandlesLookup() {
        assertDenied(
            """
                val out = output<Int>("out")
                tick {
                    val l = java.lang.invoke.MethodHandles.lookup()
                    out.value = l.hashCode()
                }
            """.trimIndent(),
            "MethodHandles.lookup()",
        )
    }

    // (b2) — the nested MethodHandles.Lookup TYPE is denied too: actually USE a
    // Lookup-typed value so its class is forced to link at run time.
    @Test fun sandboxDeniesMethodHandlesLookupType() {
        assertDenied(
            """
                val out = output<Int>("out")
                tick {
                    val l: java.lang.invoke.MethodHandles.Lookup =
                        java.lang.invoke.MethodHandles.publicLookup()
                    out.value = l.lookupModes()
                }
            """.trimIndent(),
            "MethodHandles.Lookup type",
        )
    }

    // (c1) — System.exit (and the rest of java.lang.System: load/loadLibrary
    // native, get|setProperty) is denied at link time. Author-namable (no
    // kotlin-reflect needed), highest-severity DoS/escape.
    @Test fun sandboxDeniesSystemExit() {
        assertDenied(
            """
                val out = output<Int>("out")
                tick {
                    java.lang.System.exit(0)
                    out.value = 1
                }
            """.trimIndent(),
            "System.exit()",
        )
    }

    // (c2) — the headline total-escape: ClassLoader.getSystemClassLoader()
    // returns the REAL app loader, whose loadClass bypasses the guard entirely.
    // Denying java.lang.ClassLoader closes it at link time.
    @Test fun sandboxDeniesSystemClassLoaderEscape() {
        assertDenied(
            """
                val out = output<Int>("out")
                tick {
                    val cl = java.lang.ClassLoader.getSystemClassLoader()
                    val rt = cl.loadClass("java.lang.Runtime")
                    out.value = rt.hashCode()
                }
            """.trimIndent(),
            "ClassLoader.getSystemClassLoader escape",
        )
    }

    // (c3) — Panama FFI (java.lang.foreign.*) native downcall surface denied.
    @Test fun sandboxDeniesForeignLinker() {
        assertDenied(
            """
                val out = output<Int>("out")
                tick {
                    val l = java.lang.foreign.Linker.nativeLinker()
                    out.value = l.hashCode()
                }
            """.trimIndent(),
            "foreign.Linker",
        )
    }

    // (b3) — direct loader proof: the SAME loader the eval path installs as the
    // compiled-script base loader denies BOTH hardened surfaces at `loadClass`,
    // while the basic reflection + indy/lambda machinery a script genuinely
    // links keeps loading.
    //
    // The script compile classpath deliberately excludes kotlin-reflect, so an
    // author can't even *name* a `kotlin.reflect.full.*` symbol — but the loader
    // is the real boundary (the import-allowlist is bypassable), so we assert
    // its verdict directly. modLoader = this test's loader (has stdlib + the JDK
    // platform), which is sufficient to resolve the kept-allowed names.
    @Test fun loaderDeniesHardenedSurfacesKeepsBasics() {
        val guard = SandboxClassLoader(javaClass.classLoader)

        // DENIED — the reflective calling surface + the reflective handle API +
        // the capability classes the adversarial review found reachable through
        // the broad java.*/javax.* VIA_PLATFORM delegation.
        for (denied in listOf(
            "kotlin.reflect.full.KClasses",            // memberProperties / declaredMembers facade
            "java.lang.invoke.MethodHandles",          // the factory holder
            "java.lang.invoke.MethodHandles\$Lookup",  // the privileged Lookup
            "java.lang.System",                        // exit / load(native) / get|setProperty
            "java.lang.ClassLoader",                   // getSystemClassLoader() = total escape
            "java.lang.foreign.Linker",                // Panama FFI native downcalls
            "java.lang.foreign.MemorySegment",
            "java.lang.ProcessHandle",                 // enumerate/kill OS processes
            "java.lang.invoke.VarHandle",              // low-level field/array pokes
            "java.security.AccessController",          // doPrivileged
            "javax.script.ScriptEngineManager",        // spawn another engine unsandboxed
            "java.util.ServiceLoader",                 // load arbitrary providers
            "java.lang.ref.Cleaner",                   // finalization hook
            "sun.misc.Unsafe",
            "jdk.internal.misc.Unsafe",
            "java.lang.Runtime",
            "java.lang.Thread",
        )) {
            assertThrows(ClassNotFoundException::class.java, {
                guard.loadClass(denied)
            }, "$denied must be denied by the sandbox loader")
        }

        // KEPT — what state-delegates + reified pin types + lambdas actually link:
        //  - kotlin.reflect.KClass / KProperty: `T::class` + `var by state(…)`.
        //  - java.lang.invoke.{LambdaMetafactory,MethodType}: invokedynamic
        //    lambda bootstrap (the JVM supplies these; denying them breaks every
        //    `tick { }`). Note MethodHandle itself stays loadable too — only the
        //    reflective MethodHandles factory + Lookup are refused.
        for (kept in listOf(
            "kotlin.reflect.KClass",
            "kotlin.reflect.KProperty",
            "java.lang.invoke.LambdaMetafactory",
            "java.lang.invoke.MethodType",
            "java.lang.invoke.MethodHandle",
        )) {
            assertDoesNotThrow({ guard.loadClass(kept) }, "$kept must remain loadable")
        }
    }

    // Video draw API (Phase B): the script-facing facade resolves VIA_MOD (it
    // lives under the allowlisted `dev.nitka.nodewire.script.` prefix), while the
    // GL-backed impl + every engine type it hides stays DENY'd — so a script can
    // name `VideoCanvas` (to receive one) but can never reach the canvas's GL
    // internals, the bind/unbind renderer, or GuiGraphics/Minecraft/TextureTarget.
    @Test fun videoCanvasFacadeAllowedImplDenied() {
        val guard = SandboxClassLoader(javaClass.classLoader)

        // KEPT — the sandbox-facing facade the runtime hands the script.
        assertDoesNotThrow({ guard.loadClass("dev.nitka.nodewire.script.VideoCanvas") }) {
            "VideoCanvas facade must be loadable (script.* allowlist)"
        }

        // DENIED — the GL-backed impl, the bind/draw/unbind renderer, the cadence
        // gate, and the underlying engine types are all outside `script.*`.
        for (denied in listOf(
            "dev.nitka.nodewire.client.video.NwCanvasVideoCanvas",
            "dev.nitka.nodewire.client.video.VideoFrameRenderer",
            "dev.nitka.nodewire.client.video.VideoManager",
            "dev.nitka.nodewire.client.video.GlVideoSurface",
            "net.minecraft.client.gui.GuiGraphics",
            "net.minecraft.client.Minecraft",
            "com.mojang.blaze3d.pipeline.TextureTarget",
        )) {
            assertThrows(ClassNotFoundException::class.java, {
                guard.loadClass(denied)
            }, "$denied must be denied by the sandbox loader")
        }
    }
}
