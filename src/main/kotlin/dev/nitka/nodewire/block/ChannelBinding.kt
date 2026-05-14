package dev.nitka.nodewire.block

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils

/**
 * One cross-block channel link. A [LogicBlockEntity] keeps a list of these
 * on the **source** side; on each server tick it iterates them and pushes
 * the value of its [sourceChannelName] [ChannelOutput] into the target
 * BE's external-channel-input slot of the same name.
 *
 * The match is by-name on both sides: a `speed` ChannelOutput on the
 * source feeds a `speed` ChannelInput on the target. Type compatibility
 * is checked at bind time by [LogicBlockEntity.bindChannelsTo]; mismatched
 * pairs never become bindings.
 */
data class ChannelBinding(
    val sourceChannelName: String,
    val targetPos: BlockPos,
) {
    fun toNbt(): CompoundTag = CompoundTag().also {
        it.putString("name", sourceChannelName)
        it.put("pos", NbtUtils.writeBlockPos(targetPos))
    }

    companion object {
        fun fromNbt(tag: CompoundTag) = ChannelBinding(
            sourceChannelName = tag.getString("name"),
            targetPos = NbtUtils.readBlockPos(tag.getCompound("pos")),
        )
    }
}
