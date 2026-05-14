package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Drive-by-wire binding from a named channel on a source LogicBlock to an
 * arbitrary block face. Distance-agnostic — signals reach the target via
 * the per-level VirtualSignalMap surfaced by [dev.nitka.nodewire.mixin.SignalGetterMixin].
 *
 * Channel value → redstone: BOOL → 0/15, INT → clamp(0..15), REDSTONE
 * pass-through. Other types contribute no signal at tick time.
 */
data class SideBinding(
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val targetSide: Direction,
) {
    companion object {
        val CODEC: Codec<SideBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(SideBinding::sourceChannelName),
                BlockPos.CODEC.fieldOf("pos").forGetter(SideBinding::targetPos),
                Direction.CODEC.fieldOf("side").forGetter(SideBinding::targetSide),
            ).apply(i, ::SideBinding)
        }
    }
}
