package dev.nitka.nodewire.client.script

import dev.nitka.nodewire.script.ScriptDispatchers
import dev.nitka.nodewire.script.ScriptMessage
import dev.nitka.nodewire.script.ScriptModule
import dev.nitka.nodewire.script.ScriptRuntime
import kotlinx.coroutines.Dispatchers
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The CLIENT-frame rendezvous on [ScriptRuntime.clientRendezvous]: a
 * `clientBehavior {}` runs on the frame clock, READS a replicated cell staged
 * into the module each frame, and `log()`s it to the passed message sink (→
 * [ClientScriptLog] in production). Plus hardening #1: a runaway clientBehavior
 * is disabled under the wall-clock resume budget on a REAL off-thread dispatcher,
 * without the rendezvous ever blocking (@Timeout proves it).
 *
 * Pure — no `Minecraft`, no networking. The replicated state is injected as a
 * [CompoundTag]; logs are captured via the sink lambda.
 */
class ClientScriptRuntimeTest {

    /** Reads a replicated Int cell `hp` and logs it each frame. */
    private class Reader : ScriptModule() {
        var hp by state(0, replicated = true)
        init {
            clientBehavior {
                while (true) {
                    log("hp=$hp")
                    frame()
                }
            }
        }
    }

    @Test
    @Timeout(10)
    fun `clientBehavior reads replicated state each frame and logs it`() {
        val m = Reader().also { it.setClientSide(true) }
        // Unconfined → the behavior runs inline to its first park, so each
        // clientRendezvous owns the node and advances exactly one frame.
        val rt = ScriptRuntime(m, Dispatchers.Unconfined, side = ScriptModule.Side.CLIENT)

        val logs = ArrayList<String>()
        val sink: (List<ScriptMessage>) -> Unit = { msgs -> ClientScriptLog.drain(msgs) }
        ClientScriptLog.testSink = { logs += it }
        try {
            // Frame 1: staged hp=5; the behavior's FIRST log() (hp=0, init) was
            // emitted before any state load — drained on this owned frame. Then
            // advance resumes it; it reads the freshly-loaded hp=5 and logs that.
            rt.clientRendezvous(CompoundTag().apply { putInt("hp", 5) }, sink)
            rt.clientRendezvous(CompoundTag().apply { putInt("hp", 7) }, sink)
            rt.clientRendezvous(CompoundTag().apply { putInt("hp", 9) }, sink)
        } finally {
            ClientScriptLog.testSink = null
        }

        // The behavior observed the staged replicated values across frames.
        assertTrue(logs.contains("hp=5"), "should have logged staged hp=5, got $logs")
        assertTrue(logs.contains("hp=7"), "should have logged staged hp=7, got $logs")
        rt.cancel()
    }

    /** A clientBehavior that parks once then spins forever — never re-parks. */
    private class Runaway : ScriptModule() {
        @Volatile var escape = false
        init {
            clientBehavior {
                frame() // park once
                while (!escape) { /* no suspend → never re-parks */ }
            }
        }
    }

    @Test
    @Timeout(10)
    fun `runaway clientBehavior is disabled under the wall-clock budget`() {
        val m = Runaway()
        // Real off-thread dispatcher so the runaway pins a WORKER, not the caller.
        // Tight budget (5 ms) → disabled fast; huge strike ceiling so the BUDGET
        // (not the strike count) is what trips.
        val rt = ScriptRuntime(
            m,
            ScriptDispatchers.nodeDispatcher(),
            maxStrikes = 1_000_000,
            side = ScriptModule.Side.CLIENT,
            resumeBudgetMs = 5,
        )
        val sink: (List<ScriptMessage>) -> Unit = { /* discard */ }

        // First frame: parks once (inline first attach drives to the first frame()),
        // then advance resumes it → it spins. Subsequent frames SKIP (not parked);
        // once wall-clock since the resume exceeds the budget, it disables.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!rt.disabled && System.nanoTime() < deadline) {
            rt.clientRendezvous(CompoundTag(), sink)
        }

        assertTrue(rt.disabled, "runaway client node must be disabled by the wall-clock budget")
        m.escape = true
        rt.cancel()
    }

    @Test
    fun `non-replicated read on the client throws inside a client behavior`() {
        // Direct module-level proof that the client-side flag drives the throw
        // (the runtime sets it before attach; here we assert the surface).
        class M : ScriptModule() {
            var local by state(0, replicated = false)
        }
        val m = M().also { it.setClientSide(true) }
        val ex = runCatching { @Suppress("UNUSED_EXPRESSION") m.local }.exceptionOrNull()
        assertEquals(true, ex?.message?.contains("not replicated"))
    }
}
