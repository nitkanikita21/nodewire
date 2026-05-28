package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.graph.NodeGraph
import dev.nitka.nodewire.graph.NodeId
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.graph.StatefulGraphEvaluator
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.fml.ModList

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
     * UUID of the Tweaked Controller item bound to this block. `null`
     * when unbound. Persisted to NBT. Mutating via [setControllerId]
     * also fires [setChanged] + a block update so clients see the
     * change (used by the editor toolbar's controller indicator).
     */
    private var controllerId: java.util.UUID? = null

    fun getControllerId(): java.util.UUID? = controllerId

    fun setControllerId(value: java.util.UUID?) {
        if (controllerId == value) return
        controllerId = value
        setChanged()
        val l = level ?: return
        l.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    // --- Tweaked Controller incoming state (push from Mixin) ----------

    /**
     * Latest [dev.nitka.nodewire.integration.tweakedcontroller.ControllerState]
     * applied to this block. Driven by [receiveControllerButtonStates] /
     * [receiveControllerAxisStates] which the TC packet mixins call after
     * decoding each incoming wire packet. Never persisted — purely runtime.
     */
    @Volatile
    var receivedControllerState: dev.nitka.nodewire.integration.tweakedcontroller.ControllerState? = null
        private set

    /** Wall-clock ms of the last state update, for staleness UI. */
    @Volatile
    var lastControllerStateAtMs: Long = 0L
        private set

    /**
     * Apply a decoded button array (TC's [Boolean[15]] unboxed) — bit i
     * is the GLFW gamepad button at index i. Merges with current state
     * so axes stay live between button packets.
     */
    fun receiveControllerButtonStates(buttons: BooleanArray) {
        val prev = receivedControllerState ?: dev.nitka.nodewire.integration.tweakedcontroller.ControllerState.ZERO
        receivedControllerState = prev.withButtonArray(buttons)
        lastControllerStateAtMs = net.minecraft.Util.getMillis()
    }

    /**
     * Apply a decoded axis byte array (TC's [Byte[6]] unboxed: sticks
     * 0..3 with sign-bit and 4-bit magnitude, triggers 4..5 with 4-bit
     * magnitude only). When [fullAxis] is non-null and length ≥ 6, the
     * receiver prefers those higher-precision floats.
     */
    fun receiveControllerAxisStates(axisBytes: ByteArray, fullAxis: FloatArray?) {
        val prev = receivedControllerState ?: dev.nitka.nodewire.integration.tweakedcontroller.ControllerState.ZERO
        receivedControllerState = prev.withAxisArray(axisBytes, fullAxis)
        lastControllerStateAtMs = net.minecraft.Util.getMillis()
    }

    /**
     * Values pushed in from other BEs' [ChannelOutput] nodes via their
     * bindings. Keyed by THIS BE's channel-input name. Read at the start
     * of each tick into the evaluator's externalOutputs map.
     */
    private val externalChannelInputs: MutableMap<String, PinValue> = mutableMapOf()

    private var serverEvaluator: StatefulGraphEvaluator? = null

    /**
     * Per-node Create redstone-link adapters (both input AND output). Transient
     * — rebuilt on demand each server tick; cleared in [setRemoved] so we don't
     * leave dead linkables in Create's network handler. Key = node UUID.
     */
    private val linkables: MutableMap<java.util.UUID, dev.nitka.nodewire.integration.create.CreateRedstoneLink.NodeLinkable> =
        mutableMapOf()

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
    /**
     * Outcome of [tryAddBinding]. Specific enough that the packet handler
     * can surface the failure reason to the player instead of silently
     * dropping the bind.
     */
    sealed interface BindResult {
        object Ok : BindResult
        object EmptyName : BindResult
        object SourceMissing : BindResult
        object TargetMissing : BindResult
        data class TypeMismatch(val srcType: PinType, val tgtType: PinType) : BindResult
    }

    fun tryAddBinding(
        sourceChannelName: String,
        target: LogicBlockEntity,
        targetChannelName: String,
    ): BindResult {
        if (sourceChannelName.isEmpty() || targetChannelName.isEmpty()) return BindResult.EmptyName
        val srcNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output"
                && it.config.getString("name") == sourceChannelName
        } ?: return BindResult.SourceMissing
        val tgtNode = target.graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_input"
                && it.config.getString("name") == targetChannelName
        } ?: return BindResult.TargetMissing
        val srcType = PinType.fromName(srcNode.config.getString("type"))
        val tgtType = PinType.fromName(tgtNode.config.getString("type"))
        // Accept any pair that PinValueConversion can route — same set the
        // edge-read pipeline uses. Strict equality used to reject e.g.
        // Bool channel → Redstone channel even though the value transfers.
        if (!dev.nitka.nodewire.graph.PinValueConversion.canConvert(srcType, tgtType)) {
            return BindResult.TypeMismatch(srcType, tgtType)
        }

        bindings.removeAll {
            it.sourceChannelName == sourceChannelName
                && it.target.payload.blockPos == target.blockPos
                && it.targetChannelName == targetChannelName
        }
        bindings.add(ChannelBinding(sourceChannelName, EndpointRef.from(level!!, target.blockPos), targetChannelName))
        setChanged()
        return BindResult.Ok
    }

    /** Legacy boolean wrapper. Retained for any caller that doesn't care
     *  about the reason; new code should use [tryAddBinding]. */
    fun addBinding(
        sourceChannelName: String,
        target: LogicBlockEntity,
        targetChannelName: String,
    ): Boolean = tryAddBinding(sourceChannelName, target, targetChannelName) is BindResult.Ok

    /**
     * Server-side mutator: replace a node's config blob. Used by
     * [dev.nitka.nodewire.net.BindAeroSourcePacket.Companion.handle] (and any future packet that wants to
     * update a single node's config without a full graph sync). Returns
     * true if the node was found and updated.
     *
     * Caller is responsible for any neighbour-update / block-update
     * broadcast — this method only mutates state + setChanged().
     */
    fun replaceNodeConfig(nodeId: NodeId, newConfig: CompoundTag): Boolean {
        val existing = graph.nodes[nodeId] ?: return false
        graph.nodes[nodeId] = existing.copy(config = newConfig)
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
                && it.target.payload.blockPos == targetPos
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
                && it.target.payload.blockPos == targetPos
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

        if (level == null) return false
        sideBindings.removeAll {
            it.sourceChannelName == sourceChannelName
                && it.target.payload.blockPos == targetPos
                && it.targetSide == targetSide
        }
        val ref = dev.nitka.nodewire.endpoint.EndpointRef.from(level!!, targetPos)
        sideBindings.add(SideBinding(sourceChannelName, ref, targetSide))
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
                && it.target.payload.blockPos == targetPos
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

    /**
     * Ensure each redstone_link_input/output node in the graph has a registered
     * NodeLinkable on Create's network handler. Reconfigures on freq change,
     * updates lastTransmit for output nodes, prunes orphans whose node was
     * deleted by the user. Reads lastReceived for input nodes are done in
     * the caller's external-input collection (Step 3 in serverTick).
     */
    private fun syncRedstoneLinkables(level: Level, result: dev.nitka.nodewire.graph.EvalResult) {
        val CR = dev.nitka.nodewire.integration.create.CreateRedstoneLink
        val seen = HashSet<java.util.UUID>()
        for (node in graph.nodes.values) {
            val listening = when (node.typeKey.path) {
                "redstone_link_input" -> true
                "redstone_link_output" -> false
                else -> continue
            }
            seen.add(node.id)
            val desiredFreq = CR.frequencyOf(node.config, level)
            val existing = linkables[node.id]
            val linkable = if (existing == null) {
                val l = dev.nitka.nodewire.integration.create.CreateRedstoneLink.NodeLinkable(this, desiredFreq, listening)
                linkables[node.id] = l
                CR.register(level, l)
                l
            } else if (existing.freq != desiredFreq) {
                CR.unregister(level, existing)
                existing.freq = desiredFreq
                CR.register(level, existing)
                existing
            } else {
                existing
            }
            if (!listening) {
                // Output: pull incoming edge value, update transmit.
                val edge = graph.edges.firstOrNull { it.to.node == node.id && it.to.pin == "in" }
                val value = edge?.let { result.valueAt(it.from.node, it.from.pin) }
                linkable.lastTransmit = redstoneOf(value)
                CR.updateNetworkOf(level, linkable)
            }
        }
        // Drop orphans (user deleted the node).
        val orphans = linkables.keys - seen
        for (id in orphans) {
            linkables.remove(id)?.let { CR.unregister(level, it) }
        }
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
                "redstone_link_input" -> {
                    val linkable = linkables[node.id] ?: continue
                    external[node.id to "out"] = PinValue.Redstone(linkable.lastReceived)
                }
            }
        }

        val aeroSnap = dev.nitka.nodewire.integration.aeronautics.AeroStatePipeline
            .snapshot(level, graph)
        val prevAero = dev.nitka.nodewire.integration.aeronautics.AeroStatePipeline
            .currentValues.get()
        dev.nitka.nodewire.integration.aeronautics.AeroStatePipeline
            .currentValues.set(aeroSnap)
        val result = try {
            val tcNode = dev.nitka.nodewire.integration.tweakedcontroller.ControllerInputNode
            val prevTcState = tcNode.currentState.get()
            tcNode.currentState.set(receivedControllerState)
            try {
                eval.tick(external)
            } finally {
                tcNode.currentState.set(prevTcState)
            }
        } finally {
            dev.nitka.nodewire.integration.aeronautics.AeroStatePipeline
                .currentValues.set(prevAero)
        }

        // CC: Tweaked event dispatch — skip everything if the mod isn't
        // present so the BE class loads cleanly on CC-less servers.
        if (net.neoforged.fml.ModList.get().isLoaded("computercraft") &&
            nwAttachedPeripheralsView().isNotEmpty()) {
            val newSnap = dev.nitka.nodewire.integration.cctweaked
                .NwChannelIntrospection.outputSnapshot(graph, result)
            // Build the attachments list as Lua-typed pairs. We hop
            // through `Any` in the BE field to keep CC API types out of
            // the BE compile scope; cast back here behind the ModList
            // gate where it's safe.
            val attachments = nwAttachedPeripheralsView().flatMap { p ->
                val peripheral = p as dev.nitka.nodewire.integration.cctweaked.NodewirePeripheral
                peripheral.attachmentsSnapshot()
            }
            dev.nitka.nodewire.integration.cctweaked.NwChannelEventDispatch
                .diffAndBroadcast(attachments, prev = nwChannelOutputSnapshotView(), new = newSnap)
            // Clear all per-attach initial-sync flags now that they've
            // fired at least once.
            for (p in nwAttachedPeripheralsView()) {
                val peripheral = p as dev.nitka.nodewire.integration.cctweaked.NodewirePeripheral
                for ((computer, _) in peripheral.attachmentsSnapshot()) {
                    peripheral.clearInitialSync(computer)
                }
            }
            nwUpdateChannelOutputSnapshot(newSnap)
        }

        if (ModList.get().isLoaded("create")) {
            syncRedstoneLinkables(level, result)
        }

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
                map.put(pos, sb.target.payload.blockPos, sb.targetSide, r)
            }
            // Schedule a neighbour update on each affected target so it
            // re-polls its power state; without this most blocks won't
            // notice until something else perturbs them.
            for (sb in sideBindings) {
                val targetState = level.getBlockState(sb.target.payload.blockPos)
                if (!targetState.isAir) {
                    level.neighborChanged(sb.target.payload.blockPos, targetState.block, sb.target.payload.blockPos)
                    // Also poke updateNeighborsAt so block entities that
                    // chain (e.g. piston extensions) see the change.
                    level.updateNeighborsAt(sb.target.payload.blockPos, targetState.block)
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
                val target = binding.target.resolve(level) as? LogicBlockEntity ?: continue
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
                    val tgt = lvl.getBlockState(sb.target.payload.blockPos)
                    if (!tgt.isAir) {
                        lvl.neighborChanged(sb.target.payload.blockPos, tgt.block, sb.target.payload.blockPos)
                        lvl.updateNeighborsAt(sb.target.payload.blockPos, tgt.block)
                    }
                }
                if (ModList.get().isLoaded("create")) {
                    val CR = dev.nitka.nodewire.integration.create.CreateRedstoneLink
                    for ((_, l) in linkables) CR.unregister(lvl, l)
                    linkables.clear()
                }
            }
        }
        super.setRemoved()
    }

    override fun saveAdditional(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
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
        controllerId?.let { tag.putUUID("controllerId", it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        blockName = tag.getString("name")  // returns "" if missing
        controllerId = if (tag.hasUUID("controllerId")) tag.getUUID("controllerId") else null
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

    override fun getUpdateTag(registries: net.minecraft.core.HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    private fun isSideStale(sb: SideBinding, level: Level): Boolean {
        // Source channel must still exist on this BE.
        val srcNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output"
                && it.config.getString("name") == sb.sourceChannelName
        } ?: return true
        // Source type must be convertible to REDSTONE (redstoneOf routes
        // any scalar through PinValueConversion). Vectors/strings without a
        // sensible numeric mapping prune the binding.
        val srcType = PinType.fromName(srcNode.config.getString("type"))
        if (!dev.nitka.nodewire.graph.PinValueConversion.canConvert(srcType, PinType.REDSTONE)) {
            return true
        }
        // Target must still be a non-air block. Distance is fine — the
        // signal travels via VirtualSignalMap, not via vanilla neighbour
        // chain. Unloaded chunks are tolerated: we just stop emitting until
        // they reload (don't prune, would surprise the user).
        val state = level.getBlockState(sb.target.payload.blockPos)
        if (state.isAir) return true
        return false
    }

    private fun isStale(binding: ChannelBinding, level: Level): Boolean {
        val srcNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output"
                && it.config.getString("name") == binding.sourceChannelName
        } ?: return true
        val target = binding.target.resolve(level) as? LogicBlockEntity ?: return true
        val tgtNode = target.graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_input"
                && it.config.getString("name") == binding.targetChannelName
        } ?: return true
        // Allow any pair whose values can flow through PinValueConversion.
        // Strict equality was too aggressive — Constant(Bool) → channel_output(redstone)
        // routed across to channel_input(redstone) used to prune as Bool != Redstone.
        val srcType = PinType.fromName(srcNode.config.getString("type"))
        val tgtType = PinType.fromName(tgtNode.config.getString("type"))
        return !dev.nitka.nodewire.graph.PinValueConversion.canConvert(srcType, tgtType)
    }

    // ── CC: Tweaked attached-peripheral tracking ──────────────────────────
    // Stored as Any so the BE class doesn't reference CC API types
    // directly — that way it loads cleanly when CC: Tweaked is absent.
    // Cast back to NodewirePeripheral happens only behind a ModList gate
    // (see serverTick wiring in Task 9). Mutated only from the server
    // thread; CC dispatches on its own executor but trampolines via
    // Capabilities → server tick task queue.
    @Transient
    private val nwAttachedPeripherals: MutableSet<Any> = HashSet()

    /** Called from `NodewirePeripheral.attach`. Idempotent. */
    fun nwAttachPeripheral(p: Any) { nwAttachedPeripherals.add(p) }

    /** Called from `NodewirePeripheral.detach` when the last computer leaves. */
    fun nwDetachPeripheral(p: Any) { nwAttachedPeripherals.remove(p) }

    /** Read-only view used by [serverTick]'s CC dispatch step (added in T9). */
    internal fun nwAttachedPeripheralsView(): Set<Any> = nwAttachedPeripherals

    /**
     * Last-tick snapshot of `channel_output` values, keyed by channel
     * name. Updated end-of-tick by the CC integration; consulted from
     * Lua [dev.nitka.nodewire.integration.cctweaked.NodewirePeripheral.getChannel].
     */
    @Transient
    private var nwChannelOutputSnapshot: Map<String, dev.nitka.nodewire.graph.PinValue> = emptyMap()

    internal fun nwChannelOutputSnapshotView(): Map<String, dev.nitka.nodewire.graph.PinValue> =
        nwChannelOutputSnapshot

    internal fun nwUpdateChannelOutputSnapshot(snap: Map<String, dev.nitka.nodewire.graph.PinValue>) {
        nwChannelOutputSnapshot = snap
    }

    /**
     * Write to a `channel_input`-fed external. Same map the cross-block
     * channel bindings use, so a Lua writer and another BE writer
     * compete on a last-writer-wins basis (which is the existing
     * channel-binding behaviour).
     */
    internal fun nwWriteChannelInput(name: String, value: dev.nitka.nodewire.graph.PinValue) {
        externalChannelInputs[name] = value
        setChanged()
    }

    companion object {
        private const val MAX_NAME_LENGTH = 64
        private val DIRECTIONS_BY_NAME = Direction.entries.associateBy { it.name.lowercase() }

        private fun directionOf(name: String): Direction? =
            DIRECTIONS_BY_NAME[name.lowercase()]

        internal fun redstoneOf(value: PinValue?): Int {
            if (value == null) return 0
            // Bool keeps its emission convention: true → 15 (full signal),
            // not 1 (which is what the generic numeric conversion would give).
            if (value is PinValue.Bool) return if (value.value) 15 else 0
            val converted = dev.nitka.nodewire.graph.PinValueConversion
                .convert(value, dev.nitka.nodewire.graph.PinType.REDSTONE)
            return (converted as? PinValue.Redstone)?.value?.coerceIn(0, 15) ?: 0
        }
    }
}
