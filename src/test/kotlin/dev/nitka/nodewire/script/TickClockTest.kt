package dev.nitka.nodewire.script

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TickClockTest {
    @Test
    fun `behavior advances one count per clock advance and reports fully parked`() = runBlocking {
        val clock = NwTickClock()
        var count = 0
        val token = Any() // stand-in for the per-behavior token
        val scope = CoroutineScope(Dispatchers.Unconfined + clock.frameClock)
        clock.onBehaviorLaunched()
        scope.launch {
            repeat(3) {
                count++
                clock.await(token)
            }
            clock.onBehaviorCompleted(token)
        }
        // launched coroutine runs up to the first await(token); count == 1 and it is
        // parked AT THE CURRENT generation (the generation gate, Issue-A fix).
        assertEquals(1, count)
        assertTrue(clock.isNodeFullyParked(), "behavior parked at current generation on first await")
        // advance() bumps the generation and resumes the single awaiter; under
        // Unconfined the resumed body runs inline and re-parks at the NEW generation
        // before advance() returns, so the gate is true again immediately.
        clock.advance(); assertEquals(2, count); assertTrue(clock.isNodeFullyParked())
        clock.advance(); assertEquals(3, count); assertTrue(clock.isNodeFullyParked())
        clock.advance() // coroutine completes (onBehaviorCompleted)
        assertEquals(3, count)
        assertTrue(clock.isNodeFullyParked(), "no launched-not-completed behaviors left")
    }

    /**
     * The mandatory Issue-A regression test on a REAL confined pool. After
     * [NwTickClock.advance] returns, the resumed worker has NOT yet re-parked, so
     * [NwTickClock.isNodeFullyParked] MUST report `false` until the worker re-parks
     * at the new generation. A naive `parked++/parked--` clock would (wrongly) still
     * report fully-parked here (the decrement lands on the worker after advance()),
     * letting the server own a node mid-run. This proves the generation fence.
     *
     * To make the "resumed-but-not-yet-re-parked" window deterministic (not a timing
     * gamble — the worker on a fast machine can re-park before the main thread reads
     * the gate), the worker BLOCKS on [resumeGate] right after resuming and BEFORE its
     * next `await(token)`. The test asserts not-parked while that latch is held, then
     * releases it and observes the gate flip back to true once the worker re-parks.
     */
    @Test
    @Timeout(5)
    fun `off-thread real dispatcher reports not-parked immediately after advance until worker re-parks`(): Unit = runBlocking {
        val clock = NwTickClock()
        val dispatcher = ScriptDispatchers.nodeDispatcher()
        val scope = CoroutineScope(dispatcher + clock.frameClock)
        val token = Any()
        val iterations = AtomicInteger(0)

        // released for the FIRST park only; the second iteration holds the worker
        // running (resumed, not yet re-parked) until the test lets it through.
        val firstParkReady = CountDownLatch(1)
        val resumeGate = AtomicReference<CountDownLatch?>(null)
        val resumedRunning = CountDownLatch(1)

        clock.onBehaviorLaunched()
        scope.launch {
            // iteration 0: signal we are about to park the first time, then park.
            iterations.incrementAndGet()
            firstParkReady.countDown()
            clock.await(token)
            // iteration 1: we have been RESUMED and are now running on the worker but
            // have NOT yet re-parked. Announce it and block on the gate so the test
            // can observe isNodeFullyParked()==false deterministically.
            iterations.incrementAndGet()
            resumedRunning.countDown()
            resumeGate.get()?.await()
            while (true) { clock.await(token); iterations.incrementAndGet() }
        }

        // wait until the worker has truly parked the first time
        firstParkReady.await()
        while (!clock.isNodeFullyParked()) Thread.onSpinWait()
        val before = iterations.get()

        // arm the gate, then resume the worker; it runs its body and blocks on the gate.
        val gate = CountDownLatch(1)
        resumeGate.set(gate)
        // NwTickClock.advance() no-ops if the awaiter has not yet registered with the
        // underlying BroadcastFrameClock (parkedAtGen is recorded just BEFORE
        // withFrameNanos suspends — a benign residual window the production rendezvous
        // simply retries next tick). The test has only ONE advance opportunity, so it
        // retries until the worker is provably resumed.
        while (resumedRunning.count > 0L) {
            clock.advance()
            if (resumedRunning.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)) break
        }
        // ...and is blocked on the gate (not yet re-parked). The fence MUST report false.
        assertFalse(
            clock.isNodeFullyParked(),
            "node must NOT be fully parked while a resumed worker is running, before it re-parks",
        )
        assertTrue(iterations.get() > before, "worker advanced one work-chunk after the resume")

        // let the worker proceed; it re-parks at the new generation → gate flips true.
        gate.countDown()
        while (!clock.isNodeFullyParked()) Thread.onSpinWait()

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
