package dev.nitka.nodewire.net

import com.mojang.logging.LogUtils
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.script.StateKind
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.slf4j.Logger

/** One replicated state cell's new value, as a single-key CompoundTag. */
data class CellDelta(val key: String, val kind: StateKind, val value: CompoundTag) {
    companion object {
        private val KIND_CODEC: Codec<StateKind> =
            Codec.STRING.xmap({ StateKind.valueOf(it) }, StateKind::name)
        val CODEC: Codec<CellDelta> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("k").forGetter(CellDelta::key),
                KIND_CODEC.fieldOf("kind").forGetter(CellDelta::kind),
                CompoundTag.CODEC.fieldOf("v").forGetter(CellDelta::value),
            ).apply(i, ::CellDelta)
        }
    }
}

/**
 * Server → client: a batch of replicated state-cell changes for ONE script node,
 * sent only when a cell mutated (delta, not per-tick full state). The client
 * merges them into the node's client state before its client behaviors read.
 * Server-authoritative, one-way. Modeled on the bind packets (registry-aware
 * `RegistryFriendlyByteBuf`) + [SetScriptSourcePacket] (NodeId via `UUIDUtil.CODEC`).
 */
data class StateDeltaPacket(
    val blockPos: BlockPos,
    val nodeId: NodeId,
    val deltas: List<CellDelta>,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<StateDeltaPacket> = TYPE

    companion object {
        private val LOG: Logger = LogUtils.getLogger()

        val TYPE = CustomPacketPayload.Type<StateDeltaPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "state_delta")
        )

        val CODEC: Codec<StateDeltaPacket> = RecordCodecBuilder.create { i ->
            i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(StateDeltaPacket::blockPos),
                UUIDUtil.CODEC.fieldOf("node").forGetter(StateDeltaPacket::nodeId),
                CellDelta.CODEC.listOf().fieldOf("deltas").forGetter(StateDeltaPacket::deltas),
            ).apply(i, ::StateDeltaPacket)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StateDeltaPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        // Client-side handler (playToClient). The apply object lives client-side;
        // dispatch through DistExecutor-free enqueueWork (the payload only arrives
        // on a client connection, the registration is playToClient).
        fun handle(packet: StateDeltaPacket, ctx: IPayloadContext) {
            ctx.enqueueWork {
                dev.nitka.nodewire.client.script.StateDeltaClient.apply(packet)
            }
        }
    }
}
