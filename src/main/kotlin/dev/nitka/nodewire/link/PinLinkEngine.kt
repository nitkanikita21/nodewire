package dev.nitka.nodewire.link

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.PinValueConversion
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity

/**
 * The single delivery loop behind every [PinLink]. Called from each sink's
 * server ticker (LogicBlock / ScreenBlock / CameraBlock):
 *
 *  1. PRUNE links whose source is loaded but no longer offers the pin, or
 *     whose target pin vanished / stopped type-converting. Unloaded sources
 *     are tolerated (the pin just goes quiet) — breaking a binding because a
 *     chunk unloaded would surprise the user.
 *  2. PULL: resolve each source port, [PinPort.readPin] the source pin,
 *     [PinPort.writePin] the raw value into the sink's input pin. Event pins
 *     ([PinReading.pulseStamp]) latch per link and deliver a 1-tick pulse.
 *  3. CLEAR pins that were fed last tick but not this one
 *     ([PinPort.clearPin]) so last-writer-wins slots never hold stale values
 *     after an unlink/source loss.
 */
object PinLinkEngine {

    private val LOG = com.mojang.logging.LogUtils.getLogger()

    /** Per-link delivery diagnostics, one line per link every N ticks.
     *  Cheap (no allocation on quiet sinks); grep `NW-LINK`. */
    private const val DIAG_PERIOD_TICKS = 100L

    /** Per-server-tick pull for one sink BE. No-ops unless [be] is a [PinLinkSink]. */
    fun tick(level: Level, be: BlockEntity) {
        val sink = be as? PinLinkSink ?: return
        val links = sink.pinLinks()
        val scratch = sink.pinLinkScratch
        if (links.isEmpty() && scratch.lastDelivered.isEmpty()) return
        val diag = level.gameTime % DIAG_PERIOD_TICKS == 0L

        val ctx = LinkContext(level, be.blockPos, be.blockState)
        val inputsById = sink.pinInputs(ctx).associateBy { it.id }

        val delivered = HashSet<String>()
        var changed = false
        val it = links.iterator()
        while (it.hasNext()) {
            val link = it.next()
            // Target pin must still exist on the sink.
            val tgtPin = inputsById[link.targetPin]
            if (tgtPin == null) {
                LOG.info("NW-LINK prune @{}: target pin '{}' gone", be.blockPos.toShortString(), link.targetPin)
                it.remove(); changed = true; continue
            }
            val port = PinPorts.portFor(level, link.source)
            if (port == null) {
                // Unresolvable: prune only when the position is loaded and
                // plainly not linkable any more; otherwise just go quiet.
                val pos = link.source.payload.blockPos
                if (level.isLoaded(pos) && level.getBlockState(pos).isAir) {
                    LOG.info("NW-LINK prune @{}: source {} is air", be.blockPos.toShortString(), pos.toShortString())
                    it.remove(); changed = true
                } else if (diag) {
                    LOG.info(
                        "NW-LINK @{}: '{}'<-'{}' source {} UNRESOLVED (backend={}, loaded={})",
                        be.blockPos.toShortString(), link.targetPin, link.sourcePin,
                        pos.toShortString(), link.source.backendId, level.isLoaded(pos),
                    )
                }
                continue
            }
            val srcPin = port.pinOutputs(ctx).firstOrNull { p -> p.id == link.sourcePin }
            if (srcPin == null || !PinValueConversion.canConvert(srcPin.type, tgtPin.type)) {
                LOG.info(
                    "NW-LINK prune @{}: source pin '{}' {} (port={})",
                    be.blockPos.toShortString(), link.sourcePin,
                    if (srcPin == null) "missing" else "type ${srcPin.type} !> ${tgtPin.type}",
                    port.javaClass.simpleName,
                )
                it.remove(); changed = true; continue
            }
            val reading = port.readPin(link.sourcePin)
            if (reading == null) {
                if (diag) {
                    LOG.info(
                        "NW-LINK @{}: '{}'<-'{}' read NULL (port={})",
                        be.blockPos.toShortString(), link.targetPin, link.sourcePin, port.javaClass.simpleName,
                    )
                }
                continue
            }
            if (diag) {
                LOG.info(
                    "NW-LINK @{}: '{}' <- '{}' = {}",
                    be.blockPos.toShortString(), link.targetPin, link.sourcePin, reading.value,
                )
            }
            val value = if (reading.pulseStamp >= 0L) {
                // Event pin: deliver once per stamp change, default otherwise.
                val fresh = reading.pulseStamp > link.seenStamp
                if (fresh) link.seenStamp = reading.pulseStamp
                if (fresh) reading.value else quiescent(reading.value, srcPin.type)
            } else {
                reading.value
            }
            sink.writePin(link.targetPin, value)
            delivered.add(link.targetPin)
        }

        // Pins fed last tick but silent now → reset so e.g. a Screen blanks
        // when its video link is removed or its camera disappears.
        for (pin in scratch.lastDelivered) {
            if (pin !in delivered) sink.clearPin(pin)
        }
        scratch.lastDelivered = delivered

        if (changed) {
            sink.onPinLinksChanged()
            level.sendBlockUpdated(be.blockPos, be.blockState, be.blockState, Block.UPDATE_CLIENTS)
        }
    }

    /**
     * Validated add, shared by the bind packet: target pin must exist on the
     * sink and [srcType] must convert into it. Re-binding the same
     * (source pos, target pin) pair replaces the old link instead of
     * duplicating. Returns false for non-sink BEs too.
     */
    fun addLink(level: Level, be: BlockEntity, link: PinLink, srcType: PinType): Boolean {
        val sink = be as? PinLinkSink ?: return false
        val ctx = LinkContext(level, be.blockPos, be.blockState)
        val tgtPin = sink.pinInputs(ctx).firstOrNull { it.id == link.targetPin } ?: return false
        if (!PinValueConversion.canConvert(srcType, tgtPin.type)) return false
        sink.pinLinks().removeAll {
            it.targetPin == link.targetPin &&
                it.source.payload.blockPos == link.source.payload.blockPos
        }
        sink.pinLinks().add(link)
        sink.onPinLinksChanged()
        return true
    }

    /** Remove one exact link tuple from [be]. Returns true when found. */
    fun removeLink(be: BlockEntity, source: dev.nitka.nodewire.endpoint.EndpointRef, sourcePin: String, targetPin: String): Boolean {
        val sink = be as? PinLinkSink ?: return false
        val removed = sink.pinLinks().removeAll {
            it.targetPin == targetPin && it.sourcePin == sourcePin &&
                it.source.payload.blockPos == source.payload.blockPos
        }
        if (removed) sink.onPinLinksChanged()
        return removed
    }

    private fun quiescent(firedValue: PinValue, declared: PinType): PinValue =
        when (firedValue) {
            is PinValue.Bool -> PinValue.Bool(false)
            else -> PinValue.default(declared)
        }
}
