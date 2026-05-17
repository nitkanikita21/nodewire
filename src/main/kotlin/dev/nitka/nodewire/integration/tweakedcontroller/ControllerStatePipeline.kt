package dev.nitka.nodewire.integration.tweakedcontroller

import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.apache.logging.log4j.LogManager

/**
 * Sink for incoming Tweaked-Controller packet state. Mixins inside
 * `dev.nitka.nodewire.mixin.tc` (Java) intercept TC's
 * `TweakedLinkedControllerButtonPacket.handleItem` / `handleLectern`
 * and `TweakedLinkedControllerAxisPacket.handleItem` / `handleLectern`,
 * call TC's own `ControllerRedstoneOutput.Decode*()` to unpack the wire
 * format, and forward the decoded arrays here.
 *
 * Per-call DEBUG logs make it easy to verify the data path end-to-end
 * (`grep nodewire/tc` in the game log) — chase down "binding works,
 * packets arrive, decode runs, but the node still emits zero" bugs.
 */
object ControllerStatePipeline {

    private val LOG = LogManager.getLogger("nodewire/tc")

    /**
     * Push decoded button bitmask (TC's `Boolean[]`, length 15, GLFW
     * gamepad layout) to the Logic Block at [pos] in [level].
     */
    @JvmStatic
    fun pushButtonStates(level: Level, pos: BlockPos, buttons: Array<Boolean?>) {
        val be = level.getBlockEntity(pos) as? LogicBlockEntity
        if (be == null) {
            LOG.debug("pushButtonStates: no LogicBlockEntity at {}", pos)
            return
        }
        val unboxed = BooleanArray(buttons.size) { buttons[it] == true }
        be.receiveControllerButtonStates(unboxed)
        LOG.debug("pushButtonStates: pos={} A={} B={}", pos, unboxed.getOrElse(0) { false }, unboxed.getOrElse(1) { false })
    }

    /**
     * Push decoded axis bytes (TC's `Byte[]`, length 6: byte 0..3 are
     * signed sticks with bit-4 = sign, bits 0..3 = magnitude; byte 4..5
     * are unsigned trigger magnitudes 0..15). If [fullAxis] is non-null
     * and length ≥ 6, it carries higher-precision floats and the
     * receiver should prefer it.
     */
    @JvmStatic
    fun pushAxisStates(
        level: Level,
        pos: BlockPos,
        axisBytes: Array<Byte?>,
        fullAxis: FloatArray?,
    ) {
        val be = level.getBlockEntity(pos) as? LogicBlockEntity
        if (be == null) {
            LOG.debug("pushAxisStates: no LogicBlockEntity at {}", pos)
            return
        }
        val unboxed = ByteArray(axisBytes.size) { axisBytes[it] ?: 0 }
        be.receiveControllerAxisStates(unboxed, fullAxis)
        LOG.debug(
            "pushAxisStates: pos={} LT-byte={} RT-byte={} fullAxis={}",
            pos,
            unboxed.getOrElse(4) { 0 },
            unboxed.getOrElse(5) { 0 },
            fullAxis?.joinToString(",")?.take(80),
        )
    }
}
