package dev.nitka.nodewire

import com.mojang.logging.LogUtils

/**
 * Adds a `reads` edge from `kotlin.stdlib` to `kotlinx.coroutines.core` at
 * runtime so the IDE's coroutines-debug agent (which retransforms
 * `DebugProbesKt` inside `kotlin.stdlib` to call into
 * `kotlinx.coroutines.debug.internal.DebugProbesImpl`) doesn't blow up every
 * `launch{}` with `IllegalAccessError`.
 *
 * In NeoForge, all three modules — `kotlin.stdlib`, `kotlinx.coroutines.core`,
 * and our mod `nodewire` — live in KFF's PLUGIN layer. JVM-level
 * `--add-reads` / `--add-opens` flags only mutate boot-layer modules, so they
 * can't open `Module.implAddReads` to a PLUGIN-layer module by name.
 * `--add-opens=java.base/java.lang=ALL-UNNAMED` does work, but only for
 * UNNAMED modules — our mod is loaded as a named module.
 *
 * The fix: run the privileged reflection from code that lives in an UNNAMED
 * module. We define [JpmsBridgeHelper] in a fresh anonymous [ClassLoader]'s
 * unnamed module by reading its bytecode directly from the mod jar and
 * calling `defineClass`. The helper then performs the `implAddReads` call;
 * because its caller-module is unnamed, `setAccessible(true)` is honored.
 */
internal object JpmsBridge {
    private val LOG = LogUtils.getLogger()

    fun openCoroutinesDebugBridge() {
        try {
            val helperFqn = "dev.nitka.nodewire.JpmsBridgeHelper"
            val resourcePath = helperFqn.replace('.', '/') + ".class"
            val parent = JpmsBridge::class.java.classLoader
            val bytes = parent.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                ?: error("$resourcePath not found on mod classloader")

            // Anonymous ClassLoader → its defined classes land in its UNNAMED
            // module. Parent delegation gives the helper access to plugin-layer
            // classes (kotlin.stdlib, kotlinx.coroutines.core) via the mod's
            // classloader at lookup time, but does NOT pre-load the helper from
            // there — we only ever define-by-bytecode here.
            val unnamedLoader = object : ClassLoader(parent) {
                fun defineHelper(name: String, b: ByteArray): Class<*> =
                    defineClass(name, b, 0, b.size)
            }
            val helper = unnamedLoader.defineHelper(helperFqn, bytes)
            check(!helper.module.isNamed) {
                "JpmsBridgeHelper landed in named module ${helper.module.name}; --add-opens won't apply"
            }

            helper.getMethod("bridge").invoke(null)
            LOG.info("JpmsBridge: kotlin.stdlib <-> kotlinx.coroutines.core reads edge installed")
        } catch (t: Throwable) {
            LOG.warn(
                "JpmsBridge: failed to install reads edge. " +
                    "If running with IDE debugger, disable the Kotlin coroutine agent " +
                    "(Settings → Build/Debugger → Async Stack Trace) or launch via terminal.",
                t,
            )
        }
    }
}
