package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.StatefulGraphEvaluator
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
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
     * Drive-by-wire links from this BE's channels to a face of an
     * arbitrary adjacent block. Applied as a redstone signal on this
     * block's face pointing at the target.
     */
    private val sideBindings: MutableList<SideBinding> = mutableListOf()

    private var blockName: String = ""

    fun getBlockName(): String = blockName

    fun setBlockName(value: String) {
        val sanitized = value.take(MAX_NAME_LENGTH)
        if (blockName == sanitized) return
        blockName = sanitized
        setChanged()
        val l = level ?: return
        l.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

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
    fun sideBindingsSnapshot(): List<SideBinding> = sideBindings.toList()

    /**
     * Delete one channel-binding tuple. Returns true if a matching entry
     * was removed. The block is marked dirty + a client update is pushed
     * by the caller on success.
     */
    fun removeBinding(
        sourceChannelName: String,
        targetPos: BlockPos,
        targetChannelName: String,
    ): Boolean {
        val removed = bindings.removeAll {
            it.sourceChannelName == sourceChannelName
                && it.targetPos == targetPos
                && it.targetChannelName == targetChannelName
        }
        if (removed) setChanged()
        return removed
    }

    /**
     * Delete one side-binding tuple. Returns true on success. Also clears
     * the corresponding entry from the per-level VirtualSignalMap so the
     * target sees signal go to 0 before the next tick rewrites the map.
     */
    fun removeSideBinding(
        sourceChannelName: String,
        targetPos: BlockPos,
        targetSide: Direction,
    ): Boolean {
        val removed = sideBindings.removeAll {
            it.sourceChannelName == sourceChannelName
                && it.targetPos == targetPos
                && it.targetSide == targetSide
        }
        if (removed) {
            setChanged()
            // Pre-empt the next-tick rewrite: clear our contribution now
            // so the target neighbour-update we fire next sees 0.
            level?.let { dev.nitka.nodewire.signal.VirtualSignalMap.of(it).put(blockPos, targetPos, targetSide, 0) }
            level?.let {
                val tgt = it.getBlockState(targetPos)
                if (!tgt.isAir) {
                    it.neighborChanged(targetPos, tgt.block, targetPos)
                    it.updateNeighborsAt(targetPos, tgt.block)
                }
            }
        }
        return removed
    }

    /**
     * Drive-by-wire bind: this BE's [sourceChannelName] → [targetPos]'s
     * [targetSide] face. Returns true if accepted, false on validation:
     *   * source channel must exist on this BE
     *   * source channel type must be coercible to redstone (BOOL/INT/REDSTONE)
     *   * target must be directly adjacent on `targetSide.opposite`
     *
     * Duplicates (same source channel + target pos + side) are replaced
     * to keep one binding per slot.
     */
    fun addSideBinding(
        sourceChannelName: String,
        targetPos: BlockPos,
        targetSide: Direction,
    ): Boolean {
        if (sourceChannelName.isEmpty()) return false
        val srcNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output"
                && it.config.getString("name") == sourceChannelName
        } ?: return false
        val srcType = PinType.fromName(srcNode.config.getString("type"))
        if (srcType != PinType.BOOL && srcType != PinType.INT && srcType != PinType.REDSTONE) {
            return false
        }
        // No adjacency requirement — virtual signals are injected via
        // VirtualSignalMap + a mixin into Level.getBestNeighborSignal, so
        // the target can be arbitrarily far from the source.

        sideBindings.removeAll {
            it.sourceChannelName == sourceChannelName
                && it.targetPos == targetPos
                && it.targetSide == targetSide
        }
        sideBindings.add(SideBinding(sourceChannelName, targetPos, targetSide))
        setChanged()
        return true
    }

    fun renameSideBinding(
        sourceChannelName: String,
        targetPos: BlockPos,
        targetSide: Direction,
        newName: String,
    ) {
        val idx = sideBindings.indexOfFirst {
            it.sourceChannelName == sourceChannelName
                && it.targetPos == targetPos
                && it.targetSide == targetSide
        }
        if (idx < 0) return
        val sanitized = newName.take(64)
        if (sideBindings[idx].name == sanitized) return
        sideBindings[idx] = sideBindings[idx].copy(name = sanitized)
        setChanged()
        val l = level ?: return
        l.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

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

        // 2a. Output redstone per face — driven purely by side_output nodes.
        // Drive-by-wire side bindings go through VirtualSignalMap below, not
        // through faceOutputs, since they target arbitrary positions.
        val updated = HashMap<Direction, Int>()
        for (node in graph.nodes.values) {
            if (node.typeKey.path != "side_output") continue
            val face = directionOf(node.config.getString("face")) ?: continue
            val edge = graph.edges.firstOrNull { it.to.node == node.id && it.to.pin == "in" }
            val value = edge?.let { result.valueAt(it.from.node, it.from.pin) }
            val r = redstoneOf(value)
            updated[face] = maxOf(updated[face] ?: 0, r)
        }
        // Prune stale side-bindings (target moved away, channel gone) and
        // apply live ones as redstone on our face toward the target.
        if (sideBindings.isNotEmpty()) {
            val before = sideBindings.size
            sideBindings.removeAll { isSideStale(it, level) }
            if (sideBindings.size != before) {
                setChanged()
                level.sendBlockUpdated(pos, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            }
        }
        // Compute per-channel value once for both bindings + sideBindings.
        val perChannelValueCache by lazy {
            val map = HashMap<String, PinValue>()
            for (node in graph.nodes.values) {
                if (node.typeKey.path != "channel_output") continue
                val name = node.config.getString("name")
                if (name.isEmpty()) continue
                val edge = graph.edges.firstOrNull { it.to.node == node.id && it.to.pin == "in" }
                val value = edge?.let { result.valueAt(it.from.node, it.from.pin) } ?: continue
                map[name] = value
            }
            map
        }
        // Side bindings: inject into the per-level virtual signal map. The
        // mixin into Level.getBestNeighborSignal will surface these to any
        // block that polls its neighbours — distance and adjacency are
        // irrelevant. We collect per (targetPos, targetSide) and take the
        // max if multiple sources drive the same slot.
        if (sideBindings.isNotEmpty()) {
            val map = dev.nitka.nodewire.signal.VirtualSignalMap.of(level)
            // Remove any previous contributions from this source first so a
            // dropped binding clears its signal in the same tick.
            map.clearSource(pos)
            for (sb in sideBindings) {
                val value = perChannelValueCache[sb.sourceChannelName] ?: continue
                val r = redstoneOf(value)
                map.put(pos, sb.targetPos, sb.targetSide, r)
            }
            // Schedule a neighbour update on each affected target so it
            // re-polls its power state; without this most blocks won't
            // notice until something else perturbs them.
            for (sb in sideBindings) {
                val targetState = level.getBlockState(sb.targetPos)
                if (!targetState.isAir) {
                    level.neighborChanged(sb.targetPos, targetState.block, sb.targetPos)
                    // Also poke updateNeighborsAt so block entities that
                    // chain (e.g. piston extensions) see the change.
                    level.updateNeighborsAt(sb.targetPos, targetState.block)
                }
            }
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
            for (binding in bindings) {
                val value = perChannelValueCache[binding.sourceChannelName] ?: continue
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
        } else {
            val lvl = level
            if (lvl != null) {
                // Drop any virtual signals we were injecting so targets
                // immediately stop seeing power on the next poll.
                dev.nitka.nodewire.signal.VirtualSignalMap.of(lvl).clearSource(blockPos)
                // Plus poke every target so it actually re-polls — without
                // this, a piston/lamp/etc. that was held high by our signal
                // would stay high indefinitely (vanilla only re-reads on
                // neighbour-change events, not on a passive value drop).
                for (sb in sideBindings) {
                    val tgt = lvl.getBlockState(sb.targetPos)
                    if (!tgt.isAir) {
                        lvl.neighborChanged(sb.targetPos, tgt.block, sb.targetPos)
                        lvl.updateNeighborsAt(sb.targetPos, tgt.block)
                    }
                }
            }
        }
        super.setRemoved()
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.put(
            "graph",
            dev.nitka.nodewire.graph.NodeGraph.CODEC
                .encodeStart(NbtOps.INSTANCE, graph).result()
                .orElseThrow { IllegalStateException("graph encode failed") },
        )
        if (bindings.isNotEmpty()) {
            tag.put(
                "bindings",
                ChannelBinding.CODEC.listOf()
                    .encodeStart(NbtOps.INSTANCE, bindings.toList()).result()
                    .orElseThrow { IllegalStateException("bindings encode failed") },
            )
        }
        if (sideBindings.isNotEmpty()) {
            tag.put(
                "side_bindings",
                SideBinding.CODEC.listOf()
                    .encodeStart(NbtOps.INSTANCE, sideBindings.toList()).result()
                    .orElseThrow { IllegalStateException("side_bindings encode failed") },
            )
        }
        if (blockName.isNotEmpty()) {
            tag.putString("name", blockName)
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        blockName = tag.getString("name")  // returns "" if missing
        graph = if (tag.contains("graph")) {
            dev.nitka.nodewire.graph.NodeGraph.CODEC
                .parse(NbtOps.INSTANCE, tag.getCompound("graph")).result()
                .orElse(dev.nitka.nodewire.graph.NodeGraph())
        } else {
            dev.nitka.nodewire.graph.NodeGraph()
        }
        bindings.clear()
        if (tag.contains("bindings")) {
            val list = ChannelBinding.CODEC.listOf()
                .parse(NbtOps.INSTANCE, tag.get("bindings")).result()
                .orElse(emptyList())
            bindings.addAll(list)
        }
        sideBindings.clear()
        if (tag.contains("side_bindings")) {
            val list = SideBinding.CODEC.listOf()
                .parse(NbtOps.INSTANCE, tag.get("side_bindings")).result()
                .orElse(emptyList())
            sideBindings.addAll(list)
        }
        invalidateEvaluator()
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun onDataPacket(net: Connection, pkt: ClientboundBlockEntityDataPacket) {
        pkt.tag?.let { load(it) }
    }

    private fun isSideStale(sb: SideBinding, level: Level): Boolean {
        // Source channel must still exist on this BE.
        val srcNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output"
                && it.config.getString("name") == sb.sourceChannelName
        } ?: return true
        val srcType = PinType.fromName(srcNode.config.getString("type"))
        if (srcType != PinType.BOOL && srcType != PinType.INT && srcType != PinType.REDSTONE) {
            return true
        }
        // Target must still be a non-air block. Distance is fine — the
        // signal travels via VirtualSignalMap, not via vanilla neighbour
        // chain. Unloaded chunks are tolerated: we just stop emitting until
        // they reload (don't prune, would surprise the user).
        val state = level.getBlockState(sb.targetPos)
        if (state.isAir) return true
        return false
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
        private const val MAX_NAME_LENGTH = 64
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
