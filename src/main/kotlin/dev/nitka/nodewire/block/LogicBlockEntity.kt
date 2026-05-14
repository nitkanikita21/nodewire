package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.StatefulGraphEvaluator
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Stores the editable [NodeGraph] for one logic block, drives per-tick
 * evaluation on the server, and participates in cross-block named-channel
 * bindings established via the Channel Link Tool.
 *
 * Tick flow:
 *   1. Build external inputs:
 *      - For each `side_input` node, read neighbour redstone on the
 *        configured face.
 *      - For each `channel_input` node, read [externalChannelInputs]
 *        for the matching name.
 *   2. Run the evaluator.
 *   3. Apply outputs:
 *      - For each `side_output` node, push its value into [faceOutputs];
 *        neighbour update fires if anything changed.
 *      - For each `channel_output` node, iterate [bindings] with that
 *        channel name and write into each target BE's
 *        [externalChannelInputs] — picked up by the target on its next
 *        tick.
 *
 * Bindings persist on this (source) BE. [externalChannelInputs] is purely
 * runtime — no NBT round-trip; sources will re-populate on the next tick.
 */
class LogicBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.LOGIC_BLOCK_BE.get(), pos, state) {

    var graph: NodeGraph = NodeGraph()

    /** Channel links established from this BE (source) to others. */
    private val bindings: MutableList<ChannelBinding> = mutableListOf()

    /**
     * Values pushed in from other BEs' [ChannelOutput] nodes via their
     * bindings. Keyed by THIS BE's channel-input name. Read at the start
     * of each tick into the evaluator's externalOutputs map.
     */
    private val externalChannelInputs: MutableMap<String, PinValue> = mutableMapOf()

    private var serverEvaluator: StatefulGraphEvaluator? = null

    /** Last computed redstone power per face. Empty until first server tick. */
    var faceOutputs: Map<Direction, Int> = emptyMap()
        private set

    fun invalidateEvaluator() {
        serverEvaluator = null
    }

    /**
     * Create one explicit channel link: this BE's [sourceChannelName]
     * (a `channel_output` by that name) → [target]'s [targetChannelName]
     * (a `channel_input` by that name). Returns true if the binding was
     * created, false on validation failure (missing nodes / type mismatch /
     * names blank).
     *
     * Replaces any earlier binding from the same source channel name to the
     * same target so re-picking the same pair doesn't duplicate.
     */
    fun addBinding(
        sourceChannelName: String,
        target: LogicBlockEntity,
        targetChannelName: String,
    ): Boolean {
        if (sourceChannelName.isEmpty() || targetChannelName.isEmpty()) return false
        val srcNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output"
                && it.config.getString("name") == sourceChannelName
        } ?: return false
        val tgtNode = target.graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_input"
                && it.config.getString("name") == targetChannelName
        } ?: return false
        val srcType = PinType.fromName(srcNode.config.getString("type"))
        val tgtType = PinType.fromName(tgtNode.config.getString("type"))
        if (srcType != tgtType) return false

        bindings.removeAll {
            it.sourceChannelName == sourceChannelName
                && it.targetPos == target.blockPos
                && it.targetChannelName == targetChannelName
        }
        bindings.add(ChannelBinding(sourceChannelName, target.blockPos, targetChannelName))
        setChanged()
        return true
    }

    fun bindingsSnapshot(): List<ChannelBinding> = bindings.toList()

    fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        val eval = serverEvaluator ?: StatefulGraphEvaluator(graph).also { serverEvaluator = it }

        // Drop bindings whose endpoints have gone stale (source channel
        // removed, target BE missing, target channel removed, types now
        // mismatched). Reasons happen all the time — user deletes a
        // channel_input, the binding here is orphaned. We prune lazily so
        // the editor doesn't have to call out to every other BE on save.
        if (bindings.isNotEmpty()) {
            val before = bindings.size
            bindings.removeAll { isStale(it, level) }
            if (bindings.size != before) {
                setChanged()
                // Push so client-side wire renderer drops the dead link.
                level.sendBlockUpdated(pos, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            }
        }

        // 1. External inputs: side redstone + named channels.
        val external = HashMap<Pair<java.util.UUID, String>, PinValue>()
        for (node in graph.nodes.values) {
            when (node.typeKey.path) {
                "side_input" -> {
                    val face = directionOf(node.config.getString("face")) ?: continue
                    val signal = level.getSignal(pos.relative(face), face.opposite)
                    external[node.id to "out"] = PinValue.Redstone(signal)
                }
                "channel_input" -> {
                    val name = node.config.getString("name")
                    if (name.isEmpty()) continue
                    val value = externalChannelInputs[name] ?: continue
                    external[node.id to "out"] = value
                }
            }
        }

        val result = eval.tick(external)

        // 2a. Output redstone per face.
        val updated = HashMap<Direction, Int>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != "side_output") continue
            val face = directionOf(node.config.getString("face")) ?: continue
            val edge = graph.edges.firstOrNull { it.to.node == node.id && it.to.pin == "in" }
            val value = edge?.let { result.valueAt(it.from.node, it.from.pin) }
            updated[face] = redstoneOf(value)
        }
        if (updated != faceOutputs) {
            faceOutputs = updated
            level.updateNeighborsAt(pos, blockState.block)
            setChanged()
        }

        // 2b. Cross-block channel propagation. For each channel_output node,
        // grab its incoming value and push to any target BE bound on this
        // channel name.
        if (bindings.isNotEmpty()) {
            val perChannelValue = HashMap<String, PinValue>()
            for (node in graph.nodes.values) {
                if (node.typeKey.path != "channel_output") continue
                val name = node.config.getString("name")
                if (name.isEmpty()) continue
                val edge = graph.edges.firstOrNull { it.to.node == node.id && it.to.pin == "in" }
                val value = edge?.let { result.valueAt(it.from.node, it.from.pin) } ?: continue
                perChannelValue[name] = value
            }
            for (binding in bindings) {
                val value = perChannelValue[binding.sourceChannelName] ?: continue
                val target = level.getBlockEntity(binding.targetPos) as? LogicBlockEntity ?: continue
                // Key by target's input name — they may differ from the
                // source channel's name. The target reads this slot on its
                // own next tick when it processes channel_input nodes.
                target.externalChannelInputs[binding.targetChannelName] = value
            }
        }
    }

    override fun onLoad() {
        super.onLoad()
        if (level?.isClientSide == true) {
            dev.nitka.nodewire.client.wire.ClientLogicBlockTracker.register(this)
        }
    }

    override fun setRemoved() {
        if (level?.isClientSide == true) {
            dev.nitka.nodewire.client.wire.ClientLogicBlockTracker.unregister(this)
        }
        super.setRemoved()
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.put("graph", graph.toNbt())
        if (bindings.isNotEmpty()) {
            val list = ListTag()
            for (b in bindings) list.add(b.toNbt())
            tag.put("bindings", list)
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        graph = if (tag.contains("graph")) {
            NodeGraph.fromNbt(tag.getCompound("graph"))
        } else {
            NodeGraph()
        }
        bindings.clear()
        if (tag.contains("bindings")) {
            val list = tag.getList("bindings", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) bindings.add(ChannelBinding.fromNbt(list.getCompound(i)))
        }
        invalidateEvaluator()
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun onDataPacket(net: Connection, pkt: ClientboundBlockEntityDataPacket) {
        pkt.tag?.let { load(it) }
    }

    private fun isStale(binding: ChannelBinding, level: Level): Boolean {
        val srcNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output"
                && it.config.getString("name") == binding.sourceChannelName
        } ?: return true
        val target = level.getBlockEntity(binding.targetPos) as? LogicBlockEntity ?: return true
        val tgtNode = target.graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_input"
                && it.config.getString("name") == binding.targetChannelName
        } ?: return true
        val srcType = PinType.fromName(srcNode.config.getString("type"))
        val tgtType = PinType.fromName(tgtNode.config.getString("type"))
        return srcType != tgtType
    }

    companion object {
        private val DIRECTIONS_BY_NAME = Direction.entries.associateBy { it.name.lowercase() }

        private fun directionOf(name: String): Direction? =
            DIRECTIONS_BY_NAME[name.lowercase()]

        private fun redstoneOf(value: PinValue?): Int = when (value) {
            is PinValue.Redstone -> value.value.coerceIn(0, 15)
            is PinValue.Int -> value.value.coerceIn(0, 15)
            is PinValue.Bool -> if (value.value) 15 else 0
            else -> 0
        }
    }
}
