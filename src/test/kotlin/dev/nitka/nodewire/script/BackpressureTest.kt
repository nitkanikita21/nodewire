package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.Pin
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-owner / never-wait regression anchor. A behavior on a REAL off-thread
 * confined dispatcher takes MORE than one tick to reach its next park (it blocks
 * on a test-controlled gate between parks). While it is resumed-but-not-yet-parked
 * the node is NOT fully parked, so [ScriptRuntime.rendezvous] SKIPS it — returning
 * the PREVIOUS frame, touching no buffers, never advancing — and returns
 * IMMEDIATELY (the whole test is under @Timeout to prove the main thread never
 * blocks). Once the behavior finally parks, the next rendezvous LANDS the new frame.
 */
class BackpressureTest {
    private class Slow(private val gate: AtomicReference<CountDownLatch?>) : ScriptModule() {
        var n = 0
        val out = output<Int>("out")
        init {
            behavior {
                while (true) {
                    n += 1
                    out.value = n
                    tick() // commit frame n, then park
                    // After resume: block until the test releases us. During this
                    // window the node is NOT fully parked => the server skips it.
                    gate.get()?.await()
                }
            }
        }
    }

    @Test
    @Timeout(10)
    fun `slow behavior is skipped (last frame reused) then lands the new frame`() {
        val gate = AtomicReference<CountDownLatch?>(null)
        val m = Slow(gate)
        val rt = ScriptRuntime(m, ScriptDispatchers.nodeDispatcher())
        val state = CompoundTag()
        val outPins = listOf(Pin("out", "out", PinType.INT))
        val inputs = emptyMap<String, PinValue>()

        fun read(): Int =
            (rt.rendezvous(inputs, state, outPins)["out"] as PinValue.Int).value

        // Spin the rendezvous until the behavior has parked once and committed frame 1.
        // (On a real pool the first park lands a tick or two after attach — the server
        // SKIPS until then, returning defaults; never blocks.)
        var v = read()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (v != 1 && System.nanoTime() < deadline) {
            Thread.sleep(2)
            v = read()
        }
        assertEquals(1, v, "behavior should have committed and parked frame 1")

        // Arm the gate so the worker, when next resumed past a park, blocks mid-cycle
        // BEFORE reaching its following tick(). Drive rendezvous until the node gets
        // stuck "resumed-not-reparked": then repeated reads return a CONSTANT last-owned
        // frame (the server keeps skipping). Exactly which frame is last-owned depends
        // on async timing, so we don't hardcode it — we assert the backpressure SHAPE.
        val held = CountDownLatch(1)
        gate.set(held)

        // Poll until reads stabilise to a constant value while the worker is blocked.
        // Each read() returns IMMEDIATELY (the @Timeout proves the server never waits).
        var stuckValue = read()
        var stableCount = 0
        val stuckDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (stableCount < 8 && System.nanoTime() < stuckDeadline) {
            val r = read()
            if (r == stuckValue) {
                stableCount++
            } else {
                stuckValue = r
                stableCount = 0
            }
        }
        assertTrue(stableCount >= 8, "reads must stabilise to a constant skip frame while the worker is held")
        assertTrue(stuckValue >= 1, "the last-owned frame is a real committed value, got $stuckValue")

        // Reconfirm the skip is constant and immediate (no advance, reuse last frame).
        val skipReads = (0 until 5).map { read() }
        assertTrue(
            skipReads.all { it == stuckValue },
            "while the behavior is mid-cycle the server skips and reuses the SAME frame, got $skipReads",
        )

        // Release the worker; it loops back to tick() and re-parks, committing a fresher
        // frame. Eventually a rendezvous finds it fully parked and LANDS that frame
        // (strictly greater than the stuck value). Poll (each call returns immediately).
        gate.set(null)
        held.countDown()
        var landed = read()
        val d2 = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (landed <= stuckValue && System.nanoTime() < d2) {
            Thread.sleep(2)
            landed = read()
        }
        assertTrue(landed > stuckValue, "after the slow behavior parks, a fresher frame lands, got $landed (was $stuckValue)")

        rt.cancel()
    }
}
