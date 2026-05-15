package dev.nitka.nodewire.net

import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

class SetBlockNamePacket(val pos: BlockPos, val name: String) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(pos)
        buf.writeUtf(name, MAX_NAME_LEN)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val be = level.getBlockEntity(pos) as? LogicBlockEntity ?: return@enqueueWork
            be.setBlockName(name)
        }
        c.packetHandled = true
        return true
    }

    companion object {
        const val MAX_NAME_LEN = 64
        fun decode(buf: FriendlyByteBuf) =
            SetBlockNamePacket(buf.readBlockPos(), buf.readUtf(MAX_NAME_LEN))
    }
}
