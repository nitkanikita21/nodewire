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

        // Writes/clears must reach the part that owns the pin (e.g. a CBC mount's
        // target_pitch). Pin ids are disjoint by construction, so forwarding to
        // every part is safe — only the owner reacts, the rest no-op. Without
        // this, delivery into a composite-wrapped foreign target silently drops.
        override fun writePin(id: String, value: PinValue) =
            parts.forEach { it.writePin(id, value) }

        override fun clearPin(id: String) =
            parts.forEach { it.clearPin(id) }
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
     * The redstone fallback:
     *  * output `redstone` — ONLY on blocks that actually emit redstone
     *    ([net.minecraft.world.level.block.state.BlockState.isSignalSource]:
     *    levers, buttons, comparators, redstone blocks, pressure plates,
     *    observers, redstone wire…). Reads the block's strongest EMITTED signal
     *    across all faces — not the incoming neighbour signal, which is 0 on a
     *    source. Plain decoration blocks no longer offer a redstone source.
     *  * input `redstone@<face>` — drive-by-wire onto that face, available on
     *    EVERY block (you can power a lamp, door, any block). A LogicBlock
     *    source still commits the [dev.nitka.nodewire.block.SideBinding] push
     *    path; ANY OTHER source (a Control Panel toggle, a foreign sensor)
     *    lands as a host-less link whose delivery calls [writePin] here — the
     *    port emits into the [dev.nitka.nodewire.signal.VirtualSignalMap]
     *    itself, self-keyed by the target position (one contribution per
     *    `(target, face)`).
     */
    private class RedstonePort(private val level: Level, private val pos: BlockPos) : PinPort {
        override fun pinOutputs(ctx: LinkContext): List<LinkPin> =
            if (level.getBlockState(pos).isSignalSource()) {
                listOf(LinkPin(REDSTONE_PIN, PinType.REDSTONE))
            } else {
                emptyList()
            }

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
            val state = level.getBlockState(pos)
            // The block's own emitted strength (max over faces). For a source
            // getBestNeighborSignal would read its inputs (0), not its output.
            val emitted = Direction.values().maxOf { state.getSignal(level, pos, it) }
            return PinReading(PinValue.Redstone(emitted))
        }

        /** Host-less delivery into `redstone@<face>`: emit into the virtual
         *  signal map (self-keyed — one contribution per target+face) and wake
         *  the neighbours so lamps/doors react immediately. Values arrive RAW;
         *  convert here (BOOL true → 15, floats round, ints clamp). */
        override fun writePin(id: String, value: PinValue) {
            val face = sideOfRedstoneInput(id) ?: return
            val power = (
                dev.nitka.nodewire.graph.PinValueConversion.convert(value, PinType.REDSTONE)
                    as? PinValue.Redstone
                )?.value ?: return
            push(face, power)
        }

        override fun clearPin(id: String) {
            val face = sideOfRedstoneInput(id) ?: return
            push(face, 0)
        }

        private fun push(face: Direction, power: Int) {
            dev.nitka.nodewire.signal.VirtualSignalMap.of(level).put(pos, pos, face, power)
            // Mirror the SideBinding push: nudge the virtual emitter cell and
            // the target itself so both re-poll their signal.
            val from = pos.relative(face)
            level.updateNeighborsAt(from, level.getBlockState(from).block)
            level.updateNeighborsAt(pos, level.getBlockState(pos).block)
        }
    }

    const val REDSTONE_PIN = "redstone"

    /** The face encoded in a `redstone@<face>` input pin id, or null. */
    fun sideOfRedstoneInput(pinId: String): Direction? {
        if (!pinId.startsWith("$REDSTONE_PIN@")) return null
        return Direction.byName(pinId.substringAfter('@'))
    }
}
