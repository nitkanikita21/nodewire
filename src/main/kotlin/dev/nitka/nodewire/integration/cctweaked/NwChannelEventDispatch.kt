package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.peripheral.IComputerAccess
import dev.nitka.nodewire.graph.PinValue

/**
 * Compare two snapshots of `channel_output` values and queue a
 * `nodewire_channel` event on each attached computer for every changed
 * or removed channel. A computer flagged `needsInitialSync=true` gets
 * an event for every channel in [new] regardless of [prev].
 */
object NwChannelEventDispatch {

    fun diffAndBroadcast(
        attachments: List<Pair<IComputerAccess, Boolean>>,
        prev: Map<String, PinValue>,
        new: Map<String, PinValue>,
    ) {
        if (attachments.isEmpty()) return

        // Channels present in `new` — emit when value differs OR when
        // initial sync is requested.
        for ((name, value) in new) {
            val before = prev[name]
            val differs = before == null || before != value
            for ((computer, needsInitial) in attachments) {
                if (needsInitial || differs) {
                    computer.queueEvent("nodewire_channel", name, NwChannelLuaCodec.toLua(value))
                }
            }
        }
        // Channels present in `prev` but not `new` — emit with nil.
        // Skipped during initial sync (a freshly-attached computer
        // shouldn't see channels that were never there).
        for (name in prev.keys) {
            if (name in new) continue
            for ((computer, needsInitial) in attachments) {
                if (needsInitial) continue
                computer.queueEvent("nodewire_channel", name, null)
            }
        }
    }
}
