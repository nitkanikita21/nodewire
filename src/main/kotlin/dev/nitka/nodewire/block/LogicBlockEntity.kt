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

    /**
     * Mirror of [sideBindings]: each entry feeds a redstone level read from
     * an arbitrary world block into a named `channel_input` on this BE.
     */
    private val remoteRedstoneBindings: MutableList<RemoteRedstoneBinding> = mutableListOf()

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

    /**
     * Last-tick emitted redstone per (targetPos, targetSide) for our side
     * bindings. We fire a neighbour update only when a slot's value actually
     * changes — mirrors DriveByWire's `WireNetworkNode.setInput` change-gate
     * and avoids a per-tick neighbour-update storm on held signals.
     * Transient — rebuilt from live bindings each server tick.
     */
    private val lastSideEmit: MutableMap<Pair<BlockPos, Direction>, Int> = HashMap()

    fun invalidateEvaluator() {
        serverEvaluator = null
        // Drop the per-node script runtimes too: the evaluator is rebuilt with
        // fresh `state` tags, so the old (identity-keyed) runtimes would leak
        // their scopes. Coarse (global) but correct — live nodes rebuild from
        // NBT next tick (spec D10).
        dev.nitka.nodewire.script.ScriptNodeRuntime.cancelAll()
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

    /**
     * Server-side mutator for the script node: write a new `src` into the
     * node's config, re-reshape its pins from the script header (via the
     * registered type's [dev.nitka.nodewire.graph.NodeType.pinsFor] →
     * `HeaderLexer`), prune any edges whose endpoint pin vanished or no
     * longer type-converts, and invalidate the cached evaluator so the new
     * source compiles fresh on the next tick. Returns true if the node was
     * found and is a `script` node.
     *
     * Caller is responsible for the block-update broadcast — this method
     * only mutates state + setChanged(). Used by
     * [dev.nitka.nodewire.net.SetScriptSourcePacket.Companion.handle].
     */
    fun setScriptSource(nodeId: NodeId, src: String): Boolean {
        if (!applyScriptSourceToGraph(graph, nodeId, src)) return false
        invalidateEvaluator()
        setChanged()
        return true
    }

    /**
     * Component G — server-thread poll of script-node compile status. For every
     * `script` node, reads [dev.nitka.nodewire.script.ScriptNodeRuntime.statusOf]
     * (side-effect-free, safe on the tick thread) for its current `src` and, when
     * the synced token or text differs from what's already in the node config,
     * stamps the new values into the config via
     * [dev.nitka.nodewire.script.ScriptDiagnostics]. Returns true if any node's
     * diagnostics changed (the caller then fires one setChanged + block update).
     *
     * Writes only the `__diag_*` keys — `src` is untouched, so neither the pin
     * reshape nor the compile cache key is affected.
     */
    private fun stampScriptDiagnostics(): Boolean {
        val SD = dev.nitka.nodewire.script.ScriptDiagnostics
        var changed = false
        for ((id, node) in graph.nodes) {
            if (node.typeKey.path != "script") continue
            val status = dev.nitka.nodewire.script.ScriptNodeRuntime.statusOf(node.config.getString("src"))
            val token = SD.statusToken(status)
            val text = SD.diagnosticsText(status)
            if (SD.readStatus(node.config) == token && SD.readText(node.config) == text) continue
            val newConfig = node.config.copy().apply {
                putString(SD.STATUS_KEY, token)
                putString(SD.TEXT_KEY, text)
            }
            graph.nodes[id] = node.copy(config = newConfig)
            changed = true
        }
        return changed
    }

    fun bindingsSnapshot(): List<ChannelBinding> = bindings.toList()
    fun sideBindingsSnapshot(): List<SideBinding> = sideBindings.toList()
    fun remoteRedstoneBindingsSnapshot(): List<RemoteRedstoneBinding> = remoteRedstoneBindings.toList()

    /**
     * Add a binding from a world block at [sourcePos] to the named
     * `channel_input` on this BE. Returns false if the channel doesn't
     * exist, isn't redstone-coercible, or the source is air.
     * Duplicates (same target channel + source pos) replace the existing entry.
     */
    fun addRemoteRedstoneBinding(targetChannelName: String, sourcePos: BlockPos): Boolean {
        if (targetChannelName.isEmpty()) return false
        val lvl = level ?: return false
        if (lvl.getBlockState(sourcePos).isAir) return false
        val tgtNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_input"
                && it.config.getString("name") == targetChannelName
        } ?: return false
        val tgtType = PinType.fromName(tgtNode.config.getString("type"))
        if (!dev.nitka.nodewire.graph.PinValueConversion.canConvert(PinType.REDSTONE, tgtType)) return false
        remoteRedstoneBindings.removeAll {
            it.targetChannelName == targetChannelName
                && it.source.payload.blockPos == sourcePos
        }
        val ref = dev.nitka.nodewire.endpoint.EndpointRef.from(lvl, sourcePos)
        remoteRedstoneBindings.add(RemoteRedstoneBinding(targetChannelName, ref))
        setChanged()
        return true
    }

    fun removeRemoteRedstoneBinding(targetChannelName: String, sourcePos: BlockPos): Boolean {
        val removed = remoteRedstoneBindings.removeAll {
            it.targetChannelName == targetChannelName
                && it.source.payload.blockPos == sourcePos
        }
        if (removed) setChanged()
        return removed
    }

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
            level?.let { lvl ->
                // Pre-empt the next-tick rewrite: clear our contribution now
                // so the neighbour-update we fire sees 0.
                dev.nitka.nodewire.signal.VirtualSignalMap.of(lvl).put(blockPos, targetPos, targetSide, 0)
                lastSideEmit.remove(targetPos to targetSide)
                // Poke the virtual-source neighbour (and the target) so the
                // target re-reads the now-zero bound face — same delivery
                // path as serverTick.
                val from = targetPos.relative(targetSide)
                lvl.updateNeighborsAt(from, lvl.getBlockState(from).block)
                if (!lvl.getBlockState(targetPos).isAir) {
                    lvl.updateNeighborsAt(targetPos, lvl.getBlockState(targetPos).block)
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

        if (remoteRedstoneBindings.isNotEmpty()) {
            val before = remoteRedstoneBindings.size
            remoteRedstoneBindings.removeAll { isRemoteRedstoneStale(it, level) }
            if (remoteRedstoneBindings.size != before) {
                setChanged()
                level.sendBlockUpdated(pos, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
            }
            // Poll each source's best-neighbour redstone and inject into the
            // corresponding channel_input. Mirrors how cross-block ChannelBinding
            // sets externalChannelInputs on the target BE — same delivery slot.
            for (rrb in remoteRedstoneBindings) {
                val sigLevel = level.getBestNeighborSignal(rrb.source.payload.blockPos)
                externalChannelInputs[rrb.targetChannelName] = PinValue.Redstone(sigLevel)
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

        // Dispatch any log()/chat() that script nodes emitted during eval.tick.
        dev.nitka.nodewire.script.ScriptMessageSink.drain().let { msgs ->
            if (msgs.isNotEmpty()) dispatchScriptMessages(level, pos, msgs)
        }

        // Component G: poll each script node's compile status (computed off the
        // tick thread in ScriptNodeRuntime) and stamp a compact diagnostics
        // summary into its config when it changes. Done here, on the server
        // thread, so the BE mutation + client re-sync stay on-thread (Option A
        // from the design — no risky off-thread BE writes). The keys ride the
        // existing Node.CODEC config round-trip, so the badge/editor read them
        // for free; they never touch `src`, so the compile cache key is intact.
        if (stampScriptDiagnostics()) {
            setChanged()
            level.sendBlockUpdated(pos, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS)
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
        // mixin into Level.getSignal surfaces these to any block that polls
        // its neighbours — distance and adjacency are irrelevant. We collect
        // per (targetPos, targetSide) and take the max if multiple sources
        // drive the same slot.
        //
        // Delivery: the signal arrives on `targetSide`, i.e. as if a redstone
        // source sat at `from = targetPos.relative(targetSide)`. To make the
        // target re-poll we therefore poke `from` (whose neighbour-notify
        // reaches the target ON the bound face, with the correct fromPos)
        // plus `to` (so re-emitting blocks chain) — exactly how vanilla
        // redstone propagates and how DriveByWire's ship-wire sinks deliver.
        // Poking the target *itself* (the old behaviour) only worked for
        // face-agnostic consumers (vanilla lamp, self-polling Create
        // gearshift); a mod block that keys on which face changed never saw
        // it. Gate on value-change to avoid a per-tick neighbour-update storm.
        if (sideBindings.isNotEmpty() || lastSideEmit.isNotEmpty()) {
            val map = dev.nitka.nodewire.signal.VirtualSignalMap.of(level)
            // Remove any previous contributions from this source first so a
            // dropped binding clears its signal in the same tick.
            map.clearSource(pos)
            val nowEmit = HashMap<Pair<BlockPos, Direction>, Int>()
            for (sb in sideBindings) {
                val value = perChannelValueCache[sb.sourceChannelName] ?: continue
                val slot = sb.target.payload.blockPos to sb.targetSide
                nowEmit[slot] = maxOf(nowEmit[slot] ?: 0, redstoneOf(value))
            }
            for ((slot, r) in nowEmit) map.put(pos, slot.first, slot.second, r)
            // Poke only slots whose value changed since last tick (incl. ones
            // that dropped to 0 because their binding was pruned/removed).
            for (slot in nowEmit.keys + lastSideEmit.keys) {
                if ((nowEmit[slot] ?: 0) == (lastSideEmit[slot] ?: 0)) continue
                val to = slot.first
                val from = to.relative(slot.second)
                level.updateNeighborsAt(from, level.getBlockState(from).block)
                level.updateNeighborsAt(to, level.getBlockState(to).block)
            }
            lastSideEmit.clear()
            lastSideEmit.putAll(nowEmit)
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
                // neighbour-change events, not on a passive value drop). Poke
                // the virtual-source neighbour `from` so the target sees the
                // drop on the bound face (same delivery as serverTick).
                for (sb in sideBindings) {
                    val to = sb.target.payload.blockPos
                    val from = to.relative(sb.targetSide)
                    lvl.updateNeighborsAt(from, lvl.getBlockState(from).block)
                    if (!lvl.getBlockState(to).isAir) {
                        lvl.updateNeighborsAt(to, lvl.getBlockState(to).block)
                    }
                }
                if (ModList.get().isLoaded("create")) {
                    val CR = dev.nitka.nodewire.integration.create.CreateRedstoneLink
                    for ((_, l) in linkables) CR.unregister(lvl, l)
                    linkables.clear()
                }
            }
        }
        // Cancel this BE's per-node script coroutine scopes on unload/removal
        // (spec D10). Global cancel is acceptable Phase-1 behavior: any still-live
        // node rebuilds its runtime from persisted NBT on the next server tick.
        dev.nitka.nodewire.script.ScriptNodeRuntime.cancelAll()
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
        if (remoteRedstoneBindings.isNotEmpty()) {
            tag.put(
                "remote_redstone_bindings",
                RemoteRedstoneBinding.CODEC.listOf()
                    .encodeStart(NbtOps.INSTANCE, remoteRedstoneBindings.toList()).result()
                    .orElseThrow { IllegalStateException("remote_redstone_bindings encode failed") },
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
        remoteRedstoneBindings.clear()
        if (tag.contains("remote_redstone_bindings")) {
            val list = RemoteRedstoneBinding.CODEC.listOf()
                .parse(NbtOps.INSTANCE, tag.get("remote_redstone_bindings")).result()
                .orElse(emptyList())
            remoteRedstoneBindings.addAll(list)
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

    private fun isRemoteRedstoneStale(rrb: RemoteRedstoneBinding, level: Level): Boolean {
        // Target channel must still exist on this BE.
        val tgtNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_input"
                && it.config.getString("name") == rrb.targetChannelName
        } ?: return true
        val tgtType = PinType.fromName(tgtNode.config.getString("type"))
        if (!dev.nitka.nodewire.graph.PinValueConversion.canConvert(PinType.REDSTONE, tgtType)) return true
        // Source must still be a non-air block. Unloaded chunks tolerate
        // (channel just stays at last value); only air means it was broken.
        val state = level.getBlockState(rrb.source.payload.blockPos)
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

        /**
         * Pure graph mutation behind [setScriptSource] — extracted so it is
         * unit-testable without a live BlockEntity. Writes [src] into the
         * `script` node [nodeId], re-reshapes its pins from the script header
         * (via the registered type's `pinsFor` → `HeaderLexer`), and prunes
         * edges to vanished/incompatible pins. Returns true if the node was
         * found and is a `script` node. Does NOT call `setChanged()` /
         * `invalidateEvaluator()` — the instance method owns those side effects.
         */
        fun applyScriptSourceToGraph(graph: NodeGraph, nodeId: NodeId, src: String): Boolean {
            val existing = graph.nodes[nodeId] ?: return false
            if (existing.typeKey.path != "script") return false
            val type = dev.nitka.nodewire.graph.NodeTypeRegistry.get(existing.typeKey) ?: return false
            val newConfig = existing.config.copy().apply { putString("src", src) }
            val (ins, outs) = type.pinsFor(newConfig)
            graph.nodes[nodeId] = existing.copy(inputs = ins, outputs = outs, config = newConfig)
            pruneIncompatibleEdges(graph, nodeId)
            return true
        }

        /**
         * Reshape-aware edge pruner — server-side authoritative port of
         * `EditorState._pruneIncompatibleEdgesInternal`. Drops edges touching
         * [id] whose endpoint pin no longer exists, or whose endpoint pin type
         * can no longer convert to/from the other side per
         * [dev.nitka.nodewire.graph.PinValueConversion.canConvert]. Edges that
         * still type-check survive (e.g. an output kept at the same pin id and
         * type after a header edit).
         */
        private fun pruneIncompatibleEdges(graph: NodeGraph, id: NodeId) {
            val node = graph.nodes[id] ?: return
            val inputById = node.inputs.associateBy { it.id }
            val outputById = node.outputs.associateBy { it.id }
            graph.edges.removeAll { e ->
                val touchesFrom = e.from.node == id
                val touchesTo = e.to.node == id
                if (!touchesFrom && !touchesTo) return@removeAll false
                val ourPinType = when {
                    touchesFrom -> outputById[e.from.pin]?.type ?: return@removeAll true
                    else -> inputById[e.to.pin]?.type ?: return@removeAll true
                }
                val otherNodeId = if (touchesFrom) e.to.node else e.from.node
                val otherNode = graph.nodes[otherNodeId] ?: return@removeAll true
                val otherPinType = if (touchesFrom) {
                    otherNode.inputs.firstOrNull { it.id == e.to.pin }?.type
                } else {
                    otherNode.outputs.firstOrNull { it.id == e.from.pin }?.type
                } ?: return@removeAll true
                val (srcType, dstType) = if (touchesFrom) ourPinType to otherPinType
                    else otherPinType to ourPinType
                !dev.nitka.nodewire.graph.PinValueConversion.canConvert(srcType, dstType)
            }
        }

        private fun directionOf(name: String): Direction? =
            DIRECTIONS_BY_NAME[name.lowercase()]

        private val SCRIPT_LOG: org.slf4j.Logger = com.mojang.logging.LogUtils.getLogger()
        private const val SCRIPT_CHAT_RANGE = 16.0

        /** Deliver a tick's script log()/chat() messages: LOG to console, CHAT to nearby players. */
        private fun dispatchScriptMessages(
            level: Level,
            pos: BlockPos,
            msgs: List<dev.nitka.nodewire.script.ScriptMessage>,
        ) {
            val server = level as? net.minecraft.server.level.ServerLevel
            for (m in msgs) {
                when (m.kind) {
                    dev.nitka.nodewire.script.MessageKind.LOG ->
                        SCRIPT_LOG.info("[script @ {}] {}", pos, m.text)
                    dev.nitka.nodewire.script.MessageKind.CHAT -> {
                        val comp = net.minecraft.network.chat.Component.literal(m.text)
                        server?.players()
                            ?.filter { pos.closerThan(it.blockPosition(), SCRIPT_CHAT_RANGE) }
                            ?.forEach { it.sendSystemMessage(comp) }
                    }
                }
            }
        }

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
