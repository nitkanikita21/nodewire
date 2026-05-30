package dev.nitka.nodewire.scripting

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.script.ScriptCompilerRegistry
import dev.nitka.nodewire.script.host.ScriptHost
import kotlin.script.experimental.api.ResultWithDiagnostics
import net.neoforged.fml.common.Mod
import org.slf4j.Logger

/**
 * Entrypoint for the optional `nodewire_scripting` addon mod.
 *
 * Its sole job is to plug the Kotlin-compiler-backed [ScriptHost] into core's
 * [ScriptCompilerRegistry] at mod-construction time. Once registered, core's
 * Script Node can compile in-world scripts. When this addon is absent, the
 * registry stays empty and the Script Node degrades to read-only — core never
 * references the compiler itself.
 */
@Mod(NodewireScripting.ID)
object NodewireScripting {
    const val ID = "nodewire_scripting"
    private val LOG: Logger = LogUtils.getLogger()

    init {
        ScriptCompilerRegistry.compiler = ScriptHost
        LOG.info("Nodewire Scripting loaded — script compiler registered")

        // Layer-B JPMS validation probe. Gated behind the `nodewire.scriptprobe`
        // system property so it NEVER runs in production — only the dev run sets
        // it. Run HERE in mod construction (the earliest phase) so NW-SCRIPT-PROBE
        // is logged BEFORE later mod-loading steps (e.g. the dev environment's
        // unrelated attribute-baking race) can abort the launch. The compiler
        // needs no world/registries — just a Kotlin string — so it is safe this
        // early, and this is the first moment the shaded compiler runs under
        // NeoForge's module layer. A human reads NW-SCRIPT-PROBE in the log.
        if (System.getProperty("nodewire.scriptprobe") == "true") {
            runProbe()
        }
    }

    private fun runProbe() {
        try {
            when (val result = ScriptHost.evalSource("1 + 41")) {
                is ResultWithDiagnostics.Success ->
                    LOG.info("NW-SCRIPT-PROBE ok: 1+41 = {}", result.value)
                is ResultWithDiagnostics.Failure ->
                    LOG.error("NW-SCRIPT-PROBE FAILED: {}", result.reports.joinToString { it.message })
            }
        } catch (t: Throwable) {
            LOG.error("NW-SCRIPT-PROBE THREW", t)
        }
    }
}
