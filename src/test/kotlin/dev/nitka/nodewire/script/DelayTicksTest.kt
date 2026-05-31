package dev.nitka.nodewire.script

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `delay(n.ticks)` parks for exactly n server-tick advances (spec D3). */
class DelayTicksTest {
    private class Mod : ScriptModule() {
        var resumed = false
        init { behavior { delay(5.ticks); resumed = true } }
    }

    @Test
    fun `delay(5 ticks) resumes after exactly 5 advances`() = runBlocking {
        val m = Mod()
        val clock = NwTickClock()
        m.attachScope(CoroutineScope(Dispatchers.Unconfined + clock.frameClock), clock)
        repeat(4) { clock.advance() }
        assertEquals(false, m.resumed, "must still be parked after 4 ticks")
        clock.advance()
        assertEquals(true, m.resumed, "resumes on the 5th tick")
    }
}
