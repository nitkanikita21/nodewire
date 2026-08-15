package dev.nitka.nodewire.item

import dev.nitka.nodewire.integration.tracksplus.TracksPlusTuning
import dev.nitka.nodewire.net.OpenTrackTuningPacket
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Vehicle-wide track tuner for Create: Tracks+. RMB a track mount → the
 * server snapshots that track's tuning knobs and opens [TrackTuningScreen]
 * on the client; Apply pushes the edited values onto EVERY track of the
 * vehicle (same Sable sub-level, both hull sides) — unlike the Tracks+
 * Suspension Key, which only shares along one connected chain.
 */
class TrackTuningKeyItem(props: Properties) : Item(props) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val player = context.player ?: return InteractionResult.PASS
        if (level.isClientSide) {
            // Server decides (and reads the authoritative values); swing so the
            // hand animates while the packet round-trips.
            return InteractionResult.SUCCESS
        }
        if (!TracksPlusTuning.loaded()) {
            player.displayClientMessage(Component.literal("Create: Tracks+ is not installed"), true)
            return InteractionResult.FAIL
        }
        val be = level.getBlockEntity(context.clickedPos)
        if (!TracksPlusTuning.isTrack(be)) {
            player.displayClientMessage(Component.literal("Point at a track mount"), true)
            return InteractionResult.PASS
        }
        val values = TracksPlusTuning.read(be!!)
        (player as? ServerPlayer)?.let {
            PacketDistributor.sendToPlayer(it, OpenTrackTuningPacket(context.clickedPos, values))
        }
        return InteractionResult.CONSUME
    }
}
