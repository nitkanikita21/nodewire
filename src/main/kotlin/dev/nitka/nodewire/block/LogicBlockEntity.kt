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
     * Bind every name-and-type-matching pair of channels between this BE's
     * outputs and [target]'s inputs. Returns the number of bindings
     * created. Replaces any prior bindings pointing at [target] to avoid
     * stale duplicates after a rewire.
     */
    fun bindChannelsTo(target: LogicBlockEntity): Int {
        bindings.removeAll { it.targetPos == target.blockPos }
        var count = 0
        for (src in graph.nodes.values) {
            if (src.typeKey.path != "channel_output") continue
            val srcName = src.config.getString("name")
            if (srcName.isEmpty()) continue
            val srcType = PinType.fromName(src.config.getString("type"))
            val match = target.graph.nodes.values.firstOrNull { tgt ->
                tgt.typeKey.path == "channel_input"
                    && tgt.config.getString("name") == srcName
                    && PinType.fromName(tgt.config.getString("type")) == srcType
            } ?: continue
            bindings.add(ChannelBinding(srcName, target.blockPos))
            count++
            @Suppress("UNUSED_VARIABLE") val unused = match // anchor: ensures we matched
        }
        if (count > 0) setChanged()
        return count
    }

    fun bindingsSnapshot(): List<ChannelBinding> = bindings.toList()

    fun serverTick(level: Level, pos: BlockPos, state: BlockState) {
        val eval = serverEvaluator ?: StatefulGraphEvaluator(graph).also { serverEvaluator = it }

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
                target.externalChannelInputs[binding.sourceChannelName] = value
            }
        }
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
