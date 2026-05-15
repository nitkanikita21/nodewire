package dev.nitka.nodewire.net

import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

class SetSideBindingNamePacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val targetPos: BlockPos,
    val targetSide: Direction,
    val name: String,
) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(sourcePos)
        buf.writeUtf(sourceChannelName, MAX_NAME_LEN)
        buf.writeBlockPos(targetPos)
        buf.writeEnum(targetSide)
        buf.writeUtf(name, MAX_NAME_LEN)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val be = level.getBlockEntity(sourcePos) as? LogicBlockEntity ?: return@enqueueWork
            be.renameSideBinding(sourceChannelName, targetPos, targetSide, name)
        }
        c.packetHandled = true
        return true
    }

    companion object {
        const val MAX_NAME_LEN = 64
        fun decode(buf: FriendlyByteBuf) = SetSideBindingNamePacket(
            sourcePos = buf.readBlockPos(),
            sourceChannelName = buf.readUtf(MAX_NAME_LEN),
            targetPos = buf.readBlockPos(),
            targetSide = buf.readEnum(Direction::class.java),
            name = buf.readUtf(MAX_NAME_LEN),
        )
    }
}
