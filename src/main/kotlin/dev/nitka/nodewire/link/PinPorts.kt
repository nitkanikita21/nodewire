package dev.nitka.nodewire.link

import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.integration.aeronautics.AeroBlockKind
import dev.nitka.nodewire.integration.aeronautics.AeroChannel
import dev.nitka.nodewire.integration.sensor.SensorReading
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.fml.ModList

/**
 * Resolves "what pins does the block at this position have?" — the single
 * lookup both the Channel Link Tool (click side) and [PinLinkEngine]
 * (delivery side) use.
 *
 * Resolution order:
 *  1. The BlockEntity itself implements [PinPort] → that IS the port
 *     (first-party blocks: Logic, Screen, Camera).
 *  2. Otherwise a composite of every matching adapter: Aeronautics channels,
 *     capability sensors (items/fluids/comparator), and the plain-redstone
 *     fallback every non-air block gets. Their pins merge into ONE list, so
 *     e.g. a chest offers ITEM_COUNT *and* `redstone` in the same picker.
 */
object PinPorts {

    /** Port for a clicked/known position, or null when nothing is linkable
     *  there (air). [face] threads through to face-aware fallback inputs. */
    fun at(level: Level, pos: BlockPos, face: Direction? = null): PinPort? {
        val be = level.getBlockEntity(pos)
        if (be is PinPort) return be
        val state = level.getBlockState(pos)
        if (state.isAir) return null

        val parts = buildList {
            if (be != null && ModList.get().isLoaded("aeronautics")) {
                AeroBlockKind.fromBE(be)?.let { add(AeroPort(it, be)) }
            }
            if (be != null) {
                // CBC cannon mount: live yaw/pitch of the mounted cannon.
                dev.nitka.nodewire.integration.cbc.CbcIntegration.mountPortFor(be)?.let(::add)
            }
            if (be != null) {
                val readings = SensorReading.supportedBy(be)
                if (readings.isNotEmpty()) add(SensorPort(readings, be))
            }
            add(RedstonePort(level, pos))
        }
        return if (parts.size == 1) parts[0] else CompositePort(parts)
    }

    /** Engine-side resolve of a [PinLink.source]: Sable-aware via the
     *  endpoint backend, then the same port composition as [at]. Null when
     *  the source is unresolvable (unloaded / gone). */
    fun portFor(level: Level, ref: EndpointRef): PinPort? {
        val be = ref.resolve(level)
        if (be is PinPort) return be
        val pos = be?.blockPos ?: ref.payload.blockPos
        if (!level.isLoaded(pos)) return null
        return at(level, pos)
    }

    // ── adapters ──────────────────────────────────────────────────────────

    /** Merges several adapter ports into one pin namespace. Reads route to
     *  whichever part offered the pin (ids are disjoint by construction:
     *  Aero/Sensor enum NAMES are uppercase, the fallback is lowercase). */
    private class CompositePort(private val parts: List<PinPort>) : PinPort {
        override fun pinOutputs(ctx: LinkContext): List<LinkPin> =
            parts.flatMap { it.pinOutputs(ctx) }

        override fun pinInputs(ctx: LinkContext): List<LinkPin> =
            parts.flatMap { it.pinInputs(ctx) }

        override fun readPin(id: String): PinReading? =
            parts.firstNotNullOfOrNull { it.readPin(id) }
    }

    /** Aeronautics block: every catalog channel for its kind is an output
     *  pin (reflection read, failures degrade to "nothing this tick"). */
    private class AeroPort(private val kind: AeroBlockKind, private val be: BlockEntity) : PinPort {
        override fun pinOutputs(ctx: LinkContext): List<LinkPin> =
            AeroChannel.byKind(kind).map { LinkPin(it.name, it.pinType, it.displayName) }

        override fun readPin(id: String): PinReading? {
            val ch = AeroChannel.fromName(id)?.takeIf { it.kind == kind } ?: return null
            return runCatching { PinReading(ch.read(be)) }.getOrNull()
        }
    }

    /** Capability sensor over an arbitrary container/comparator block. Reads
     *  use the side-less BLOCK capability (same as [SensorReading.supportedBy]). */
    private class SensorPort(
        private val readings: List<SensorReading>,
        private val be: BlockEntity,
    ) : PinPort {
        override fun pinOutputs(ctx: LinkContext): List<LinkPin> =
            readings.map { LinkPin(it.name, it.pinType, it.displayName) }

        override fun readPin(id: String): PinReading? {
            val r = SensorReading.fromName(id)?.takeIf { it in readings } ?: return null
            return runCatching { PinReading(r.read(be, null, null)) }.getOrNull()
        }
    }

    /**
     * The fallback every plain block gets:
     *  * output `redstone` — the block's best-neighbour signal, polled per
     *    tick (a lever, comparator, pressure plate… becomes a source).
     *  * input `redstone@<face>` — drive-by-wire onto that face. This input
     *    has NO [PinPort.writePin] body: the bind packet routes it to the
     *    source LogicBlock's [dev.nitka.nodewire.block.SideBinding] push
     *    path (a stateless adapter can't emit into the signal map itself).
     */
    private class RedstonePort(private val level: Level, private val pos: BlockPos) : PinPort {
        override fun pinOutputs(ctx: LinkContext): List<LinkPin> =
            listOf(LinkPin(REDSTONE_PIN, PinType.REDSTONE))

        override fun pinInputs(ctx: LinkContext): List<LinkPin> {
            val face = ctx.face ?: return emptyList()
            return listOf(
                LinkPin(
                    "$REDSTONE_PIN@${face.name.lowercase()}",
                    PinType.REDSTONE,
                    "redstone ${face.name.lowercase()}",
                ),
            )
        }

        override fun readPin(id: String): PinReading? {
            if (id != REDSTONE_PIN) return null
            return PinReading(PinValue.Redstone(level.getBestNeighborSignal(pos)))
        }
    }

    const val REDSTONE_PIN = "redstone"

    /** The face encoded in a `redstone@<face>` input pin id, or null. */
    fun sideOfRedstoneInput(pinId: String): Direction? {
        if (!pinId.startsWith("$REDSTONE_PIN@")) return null
        return Direction.byName(pinId.substringAfter('@'))
    }
}
