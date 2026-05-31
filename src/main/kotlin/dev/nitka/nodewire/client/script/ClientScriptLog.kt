package dev.nitka.nodewire.client.script

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.script.MessageKind
import dev.nitka.nodewire.script.ScriptMessage

/**
 * Client-side drain target for a client behavior's `log()` / `chat()`.
 *
 * - `LOG` → the CLIENT console (SLF4J), mirroring how the server drains into
 *   [dev.nitka.nodewire.script.ScriptMessageSink] → the mod log.
 * - `CHAT` → IGNORED. A client behavior cannot author server chat (the client
 *   is not authoritative); `chat()` from the client is a deliberate no-op.
 *
 * The [testSink] override keeps this unit-testable without a running client
 * (no `Minecraft.getInstance()`), the same seam pattern as
 * `ScriptNodeRuntime.compileExecutor` / `nodeDispatcher`.
 */
object ClientScriptLog {
    private val LOG = LogUtils.getLogger()

    /** Test hook — null in production (→ real logger). */
    @Volatile
    var testSink: ((String) -> Unit)? = null

    /** Forward LOG messages to the client console; drop CHAT messages. */
    fun drain(messages: List<ScriptMessage>) {
        if (messages.isEmpty()) return
        for (m in messages) {
            if (m.kind != MessageKind.LOG) continue // CHAT ignored client-side
            val sink = testSink
            if (sink != null) sink(m.text) else LOG.info("[NW client-script] {}", m.text)
        }
    }
}
