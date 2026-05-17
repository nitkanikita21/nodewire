package dev.nitka.nodewire.net

import dev.nitka.nodewire.block.LogicBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.network.NetworkEvent
import java.util.UUID
import java.util.function.Supplier

/**
 * C→S: client-side unbind/replace of a block's bound controller.
 * Used by the editor toolbar's "Unbind" button. `id == null` clears
 * the binding; non-null replaces it (rare — the usual bind path is
 * via [dev.nitka.nodewire.integration.tweakedcontroller.ControllerBindHandler]
 * RMB-with-controller-item, not packets).
 */
class SetControllerIdPacket(val pos: BlockPos, val id: UUID?) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(pos)
        buf.writeBoolean(id != null)
        id?.let { buf.writeUUID(it) }
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val be = level.getBlockEntity(pos) as? LogicBlockEntity ?: return@enqueueWork
            be.setControllerId(id)
        }
        c.packetHandled = true
        return true
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): SetControllerIdPacket {
            val pos = buf.readBlockPos()
            val has = buf.readBoolean()
            return SetControllerIdPacket(pos, if (has) buf.readUUID() else null)
        }
    }
}
