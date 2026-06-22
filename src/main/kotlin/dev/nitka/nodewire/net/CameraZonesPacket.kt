package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.camerachunk.CameraChunkServer
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: the chunk-zone centres (one per far camera the client is
 * rendering beyond render distance) the client wants force-loaded + streamed.
 * [dimension] is the client's current dimension; the server only force-loads in
 * that dimension. Centres are chunk-pos longs; the server expands each by
 * [dev.nitka.nodewire.camerachunk.CameraZones.RADIUS]. Empty list = release.
 *
 * Pillar 2 Stage A — see [CameraChunkServer].
 */
data class CameraZonesPacket(
    val dimension: ResourceKey<Level>,
    val centers: List<Long>,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<CameraZonesPacket> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CameraZonesPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "camera_zones"),
        )

        val CODEC: Codec<CameraZonesPacket> = RecordCodecBuilder.create { i ->
            i.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dim").forGetter(CameraZonesPacket::dimension),
                Codec.LONG.listOf().fieldOf("centers").forGetter(CameraZonesPacket::centers),
            ).apply(i, ::CameraZonesPacket)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, CameraZonesPacket> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC).cast()

        fun handle(packet: CameraZonesPacket, ctx: IPayloadContext) {
            val player = ctx.player() as? ServerPlayer ?: return
            CameraChunkServer.onZonesRequest(player, packet.dimension, packet.centers.map { ChunkPos(it) })
        }
    }
}
