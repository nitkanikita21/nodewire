package dev.nitka.nodewire.net

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

/**
 * Server → client packet asking the client to highlight a world block.
 * Sent by the server-side `/nodewire highlight` command so chat-link
 * `RUN_COMMAND` click events (which always go to the server) can still
 * trigger client-side highlight rendering.
 */
class HighlightPacket(val pos: BlockPos, val durationMs: Long) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(pos)
        buf.writeVarLong(durationMs)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>) {
        val c = ctx.get()
        c.enqueueWork {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                Runnable {
                    dev.nitka.nodewire.client.highlight.BlockHighlightRenderer
                        .highlight(pos, durationMs)
                }
            }
        }
        c.packetHandled = true
    }

    companion object {
        fun decode(buf: FriendlyByteBuf) = HighlightPacket(
            buf.readBlockPos(),
            buf.readVarLong(),
        )
    }
}
