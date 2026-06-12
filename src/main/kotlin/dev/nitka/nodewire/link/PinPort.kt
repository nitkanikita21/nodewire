package dev.nitka.nodewire.link

import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * One named, typed pin a block exposes to the Channel Link Tool.
 *
 * [id] is the stable wire identity (persisted inside [PinLink]); [label] is
 * what the picker shows. Pin ids are flat strings — a port may encode extra
 * routing into them (e.g. the redstone fallback's `"redstone@north"`).
 */
data class LinkPin(
    val id: String,
    val type: PinType,
    val label: String = id,
)

/**
 * One sample of an output pin.
 *
 * [pulseStamp] turns a pin into an EVENT pin: -1 (default) means the value is
 * sticky — deliver it every tick. A non-negative stamp is a monotonic
 * "last fired at" marker (gameTime); [PinLinkEngine] compares it against the
 * per-link latch and delivers [value] exactly once per stamp change, writing
 * the pin type's default on quiet ticks. That gives 1-tick pulses (the
 * touch-screen's `touch_down`) without per-consumer state in the source.
 */
data class PinReading(
    val value: PinValue,
    val pulseStamp: Long = -1L,
)

/** Click/resolve context handed to [PinPort] enumerations. [face] is the
 *  clicked face when the enumeration comes from a tool click, null when the
 *  delivery engine re-enumerates server-side. */
data class LinkContext(
    val level: Level,
    val pos: BlockPos,
    val state: BlockState,
    val face: Direction? = null,
)

/**
 * THE unified linking surface. Any [net.minecraft.world.level.block.entity.BlockEntity]
 * that implements this (or has a [PinPorts] adapter) becomes linkable with the
 * Channel Link Tool — no per-block branches in the tool, no per-kind packets.
 *
 *  * [pinOutputs] — pins this block PRODUCES. Sneak+RMB enumerates them; the
 *    delivery engine samples them each server tick via [readPin].
 *  * [pinInputs] — pins this block ACCEPTS. Plain RMB (with an armed source)
 *    enumerates them; the engine delivers via [writePin].
 *
 * Implementors today: LogicBlockEntity (channel_output / channel_input
 * nodes), ScreenBlockEntity (`touch`/`touch_down` out, `screen` in),
 * CameraBlockEntity (`video` out, `fov`/`enable`/`yaw`/`pitch` in). Foreign
 * blocks (Aeronautics, cap-bearing containers, plain redstone) get the same
 * surface through [PinPorts] adapters.
 */
interface PinPort {
    fun pinOutputs(ctx: LinkContext): List<LinkPin> = emptyList()

    fun pinInputs(ctx: LinkContext): List<LinkPin> = emptyList()

    /** Sample output pin [id]; null = nothing to deliver right now (the
     *  target keeps its previous value). Server thread. */
    fun readPin(id: String): PinReading? = null

    /** Deliver [value] into input pin [id]. Values arrive RAW — consumers
     *  convert at their own read site (the evaluator's edge-read path). */
    fun writePin(id: String, value: PinValue) {}

    /** Input pin [id] stopped being fed (its link was removed or its source
     *  went silent) — reset whatever [writePin] set so stale last-writer-wins
     *  values don't linger (the "old picture on rewire" class of bug). */
    fun clearPin(id: String) {}
}
