package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.graph.NodeGraph
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Stores the editable [NodeGraph] for one logic block. Persists to NBT;
 * synced to clients via [getUpdateTag] + [getUpdatePacket] so the editor
 * can read the latest server-authoritative graph when it opens.
 *
 * The editor reads from the BE on open, edits a local copy, and sends one
 * `SaveGraphPacket` (Phase 4) on close. There's no incremental sync — the
 * server is the authority and the client's local copy is throwaway until
 * save.
 */
class LogicBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.LOGIC_BLOCK_BE.get(), pos, state) {

    var graph: NodeGraph = NodeGraph()

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.put("graph", graph.toNbt())
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        graph = if (tag.contains("graph")) {
            NodeGraph.fromNbt(tag.getCompound("graph"))
        } else {
            NodeGraph()
        }
    }

    /** Full BE state sent to clients during chunk-load. Includes the graph. */
    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    /** Incremental BE-update packet — also full snapshot for MVP simplicity. */
    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun onDataPacket(net: Connection, pkt: ClientboundBlockEntityDataPacket) {
        // The handler in vanilla calls `load(pkt.tag)` for us through the
        // ClientboundBlockEntityDataPacket dispatch — overriding here lets us
        // hook future incremental fields, but the default behavior already
        // works because `load()` parses the same NBT shape we wrote in
        // `saveAdditional`.
        pkt.tag?.let { load(it) }
    }
}
