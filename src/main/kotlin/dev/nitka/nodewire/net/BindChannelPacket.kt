package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.block.LogicBlockEntity
import dev.nitka.nodewire.endpoint.EndpointRef
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.minecraftforge.network.NetworkEvent
import org.slf4j.Logger
import java.util.function.Supplier

/**
 * Client → server: commits a channel binding chosen by the player via
 * the Channel Link Tool. The source position and channel name were set
 * earlier via [SetChannelSourcePacket]; this packet carries only the
 * target side. Server resolves both BEs, validates type compatibility
 * inside [LogicBlockEntity.addBinding], and pushes a chunk update so
 * connected clients pick up the new binding for the wire renderer.
 */
class BindChannelPacket(
    val sourcePos: BlockPos,
    val sourceChannelName: String,
    val target: EndpointRef,
    val targetChannelName: String,
) {
    fun encode(buf: FriendlyByteBuf) {
        buf.writeCodec(CODEC, this)
    }

    fun handle(ctx: Supplier<NetworkEvent.Context>): Boolean {
        val c = ctx.get()
        c.enqueueWork {
            val player = c.sender ?: return@enqueueWork
            val level = player.level()
            val srcRef = EndpointRef.from(level, sourcePos)
            val srcCenter = srcRef.worldCenter(level) ?: Vec3.atCenterOf(sourcePos)
            if (player.distanceToSqr(srcCenter.x, srcCenter.y, srcCenter.z) > MAX_REACH_SQ) {
                LOG.warn("Bind rejected: source too far from {}", player.gameProfile.name)
                notify(player, "Source block is too far away")
                return@enqueueWork
            }
            val srcBe = level.getBlockEntity(sourcePos) as? LogicBlockEntity
            if (srcBe == null) {
                LOG.warn("Bind rejected: source BE missing at {}", sourcePos)
                notify(player, "Source block missing at ${sourcePos.toShortString()}")
                return@enqueueWork
            }
            val tgtBe = target.resolve(level) as? LogicBlockEntity
            if (tgtBe == null) {
                LOG.warn("Bind rejected: target BE missing at {}", target)
                notify(player, "Target block missing at ${target.payload.blockPos.toShortString()}")
                return@enqueueWork
            }
            when (val res = srcBe.tryAddBinding(sourceChannelName, tgtBe, targetChannelName)) {
                LogicBlockEntity.BindResult.Ok -> {
                    level.sendBlockUpdated(
                        sourcePos, srcBe.blockState, srcBe.blockState, Block.UPDATE_CLIENTS,
                    )
                }
                LogicBlockEntity.BindResult.EmptyName -> {
                    LOG.warn("Bind rejected: empty channel name (src='{}' tgt='{}')", sourceChannelName, targetChannelName)
                    notify(player, "Channel name is empty — give your Channel Output/Input a name")
                }
                LogicBlockEntity.BindResult.SourceMissing -> {
                    LOG.warn("Bind rejected: src '{}' has no Channel Output named '{}'", sourcePos, sourceChannelName)
                    notify(player, "Source block has no Channel Output named '$sourceChannelName' (close the editor to save first)")
                }
                LogicBlockEntity.BindResult.TargetMissing -> {
                    LOG.warn("Bind rejected: tgt '{}' has no Channel Input named '{}'", target, targetChannelName)
                    notify(player, "Target block has no Channel Input named '$targetChannelName' (close the editor to save first)")
                }
                is LogicBlockEntity.BindResult.TypeMismatch -> {
                    LOG.warn(
                        "Bind rejected: type mismatch {} vs {} (src='{}' tgt='{}')",
                        res.srcType, res.tgtType, sourceChannelName, targetChannelName,
                    )
                    notify(player, "Type mismatch: ${res.srcType.name.lowercase()} → ${res.tgtType.name.lowercase()}")
                }
            }
        }
        c.packetHandled = true
        return true
    }

    private fun notify(player: net.minecraft.server.level.ServerPlayer, msg: String) {
        player.displayClientMessage(
            net.minecraft.network.chat.Component.literal("Bind failed: $msg")
                .withStyle(net.minecraft.ChatFormatting.YELLOW),
            true,
        )
    }

    companion object {
        private val LOG: Logger = LogUtils.getLogger()
        // Generous reach — link tool can be used while peeking around.
        private const val MAX_REACH_SQ = 16.0 * 16.0

        val CODEC: com.mojang.serialization.Codec<BindChannelPacket> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    net.minecraft.core.BlockPos.CODEC.fieldOf("src_pos").forGetter(BindChannelPacket::sourcePos),
                    com.mojang.serialization.Codec.STRING.fieldOf("src_ch").forGetter(BindChannelPacket::sourceChannelName),
                    EndpointRef.CODEC.fieldOf("target").forGetter(BindChannelPacket::target),
                    com.mojang.serialization.Codec.STRING.fieldOf("tgt_ch").forGetter(BindChannelPacket::targetChannelName),
                ).apply(i, ::BindChannelPacket)
            }

        fun decode(buf: FriendlyByteBuf): BindChannelPacket = buf.readCodec(CODEC)
    }
}
