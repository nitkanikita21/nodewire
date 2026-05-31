package dev.nitka.nodewire.script

/** Deterministic clock seam for unit tests — same as [NwTickClock] but with
 *  an exposed park state so tests can assert "behavior is parked". */
class FakeTickClock {
    val clock = NwTickClock()

    /** Resume parked behaviors (no wait — single-owner advance). */
    fun advance() = clock.advance()

    val fullyParked get() = clock.isNodeFullyParked()
}
