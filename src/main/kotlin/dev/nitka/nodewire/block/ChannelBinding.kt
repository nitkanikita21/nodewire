package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos

/**
 * One cross-block channel link. A [LogicBlockEntity] keeps a list of these
 * on the **source** side; on each server tick it iterates them and pushes
 * the value of its [sourceChannelName] ChannelOutput into the target BE's
 * external-channel-input slot named [targetChannelName].
 */
data class ChannelBinding(
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val targetChannelName: String,
) {
    companion object {
        val CODEC: Codec<ChannelBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(ChannelBinding::sourceChannelName),
                BlockPos.CODEC.fieldOf("pos").forGetter(ChannelBinding::targetPos),
                Codec.STRING.fieldOf("dst").forGetter(ChannelBinding::targetChannelName),
            ).apply(i, ::ChannelBinding)
        }
    }
}
