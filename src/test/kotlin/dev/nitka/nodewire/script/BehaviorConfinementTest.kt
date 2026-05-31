package dev.nitka.nodewire.script

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two behaviors of one node mutate a shared PLAIN var deterministically — they
 * interleave only at suspend points (cooperative within a node), so no lock is
 * needed (spec D5). Under Unconfined resumes are inline, so the result is exact.
 */
class BehaviorConfinementTest {
    private class Mod : ScriptModule() {
        var shared = 0
        init {
            behavior { repeat(3) { shared += 10; tick() } }
            behavior { repeat(3) { shared += 1; tick() } }
        }
    }

    @Test
    fun `two behaviors mutate a shared var deterministically`() = runBlocking {
        val m = Mod()
        val clock = NwTickClock()
        m.attachScope(CoroutineScope(Dispatchers.Unconfined + clock.frameClock), clock)
        assertEquals(11, m.shared, "both behaviors run once before first park")
        repeat(2) { clock.advance() }
        assertEquals(33, m.shared)
    }
}
