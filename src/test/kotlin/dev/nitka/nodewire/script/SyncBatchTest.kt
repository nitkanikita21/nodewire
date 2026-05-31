package dev.nitka.nodewire.script

import dev.nitka.nodewire.graph.PinValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `sync { }` (and the underlying writes-buffer / commit-at-suspend) batches every
 * write between two suspends into ONE frame, last-write-wins (spec D4/D7). Several
 * `out.value =` writes inside one `sync{}` block must produce a single committed
 * frame holding only the LAST value — not one frame per write.
 */
class SyncBatchTest {
    private class Mod : ScriptModule() {
        val out = output<Int>("out")
        init {
            behavior {
                sync {
                    out.value = 1
                    out.value = 2
                    out.value = 3
                }
            }
        }
    }

    @Test
    fun `three writes in one sync block commit one frame, last-write-wins`() = runBlocking {
        val m = Mod()
        val clock = NwTickClock()
        m.attachScope(CoroutineScope(Dispatchers.Unconfined + clock.frameClock), clock)

        // Under Unconfined the behavior runs inline up to its first park: the sync{}
        // block executes all three writes, commitFrame() snapshots them as ONE frame.
        val frame = m.pullOutputs()
        assertEquals(PinValue.Int(3), frame["out"], "the committed frame holds only the last write")
    }

    @Test
    fun `successive ticks each commit one frame from the buffered writes`() = runBlocking {
        // A behavior that writes once per tick — proves each suspend commits exactly
        // the buffer state at that suspend (a frame per tick, not per write).
        val seen = ArrayList<Int>()
        val m = object : ScriptModule() {
            val out = output<Int>("out")
            init {
                behavior {
                    var i = 0
                    while (true) {
                        i += 1
                        out.value = i * 10
                        tick()
                    }
                }
            }
        }
        val clock = NwTickClock()
        m.attachScope(CoroutineScope(Dispatchers.Unconfined + clock.frameClock), clock)

        seen += (m.pullOutputs()["out"] as PinValue.Int).value // committed at first park
        repeat(3) {
            clock.advance()
            seen += (m.pullOutputs()["out"] as PinValue.Int).value
        }
        assertEquals(listOf(10, 20, 30, 40), seen)
    }
}
