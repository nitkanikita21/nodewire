package dev.nitka.nodewire.client.script

/**
 * Hardening #3 — the CLIENT kill-switch for auto-running script `clientBehavior`
 * code. Untrusted author code auto-runs on EVERY viewer of a script node, so a
 * viewer MUST be able to stop all client scripts WITHOUT first rendering another
 * frame (a runaway could otherwise starve the render thread before any UI is
 * reachable — though hardening #1's worker + wall-clock budget already keeps the
 * render thread itself non-blocking).
 *
 * - [enabled] is `@Volatile`, default ON, flipped from the `/nodewire
 *   clientscripts <on|off>` client command ([ClientScriptCommand]) — which runs
 *   on the client command thread, NOT inside the render loop.
 * - [ClientScriptDriver] reads [enabled] at the TOP of every frame; when OFF it
 *   cancels all client runtimes and skips the per-node rendezvous entirely.
 */
object ClientScriptToggle {
    @Volatile
    var enabled: Boolean = true
        private set

    /** Flip the kill-switch. When turning OFF, cancel all live client runtimes
     *  immediately so nothing is left parked. Returns the new state. */
    fun set(on: Boolean): Boolean {
        enabled = on
        if (!on) ClientScriptNodeRuntime.cancelAll()
        return on
    }
}
