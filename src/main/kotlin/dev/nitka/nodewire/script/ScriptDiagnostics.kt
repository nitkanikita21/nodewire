package dev.nitka.nodewire.script

import net.minecraft.nbt.CompoundTag

/**
 * Reserved sync location for component G (compile-status badge on the script
 * node). Per the design spec §G + blueprint item 3, compile diagnostics ride
 * the node's `config` `CompoundTag` — it is already round-tripped verbatim by
 * `Node.CODEC` on both the save tag and the BlockEntity update tag, so anything
 * stamped here syncs to the client for free and survives the existing graph
 * codec with zero `Node`/`Codec` churn.
 *
 * The keys are namespaced with a `__diag` prefix so they are inert to pin
 * reshape (`HeaderLexer` reads only `input`/`output` declarations; `pinsFor`
 * reads only `src`) and do not change `src`, so stamping them does not churn the
 * compile cache key.
 *
 * The server WRITE (component G, Option A) stamps these keys from
 * `LogicBlockEntity.serverTick`: it polls [ScriptNodeRuntime.statusOf] for each
 * script node and writes [statusToken]/[diagnosticsText] into the node config on
 * change, on the server thread, then fires one block update. The client reads
 * [STATUS_KEY] for the node-card badge and [TEXT_KEY] for the editor status strip.
 */
object ScriptDiagnostics {

    /** Config key carrying the compact status token: one of [STATUS_*]. */
    const val STATUS_KEY = "__diag_status"

    /** Config key carrying the full human-readable diagnostics text. */
    const val TEXT_KEY = "__diag_text"

    const val STATUS_EMPTY = "empty"
    const val STATUS_COMPILING = "compiling"
    const val STATUS_OK = "ok"
    const val STATUS_ERROR = "err"

    /** Map a runtime [ScriptNodeRuntime.Status] to its synced token. */
    fun statusToken(status: ScriptNodeRuntime.Status): String = when (status) {
        ScriptNodeRuntime.Status.Empty -> STATUS_EMPTY
        ScriptNodeRuntime.Status.Compiling -> STATUS_COMPILING
        ScriptNodeRuntime.Status.Ok -> STATUS_OK
        is ScriptNodeRuntime.Status.Error -> STATUS_ERROR
    }

    /** Full diagnostics text for a [ScriptNodeRuntime.Status], or "" when none. */
    fun diagnosticsText(status: ScriptNodeRuntime.Status): String = when (status) {
        is ScriptNodeRuntime.Status.Error -> status.diagnostics.joinToString("\n")
        else -> ""
    }

    /** Read the synced status token from a node's [config], or [STATUS_EMPTY]. */
    fun readStatus(config: CompoundTag): String =
        if (config.contains(STATUS_KEY)) config.getString(STATUS_KEY) else STATUS_EMPTY

    /** Read the synced diagnostics text from a node's [config], or "". */
    fun readText(config: CompoundTag): String =
        if (config.contains(TEXT_KEY)) config.getString(TEXT_KEY) else ""
}
