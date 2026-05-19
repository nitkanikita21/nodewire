package dev.nitka.nodewire

import com.mojang.logging.LogUtils

/**
 * NeoForge runs Kotlin under strict JPMS: KFF puts `kotlin.stdlib` and
 * `kotlinx.coroutines.core` in separate modules on its plugin layer, but
 * `kotlin.stdlib`'s `DebugProbesKt.probeCoroutineCreated` directly calls
 * into `kotlinx.coroutines.debug.internal.DebugProbesImpl`. That call
 * needs a `reads` edge that the stdlib's module-info doesn't declare,
 * so every `launch {}` throws `IllegalAccessError` at runtime.
 *
 * The fix usually proposed (`--add-reads=kotlin.stdlib=kotlinx.coroutines.core`
 * on the JVM command line) doesn't reach KFF's plugin layer — module
 * arguments only apply to the boot layer's modules, and these modules
 * are loaded into a child layer at runtime.
 *
 * Instead we open the edge programmatically via `Module.implAddReads`,
 * accessed reflectively. This requires `--add-opens=java.base/java.lang=
 * ALL-UNNAMED` on the boot JVM args (configured in build.gradle.kts).
 *
 * Called once from [Nodewire.init], before any code in this mod issues a
 * `launch {}`.
 */
internal object JpmsBridge {
    private val LOG = LogUtils.getLogger()

    fun openCoroutinesDebugBridge() {
        try {
            val stdlibModule = kotlin.Unit::class.java.module
            val coroutinesModule = kotlinx.coroutines.Job::class.java.module
            if (stdlibModule.canRead(coroutinesModule)) return

            val implAddReads = Module::class.java.getDeclaredMethod("implAddReads", Module::class.java)
            implAddReads.isAccessible = true
            implAddReads.invoke(stdlibModule, coroutinesModule)
            LOG.info("Granted kotlin.stdlib -> kotlinx.coroutines.core reads edge for DebugProbes")
        } catch (t: Throwable) {
            LOG.warn("Failed to open kotlin.stdlib reads of kotlinx.coroutines.core; launch{{}} may IllegalAccessError", t)
        }
    }
}
