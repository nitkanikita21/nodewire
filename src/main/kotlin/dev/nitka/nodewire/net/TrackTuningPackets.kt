package dev.nitka.nodewire.net

import dev.nitka.nodewire.Nodewire
import dev.nitka.nodewire.integration.tracksplus.TracksPlusTuning
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Server → client: open the Track Tuning screen for the track at [pos],
 * seeded with the SERVER-side tuning snapshot [values] (the client BE copy
 * doesn't reliably sync the multipliers, so the server reads them).
 */
data class OpenTrackTuningPacket(val pos: BlockPos, val values: CompoundTag) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<OpenTrackTuningPacket> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenTrackTuningPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "open_track_tuning"),
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenTrackTuningPacket> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC.cast(),
                OpenTrackTuningPacket::pos,
                ByteBufCodecs.COMPOUND_TAG.cast(),
                OpenTrackTuningPacket::values,
                ::OpenTrackTuningPacket,
            )

        fun handle(packet: OpenTrackTuningPacket, ctx: IPayloadContext) {
            dev.nitka.nodewire.client.screen.TrackTuningScreen.open(packet.pos, packet.values)
        }
    }
}

/**
 * Client → server: apply the edited tuning [values] to EVERY track of the
 * vehicle containing [pos] (same Sable sub-level; both sides of the hull —
 * Tracks+' own share only walks one connected chain).
 */
data class ApplyTrackTuningPacket(val pos: BlockPos, val values: CompoundTag) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ApplyTrackTuningPacket> = TYPE

    companion object {
        private const val MAX_REACH_SQ = 64.0 * 64.0

        val TYPE = CustomPacketPayload.Type<ApplyTrackTuningPacket>(
            ResourceLocation.fromNamespaceAndPath(Nodewire.ID, "apply_track_tuning"),
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ApplyTrackTuningPacket> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC.cast(),
                ApplyTrackTuningPacket::pos,
                ByteBufCodecs.COMPOUND_TAG.cast(),
                ApplyTrackTuningPacket::values,
                ::ApplyTrackTuningPacket,
            )

        fun handle(packet: ApplyTrackTuningPacket, ctx: IPayloadContext) {
            val player = ctx.player()
            val level = player.level() as? ServerLevel ?: return
            if (!TracksPlusTuning.loaded()) return
            if (player.distanceToSqr(Vec3.atCenterOf(packet.pos)) > MAX_REACH_SQ) return
            val origin = level.getBlockEntity(packet.pos)
            if (!TracksPlusTuning.isTrack(origin)) return

            val targets = TracksPlusTuning.tracksOfVehicle(level, packet.pos)
            var applied = 0
            for (be in targets) {
                var any = false
                for (s in TracksPlusTuning.SPECS) {
                    if (!packet.values.contains(s.key)) continue
                    if (TracksPlusTuning.apply(be, s.key, packet.values.getDouble(s.key))) any = true
                }
                if (any) applied++
            }
            player.displayClientMessage(
                Component.literal("Track tuning applied to $applied track(s)"),
                true,
            )
        }
    }
}
