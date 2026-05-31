package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Runaway guard on a REAL off-thread dispatcher. A behavior that parks once then
 * spins forever with NO further suspend never re-parks, so the node is never fully
 * parked, so [ScriptRuntime.rendezvous] ALWAYS skips it (it never waits, never
 * hangs — the @Timeout proves the main thread is never blocked). After [maxStrikes]
 * consecutive not-parked ticks the node is disabled, rendezvous returns type-
 * defaults, and a diagnostic is stamped. This is the structural proof that a
 * runaway can never deadlock the server tick (spec D9/R10).
 *
 * The spin loop checks a cooperative [escape] flag so the test can free the pinned
 * pool thread after the assertions (a true production runaway leaks one bounded
 * pool slot — accepted, spec R4 — but the shared test pool must not starve later
 * tests).
 */
class RunawayGuardTest {
    private class Runaway : ScriptModule() {
        @Volatile var escape = false
        val out = output<Int>("out")
        init {
            behavior {
                tick() // park once, commit an initial frame
                while (!escape) { /* no suspend → never re-parks */ }
            }
        }
    }

    @Test
    @Timeout(10)
    fun `no-suspend runaway is always skipped then disabled (never hangs)`() {
        val m = Runaway()
        val maxStrikes = 8
        val rt = ScriptRuntime(m, ScriptDispatchers.nodeDispatcher(), maxStrikes = maxStrikes)
        val state = CompoundTag()
        val outPins = listOf(Pin("out", "out", PinType.INT))
        val inputs = emptyMap<String, PinValue>()

        ScriptMessageSink.drain() // clear any residue from earlier tests on this thread

        fun read(): Int =
            (rt.rendezvous(inputs, state, outPins)["out"] as PinValue.Int).value

        // Drive the rendezvous well past maxStrikes. Each call returns IMMEDIATELY
        // (the @Timeout would trip if the server ever blocked on the spinning worker).
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!rt.disabled && System.nanoTime() < deadline) {
            read()
        }

        assertTrue(rt.disabled, "runaway node must be disabled after maxStrikes not-parked ticks")

        // Once disabled, rendezvous returns type-defaults (INT zero), no hang.
        assertEquals(0, read())

        // A runaway diagnostic was stamped into the message sink.
        val msgs = ScriptMessageSink.drain()
        assertTrue(
            msgs.any { it.text.contains("runaway") },
            "a runaway diagnostic must be stamped, got $msgs",
        )

        // Free the pinned pool thread so it does not starve later tests.
        m.escape = true
        rt.cancel()
    }
}
