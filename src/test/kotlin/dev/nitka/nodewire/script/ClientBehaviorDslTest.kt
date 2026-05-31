package dev.nitka.nodewire.script

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClientBehaviorDslTest {
    private class M : ScriptModule() {
        var serverRuns = 0
        var clientRuns = 0
        init {
            behavior { serverRuns++; tick() }        // server list
            clientBehavior { clientRuns++; frame() } // client list
        }
    }

    @Test fun `server attach launches only behavior, client attach only clientBehavior`() {
        val mServer = M(); val clkS = NwTickClock()
        mServer.attachScope(CoroutineScope(Dispatchers.Unconfined + clkS.frameClock), clkS, ScriptModule.Side.SERVER)
        assertEquals(1, mServer.serverRuns); assertEquals(0, mServer.clientRuns)

        val mClient = M(); val clkC = NwTickClock()
        mClient.attachScope(CoroutineScope(Dispatchers.Unconfined + clkC.frameClock), clkC, ScriptModule.Side.CLIENT)
        assertEquals(0, mClient.serverRuns); assertEquals(1, mClient.clientRuns)
    }
}
