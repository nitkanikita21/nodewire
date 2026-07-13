package dev.nitka.nodewire.link

import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.PinValueConversion
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * The per-link PULL body shared by every pin-link host. Given one link
 * (source/target [EndpointRef]s, the two pin ids, and the link's event-pin
 * latch), it resolves both ports through [PinPorts.portFor] and applies the
 * exact delivery semantics the inline [PinLinkEngine] loop used to:
 *
 *  1. PRUNE links whose target pin vanished, whose source is loaded but plainly
 *     gone (air), or whose source pin disappeared / stopped type-converting.
 *     Unloaded endpoints are tolerated (the pin just goes [Outcome.Quiet]) —
 *     breaking a binding because a chunk unloaded would surprise the user.
 *  2. PULL: [PinPort.readPin] the source pin, [PinPort.writePin] the raw value
 *     into the target's input pin. Event pins ([PinReading.pulseStamp]) latch
 *     per link and deliver a 1-tick pulse.
 *
 * It does NOT own the link list or the per-target clear bookkeeping — the
 * caller prunes on [Outcome.Prune] and diffs [Outcome.Delivered] pins against
 * last tick to [PinPort.clearPin] the ones that went silent. Two hosts call it:
 * [PinLinkEngine] (consumer-hosted — the sink IS the target port) and the
 * level-hosted host-less engine (foreign→foreign links).
 */
object PinLinkDelivery {

    private val LOG = com.mojang.logging.LogUtils.getLogger()

    /** Per-link delivery diagnostics, one line per link every N ticks.
     *  Cheap (no allocation on quiet links); grep `NW-LINK`. */
    private const val DIAG_PERIOD_TICKS = 100L

    private const val MAX_LINK_DISTANCE_SQ =
        PinLinkEngine.MAX_LINK_DISTANCE * PinLinkEngine.MAX_LINK_DISTANCE

    /** What pulling one link this tick decided. */
    sealed interface Outcome {
        /** The source produced [value] and it was written into the target's
         *  input pin; [seenStamp] is the (possibly advanced) event-pin latch
         *  the caller must store back on the link. */
        data class Delivered(val value: PinValue, val seenStamp: Long) : Outcome

        /** Nothing to deliver (out of range, source unloaded / unresolved, a
         *  null read, or a quiet event pin) — leave the target's last value. */
        data object Quiet : Outcome

        /** The link is plainly dead (target pin gone, source is air, or the
         *  source pin vanished / no longer type-converts) — drop it. */
        data object Prune : Outcome
    }

    /**
     * Pull one link: resolve [source] → [sourcePin], range-gate against
     * [target], type-check into [targetPin], read + latch event pins, and on
     * success [PinPort.writePin] the value into the target's port. [seenStamp]
     * is the caller's current per-link latch (see [PinReading.pulseStamp]).
     */
    fun deliver(
        level: Level,
        source: EndpointRef,
        sourcePin: String,
        target: EndpointRef,
        targetPin: String,
        seenStamp: Long,
    ): Outcome {
        val diag = level.gameTime % DIAG_PERIOD_TICKS == 0L
        val tgtShort = target.payload.blockPos.toShortString()

        // Target port + pin must still exist. An unresolvable target means its
        // chunk is just unloaded (go quiet); a resolved target that no longer
        // offers the pin is a dead link (prune).
        val targetPort = PinPorts.portFor(level, target) ?: return Outcome.Quiet
        // The redstone fallback's input pins are face-scoped — recover the face
        // from the pin id so re-enumeration offers them (no clicked face here).
        val tgtFace = PinPorts.sideOfRedstoneInput(targetPin)
        val tgtPin = targetPort.pinInputs(ctxFor(level, target, tgtFace)).firstOrNull { it.id == targetPin }
        if (tgtPin == null) {
            LOG.info("NW-LINK prune @{}: target pin '{}' gone", tgtShort, targetPin)
            return Outcome.Prune
        }

        val port = PinPorts.portFor(level, source)
        if (port == null) {
            // Unresolvable: prune only when the source position is loaded and
            // plainly not linkable any more; otherwise just go quiet.
            val pos = source.payload.blockPos
            if (level.isLoaded(pos) && level.getBlockState(pos).isAir) {
                LOG.info("NW-LINK prune @{}: source {} is air", tgtShort, pos.toShortString())
                return Outcome.Prune
            }
            if (diag) {
                LOG.info(
                    "NW-LINK @{}: '{}'<-'{}' source {} UNRESOLVED (backend={}, loaded={})",
                    tgtShort, targetPin, sourcePin,
                    pos.toShortString(), source.backendId, level.isLoaded(pos),
                )
            }
            return Outcome.Quiet
        }

        // Range gate: too far apart → go quiet (don't deliver, don't prune;
        // structures move, so the link may come back into range).
        val srcCenter = centerOf(level, source)
        val tgtCenter = centerOf(level, target)
        if (srcCenter.distanceToSqr(tgtCenter) > MAX_LINK_DISTANCE_SQ) {
            if (diag) {
                LOG.info(
                    "NW-LINK @{}: '{}'<-'{}' OUT OF RANGE ({}m > {}m)",
                    tgtShort, targetPin, sourcePin,
                    "%.1f".format(Math.sqrt(srcCenter.distanceToSqr(tgtCenter))), PinLinkEngine.MAX_LINK_DISTANCE,
                )
            }
            return Outcome.Quiet
        }

        val srcPin = port.pinOutputs(ctxFor(level, source)).firstOrNull { p -> p.id == sourcePin }
        if (srcPin == null || !PinValueConversion.canConvert(srcPin.type, tgtPin.type)) {
            LOG.info(
                "NW-LINK prune @{}: source pin '{}' {} (port={})",
                tgtShort, sourcePin,
                if (srcPin == null) "missing" else "type ${srcPin.type} !> ${tgtPin.type}",
                port.javaClass.simpleName,
            )
            return Outcome.Prune
        }

        val reading = port.readPin(sourcePin)
        if (reading == null) {
            if (diag) {
                LOG.info(
                    "NW-LINK @{}: '{}'<-'{}' read NULL (port={})",
                    tgtShort, targetPin, sourcePin, port.javaClass.simpleName,
                )
            }
            return Outcome.Quiet
        }
        if (diag) {
            LOG.info(
                "NW-LINK @{}: '{}' <- '{}' = {}",
                tgtShort, targetPin, sourcePin, reading.value,
            )
        }

        var stamp = seenStamp
        val value = if (reading.pulseStamp >= 0L) {
            // Event pin: deliver once per stamp change, default otherwise.
            val fresh = reading.pulseStamp > stamp
            if (fresh) stamp = reading.pulseStamp
            if (fresh) reading.value else quiescent(reading.value, srcPin.type)
        } else {
            reading.value
        }
        targetPort.writePin(targetPin, value)
        return Outcome.Delivered(value, stamp)
    }

    /** Sub-level-aware world centre of an endpoint, or its block centre. */
    private fun centerOf(level: Level, ref: EndpointRef): Vec3 =
        ref.worldCenter(level) ?: Vec3.atCenterOf(ref.payload.blockPos)

    /** Server-side re-enumeration context for an endpoint's port. [face] only
     *  matters for face-scoped fallback pins (`redstone@<face>`) — recovered
     *  from the pin id, since the delivery side has no clicked face. */
    private fun ctxFor(level: Level, ref: EndpointRef, face: net.minecraft.core.Direction? = null): LinkContext {
        val pos = ref.payload.blockPos
        return LinkContext(level, pos, level.getBlockState(pos), face)
    }

    private fun quiescent(firedValue: PinValue, declared: PinType): PinValue =
        when (firedValue) {
            is PinValue.Bool -> PinValue.Bool(false)
            else -> PinValue.default(declared)
        }
}
