package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

/**
 * Sink for incoming Tweaked-Controller packet state. Mixins inside
 * `dev.nitka.nodewire.mixin` (Java) intercept TC's
 * `TweakedLinkedControllerButtonPacket.handleItem` / `handleLectern`
 * and `TweakedLinkedControllerAxisPacket.handleItem` / `handleLectern`,
 * extract the raw packed wire values, and forward them here. We resolve
 * the target [LogicBlockEntity] and let it apply the update.
 *
 * Why push (not pull): pulling from TC's state map requires either
 * scanning lecterns each tick or chasing TC's frequency-based redstone
 * network. Drive-By-Wire's solution is push via mixin, so we do the
 * same — it's deterministic, runs at packet rate (~20Hz), no per-tick
 * scans, no UUID indirection.
 */
object ControllerStatePipeline {

    /**
     * Push raw packed button bitmask to the Logic Block at [pos] in
     * [level]. Decoding (which physical button is which bit) happens
     * inside [LogicBlockEntity] when it converts to a [ControllerState].
     */
    @JvmStatic
    fun pushButtons(level: Level, pos: BlockPos, buttonStates: Short) {
        val be = level.getBlockEntity(pos) as? LogicBlockEntity ?: return
        be.receiveControllerButtonStates(buttonStates)
    }

    /**
     * Push packed axis int. If TC's [fullAxis] is non-null and length
     * 6, it carries higher-precision float axes; otherwise [axisPacked]
     * (a 32-bit int holding 6 packed bytes per TC's encoding) is the
     * source of truth.
     */
    @JvmStatic
    fun pushAxes(level: Level, pos: BlockPos, axisPacked: Int, fullAxis: FloatArray?) {
        val be = level.getBlockEntity(pos) as? LogicBlockEntity ?: return
        be.receiveControllerAxisStates(axisPacked, fullAxis)
    }
}
