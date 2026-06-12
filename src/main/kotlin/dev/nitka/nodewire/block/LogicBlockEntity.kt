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
    BlockEntity(Registry.LOGIC_BLOCK_BE.get(), pos, state),
    ChannelInputSink,
    dev.nitka.nodewire.link.PinLinkSink {

    var graph: NodeGraph = NodeGraph()

    /** Channel links established from this BE (source) to others. */
    private val bindings: MutableList<ChannelBinding> = mutableListOf()

    /**
     * Drive-by-wire links from this BE's channels to a face of an
     * arbitrary adjacent block. Applied as a redstone signal on this
     * block's face pointing at the target.
     */
    private val sideBindings: MutableList<SideBinding> = mutableListOf()

    // ── unified pin links (this BE as SINK) ──────────────────────────────
    // Every "something feeds my channel_input" relation — a camera's video
    // handle, a screen's touch events, a world block's redstone, ANOTHER
    // logic block's channel_output — is one PinLink, pulled per server tick
    // by PinLinkEngine. Created by the Channel Link Tool via BindPinPacket.

    private val pinLinks: MutableList<dev.nitka.nodewire.link.PinLink> = mutableListOf()

    override val pinLinkScratch = dev.nitka.nodewire.link.PinLinkScratch()

    override fun pinLinks(): MutableList<dev.nitka.nodewire.link.PinLink> = pinLinks

    fun pinLinksSnapshot(): List<dev.nitka.nodewire.link.PinLink> = pinLinks.toList()

    override fun onPinLinksChanged() {
        setChanged()
    }

    // ── PinPort: this block's linkable surface ───────────────────────────
    // Outputs = named channel_output nodes (sampled from the last tick's
    // value snapshot); inputs = named channel_input nodes (delivered into
    // the same externalChannelInputs slot every legacy path used).

    override fun pinOutputs(ctx: dev.nitka.nodewire.link.LinkContext): List<dev.nitka.nodewire.link.LinkPin> =
        channelPins("channel_output")

    override fun pinInputs(ctx: dev.nitka.nodewire.link.LinkContext): List<dev.nitka.nodewire.link.LinkPin> =
        channelPins("channel_input")

    private fun channelPins(nodePath: String): List<dev.nitka.nodewire.link.LinkPin> =
        graph.nodes.values
            .filter { it.typeKey.path == nodePath }
            .mapNotNull { node ->
                val name = node.config.getString("name")
                if (name.isEmpty()) null
                else dev.nitka.nodewire.link.LinkPin(name, PinType.fromName(node.config.getString("type")))
            }

    /** Last tick's channel_output values, refreshed unconditionally at the
     *  end of [serverTick] — what other sinks' [readPin] pulls see. */
    @Transient
    private var pinReadSnapshot: Map<String, PinValue> = emptyMap()

    override fun readPin(id: String): dev.nitka.nodewire.link.PinReading? =
        pinReadSnapshot[id]?.let { dev.nitka.nodewire.link.PinReading(it) }

    override fun writePin(id: String, value: PinValue) {
        externalChannelInputs[id] = value
    }

    override fun clearPin(id: String) {
        externalChannelInputs.remove(id)
    }

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
     *
     * Concurrent: written on the server thread; the singleplayer editor
     * preview reads a snapshot from the render thread (the integrated-server
     * bridge in NodeEditorScreen) so its channel_input chips show LIVE
     * delivered values instead of permanent zeros.
     */
    private val externalChannelInputs: MutableMap<String, PinValue> =
        java.util.concurrent.ConcurrentHashMap()

    /** Render-thread-safe copy for the editor preview (SP integrated server). */
    fun externalInputsSnapshot(): Map<String, PinValue> = HashMap(externalChannelInputs)

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

    /**
     * Phase 2b — CLIENT-side replicated script state, keyed by node id. The
     * server pushes [dev.nitka.nodewire.net.StateDeltaPacket]s (and seeds late
     * joiners via [getUpdateTag]); each delta merges into the node's tag here.
     * A future client script runtime (2c) reads from this map BEFORE its
     * client behaviors run. No server-side meaning. Client-thread only.
     */
    private val clientReplicatedState: MutableMap<NodeId, CompoundTag> = HashMap()

    /** Read-only view of a node's current client replicated state (2c will consume). */
    fun clientReplicatedStateOf(nodeId: NodeId): CompoundTag? = clientReplicatedState[nodeId]

    /**
     * Phase 2c — CLIENT frame driver enumeration. For each `script` node on this
     * BE's client graph copy, yields `(src, stateTag)` where [stateTag] is the
     * node's client replicated-state slot. The slot is created lazily here (via
     * [getOrPut]) so its **identity is stable** across frames — the client
     * runtime keys its per-node [dev.nitka.nodewire.script.ScriptRuntime] on that
     * tag instance ([ClientScriptNodeRuntime.frameTick]), so a node whose server
     * hasn't yet pushed a delta still gets a stable (empty) tag rather than a new
     * one each frame. Client-thread only. Skips nodes with a blank `src`.
     */
    fun clientScriptNodes(): List<Pair<String, CompoundTag>> {
        val out = ArrayList<Pair<String, CompoundTag>>()
        for ((id, node) in graph.nodes) {
            if (node.typeKey.path != "script") continue
            val src = node.config.getString("src")
            if (src.isBlank()) continue
            val slot = clientReplicatedState.getOrPut(id) { CompoundTag() }
            out.add(src to slot)
        }
        return out
    }

    /** All client replicated-state tags currently held (for unload-cancel). */
    fun clientReplicatedStateTags(): Collection<CompoundTag> = clientReplicatedState.values

    fun invalidateEvaluator() {
        serverEvaluator = null
        // Drop the per-node script runtimes too: the evaluator is rebuilt with
        // fresh `state` tags, so the old (identity-keyed) runtimes would leak
        // their scopes. Coarse (global) but correct — live nodes rebuild from
        // NBT next tick (spec D10).
        dev.nitka.nodewire.script.ScriptNodeRuntime.cancelAll()
    }

    // NOTE (unified linking, 2026-06-11): logic→logic links are no longer
    // CREATED here — the Link Tool stores a PinLink on the TARGET, which
    // pulls from this block's channel snapshot. The legacy source-side
    // [bindings] list keeps loading, ticking and pushing for old worlds
    // (and the Link Manager can still remove its entries), it just gains
    // no new entries.

    /**
     * Server-side mutator: replace a node's config blob (single-node config
     * update without a full graph sync). Returns true if the node was found
     * and updated. Caller owns any block-update broadcast.
     */
    /**
     * Phase 2b — CLIENT-only: merge replicated state [deltaTag] (a multi-key tag
     * of cell values) into [nodeId]'s client replicated-state slot. No broadcast
     * (the data CAME from the server). Returns false if the node is gone. This is
     * the thin "apply a delta into the node's state tag" helper — it does NOT run
     * any client script runtime (that is 2c). Client-thread only.
     */
    fun applyClientStateDelta(nodeId: NodeId, deltaTag: CompoundTag): Boolean {
        if (nodeId !in graph.nodes) return false
        val slot = clientReplicatedState.getOrPut(nodeId) { CompoundTag() }
        slot.merge(deltaTag)
        return true
    }

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
        if (removed) {
            setChanged()
            // If the target is a non-Logic channel sink (e.g. a video Screen),
            // clear its slot so it blanks on unbind — its transient input is
            // otherwise never reset (delivery just stops, leaving the last value).
            val lvl = level
            val tgt = lvl?.getBlockEntity(targetPos)
            if (tgt is ChannelInputSink && tgt !is LogicBlockEntity) {
                val type = (tgt as? dev.nitka.nodewire.link.PinPort)
                    ?.pinInputs(dev.nitka.nodewire.link.LinkContext(lvl, targetPos, lvl.getBlockState(targetPos)))
                    ?.firstOrNull { it.id == targetChannelName }?.type
                    ?: dev.nitka.nodewire.graph.PinType.VIDEO
                tgt.writeChannelInput(targetChannelName, dev.nitka.nodewire.graph.PinValue.default(type))
            }
        }
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

        // Unified pin links: prune stale, pull every bound source pin (camera
        // video, screen touch, world redstone, other logic blocks' channels…)
        // into externalChannelInputs, clear slots that went silent.
        dev.nitka.nodewire.link.PinLinkEngine.tick(level, this)

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

        val sensorSnap = dev.nitka.nodewire.integration.sensor.SensorStatePipeline
            .snapshot(level, graph)
        val prevSensor = dev.nitka.nodewire.integration.sensor.SensorStatePipeline
            .currentValues.get()
        dev.nitka.nodewire.integration.sensor.SensorStatePipeline
            .currentValues.set(sensorSnap)
        val result = try {
            val aeroSnap = dev.nitka.nodewire.integration.aeronautics.AeroStatePipeline
                .snapshot(level, graph)
            val prevAero = dev.nitka.nodewire.integration.aeronautics.AeroStatePipeline
                .currentValues.get()
            dev.nitka.nodewire.integration.aeronautics.AeroStatePipeline
                .currentValues.set(aeroSnap)
            try {
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
        } finally {
            dev.nitka.nodewire.integration.sensor.SensorStatePipeline
                .currentValues.set(prevSensor)
        }

        // Dispatch any log()/chat() that script nodes emitted during eval.tick.
        dev.nitka.nodewire.script.ScriptMessageSink.drain().let { msgs ->
            if (msgs.isNotEmpty()) dispatchScriptMessages(level, pos, msgs)
        }

        // Phase 2b: replicated state deltas. The node was evaluated above; its
        // per-node ScriptRuntime buffered any replicated-cell changes on the OWNED
        // (fully-parked) branch after saveState (race-free). Drain per script node
        // and send a small delta packet to players tracking this BE's chunk —
        // bypasses the full-graph sendBlockUpdated path (spec §5.2/§5.4). On-change
        // only: an empty drain sends nothing.
        if (level is net.minecraft.server.level.ServerLevel) {
            for (node in graph.nodes.values) {
                if (node.typeKey.path != "script") continue
                val nodeState = eval.nodeState(node.id) ?: continue
                val deltas = dev.nitka.nodewire.script.ScriptNodeRuntime.drainReplicatedDeltas(nodeState)
                if (deltas.isEmpty()) continue
                val cellDeltas = deltas.map {
                    dev.nitka.nodewire.net.CellDelta(
                        it.key, it.kind,
                        dev.nitka.nodewire.script.ScriptModuleReplication.encodeCell(it),
                    )
                }
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk(
                    level,
                    net.minecraft.world.level.ChunkPos(pos),
                    dev.nitka.nodewire.net.StateDeltaPacket(pos, node.id, cellDeltas),
                )
            }
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
                val resolved = binding.target.resolve(level)
                when (resolved) {
                    // Logic→Logic: byte-identical legacy path (direct slot write).
                    // Key by target's input name — they may differ from the
                    // source channel's name. The target reads this slot on its
                    // own next tick when it processes channel_input nodes.
                    is LogicBlockEntity ->
                        resolved.externalChannelInputs[binding.targetChannelName] = value
                    // Additive: endpoint consumers that own no graph (e.g. the
                    // video Screen) receive the same value via ChannelInputSink.
                    // The From-variant lets the sink learn WHO feeds it — the
                    // touch-screen routes taps back to this block through it.
                    is ChannelInputSink ->
                        resolved.writeChannelInputFrom(binding.targetChannelName, value, pos)
                    else -> {}
                }
            }
        }

        // Publish this tick's channel_output values for PinLink consumers —
        // other sinks' PinLinkEngine pulls read this snapshot via readPin.
        pinReadSnapshot = perChannelValueCache
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
            // Phase 2c — cancel this BE's per-node CLIENT script scopes on unload
            // so an unloaded chunk's client behaviors stop running (spec §6.3).
            for (tag in clientReplicatedStateTags()) {
                dev.nitka.nodewire.client.script.ClientScriptNodeRuntime.cancelForState(tag)
            }
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
        if (pinLinks.isNotEmpty()) {
            tag.put(
                PIN_LINKS_KEY,
                dev.nitka.nodewire.link.PinLink.CODEC.listOf()
                    .encodeStart(NbtOps.INSTANCE, pinLinks.toList()).result()
                    .orElseThrow { IllegalStateException("pin_links encode failed") },
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
        pinLinks.clear()
        if (tag.contains(PIN_LINKS_KEY)) {
            val list = dev.nitka.nodewire.link.PinLink.CODEC.listOf()
                .parse(NbtOps.INSTANCE, tag.get(PIN_LINKS_KEY)).result()
                .orElse(emptyList())
            pinLinks.addAll(list)
        }
        // One-way migration of the pre-unification binding lists (camera /
        // remote-redstone / screen-touch, each `{ch, source}`) into PinLinks.
        // The legacy keys are read once and never written back. A touch
        // binding becomes TWO links (the old path auto-fed `<ch>_down`); the
        // engine prunes the `_down` one if no such channel_input exists.
        migrateLegacyLinks(tag, "camera_source_bindings", "video") { _ -> }
        migrateLegacyLinks(tag, "remote_redstone_bindings", dev.nitka.nodewire.link.PinPorts.REDSTONE_PIN) { _ -> }
        migrateLegacyLinks(tag, "screen_touch_bindings", "touch") { (src, ch) ->
            pinLinks.add(dev.nitka.nodewire.link.PinLink(src, "touch_down", ch + "_down"))
        }
        // Phase 2b.3b: CLIENT-only — consume the late-joiner replicated-state
        // piggyback from getUpdateTag, staging current values BEFORE any client
        // runtime (2c) reads, so a fresh client starts from current state not init.
        // Server reads never see this key (it's only written by getUpdateTag).
        if (level?.isClientSide == true && tag.contains(REPLICATED_STATE_KEY)) {
            val repl = tag.getCompound(REPLICATED_STATE_KEY)
            for (key in repl.allKeys) {
                val nodeId = runCatching { java.util.UUID.fromString(key) }.getOrNull() ?: continue
                if (nodeId !in graph.nodes) continue
                clientReplicatedState.getOrPut(nodeId) { CompoundTag() }.merge(repl.getCompound(key))
            }
        }
        invalidateEvaluator()
    }

    override fun getUpdateTag(registries: net.minecraft.core.HolderLookup.Provider): CompoundTag {
        val tag = saveWithoutMetadata(registries)
        // Phase 2b.3b: piggyback CURRENT replicated values so a client that loads
        // the chunk AFTER the last change starts from current state, not `init`.
        // The per-node `state` tag is the server evaluator's private scratch (NOT
        // in NodeGraph.CODEC), so without this a late joiner is stuck at init.
        // Ship ONLY replicated keys (server-only cells never leak). Keep it tiny.
        val ev = serverEvaluator
        if (ev != null) {
            val repl = CompoundTag()
            for ((id, node) in graph.nodes) {
                if (node.typeKey.path != "script") continue
                val nodeState = ev.nodeState(id) ?: continue
                val keys = dev.nitka.nodewire.script.ScriptNodeRuntime
                    .replicatedKeys(node.config.getString("src"))
                if (keys.isEmpty()) continue
                val sub = dev.nitka.nodewire.script.ScriptModuleReplication
                    .buildReplicatedSubTag(nodeState, keys)
                if (!sub.isEmpty) repl.put(id.toString(), sub)
            }
            if (!repl.isEmpty) tag.put(REPLICATED_STATE_KEY, repl)
        }
        return tag
    }

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

    /**
     * Read one pre-unification binding list (`[{ch, source}]`) from [tag]
     * and append a [dev.nitka.nodewire.link.PinLink] per entry, with the
     * given fixed [sourcePin]. [extra] runs per entry for companions (the
     * touch `_down` link). Legacy keys are never written back — saving once
     * completes the migration.
     */
    private fun migrateLegacyLinks(
        tag: CompoundTag,
        key: String,
        sourcePin: String,
        extra: (Pair<EndpointRef, String>) -> Unit,
    ) {
        if (!tag.contains(key)) return
        val listTag = tag.getList(key, net.minecraft.nbt.Tag.TAG_COMPOUND.toInt())
        for (i in 0 until listTag.size) {
            val e = listTag.getCompound(i)
            val ch = e.getString("ch")
            if (ch.isEmpty()) continue
            val src = EndpointRef.CODEC
                .parse(NbtOps.INSTANCE, e.getCompound("source"))
                .result().orElse(null) ?: continue
            pinLinks.add(dev.nitka.nodewire.link.PinLink(src, sourcePin, ch))
            extra(src to ch)
        }
    }

    private fun isStale(binding: ChannelBinding, level: Level): Boolean {
        val srcNode = graph.nodes.values.firstOrNull {
            it.typeKey.path == "channel_output"
                && it.config.getString("name") == binding.sourceChannelName
        } ?: return true
        val resolved = binding.target.resolve(level)
        // Graphless endpoint consumer (e.g. the video Screen): it has no
        // channel_input node to match against — the binding is live as long as
        // the source channel_output exists and the target BE is present.
        if (resolved !is LogicBlockEntity) {
            return resolved !is ChannelInputSink
        }
        val target = resolved
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

    /** [ChannelInputSink] — delegates to the existing channel-input writer. */
    override fun writeChannelInput(name: String, value: dev.nitka.nodewire.graph.PinValue) {
        nwWriteChannelInput(name, value)
    }

    companion object {
        private const val MAX_NAME_LENGTH = 64

        /** Unified incoming pin links (PinLink list). */
        private const val PIN_LINKS_KEY = "pin_links"

        /** getUpdateTag sub-tag (node-id -> replicated cell values) for late joiners (2b.3b). */
        private const val REPLICATED_STATE_KEY = "nw_replicated_state"
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
