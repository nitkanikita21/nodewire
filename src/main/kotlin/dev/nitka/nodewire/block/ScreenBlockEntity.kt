package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * BlockEntity for [ScreenBlock]. Owns no graph: it is a pure consumer that
 * receives a `PinValue.Video(handle)` through the channel pipeline (via
 * [ChannelInputSink]) and exposes the bare UUID to the client BER, which blits
 * that handle's `VideoManager` surface on the [ScreenBlock.FACING] face.
 *
 * Refcount discipline (the endpoints-only rule): only this consumer (and, later,
 * the Camera producer) acquire/release in `VideoManager` — a LogicBlock merely
 * routing a VIDEO handle through a channel does not. On the **client**:
 *   - `onLoad`        -> `acquire(currentHandle)` if non-nil.
 *   - handle change   -> `release(old); acquire(new)` so the refcount tracks the
 *                        live handle across a channel re-bind.
 *   - `setRemoved`    -> `release(currentHandle)` if non-nil.
 * Guarded by `level?.isClientSide == true`, mirroring [LogicBlockEntity]'s
 * `onLoad`/`setRemoved` seams.
 */
class ScreenBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.SCREEN_BLOCK_BE.get(), pos, state),
    ChannelInputSink,
    dev.nitka.nodewire.link.PinLinkSink {

    // ── unified pin links ─────────────────────────────────────────────────
    // The Screen is BOTH ends of the unified link surface:
    //  * input  `screen` (VIDEO)            — what the panel displays;
    //  * output `touch` (VEC2, sticky)      — last tap in panel-surface px;
    //  * output `touch_down` (BOOL, pulse)  — fires on the tick of a new tap.
    // Covered panel cells delegate reads/writes to their anchor, so linking
    // ANY cell behaves as linking the panel.

    private val pinLinks: MutableList<dev.nitka.nodewire.link.PinLink> = mutableListOf()

    override val pinLinkScratch = dev.nitka.nodewire.link.PinLinkScratch()

    override fun pinLinks(): MutableList<dev.nitka.nodewire.link.PinLink> = pinLinks

    fun pinLinksSnapshot(): List<dev.nitka.nodewire.link.PinLink> = pinLinks.toList()

    override fun onPinLinksChanged() {
        pushSync()
    }

    override fun pinInputs(ctx: dev.nitka.nodewire.link.LinkContext): List<dev.nitka.nodewire.link.LinkPin> =
        listOf(dev.nitka.nodewire.link.LinkPin(SCREEN_CHANNEL, PinType.VIDEO))

    override fun pinOutputs(ctx: dev.nitka.nodewire.link.LinkContext): List<dev.nitka.nodewire.link.LinkPin> =
        listOf(
            dev.nitka.nodewire.link.LinkPin(TOUCH_PIN, PinType.VEC2),
            dev.nitka.nodewire.link.LinkPin(TOUCH_DOWN_PIN, PinType.BOOL),
        )

    override fun readPin(id: String): dev.nitka.nodewire.link.PinReading? {
        val anchor = anchorOrSelf()
        val tap = anchor.latestTap() ?: return null
        return when (id) {
            TOUCH_PIN -> dev.nitka.nodewire.link.PinReading(
                PinValue.Vec2(tap.first[0].toFloat(), tap.first[1].toFloat()),
            )
            TOUCH_DOWN_PIN -> dev.nitka.nodewire.link.PinReading(
                PinValue.Bool(true),
                pulseStamp = tap.second,
            )
            else -> null
        }
    }

    override fun writePin(id: String, value: PinValue) = writeChannelInput(id, value)

    override fun clearPin(id: String) {
        if (id == SCREEN_CHANNEL) writeChannelInput(SCREEN_CHANNEL, PinValue.Video(NIL_HANDLE))
    }

    /** The panel anchor this cell belongs to (self when uncovered). */
    private fun anchorOrSelf(): ScreenBlockEntity =
        coveredBy?.let { level?.getBlockEntity(it) as? ScreenBlockEntity }
            ?.takeIf { it.coveredBy() == null }
            ?: this

    /**
     * Runtime channel-input slot (purely transient, mirrors
     * [LogicBlockEntity.externalChannelInputs] — re-populated by the source's
     * next tick). Only the `"screen"` slot carries a VIDEO handle.
     */
    private val channelInputs: MutableMap<String, PinValue> = mutableMapOf()

    /**
     * Pure refcount reconciler — `release(old); acquire(new)` on change. Wired to
     * the client [VideoManager] via [videoManager]. Driven only on the client.
     */
    private val tracker = ScreenHandleTracker(object : ScreenHandleTracker.Refcounter {
        // Client-only (gated by isClientSide at every call site) — touches the
        // client VideoManager class lazily, so the server path never loads it.
        override fun acquire(handle: UUID) =
            dev.nitka.nodewire.client.video.VideoManager.acquire(handle)

        override fun release(handle: UUID) =
            dev.nitka.nodewire.client.video.VideoManager.release(handle)
    })

    /**
     * The bare VIDEO handle delivered on the `"screen"` channel, or `null` if
     * none / the nil handle. The BER reads this each frame. Never carries a
     * frame — only the UUID crosses the channel (the net invariant).
     */
    fun videoHandle(): UUID? = decodeHandle(channelInputs[SCREEN_CHANNEL])

    // ── multiblock panel (span) ───────────────────────────────────────────
    // The ANCHOR (bottom-left block looking AT the face) carries spanCols ×
    // spanRows; every other block of the panel carries coveredBy=anchorPos and
    // renders nothing + forwards channel deliveries to the anchor. Resized via
    // the Channel Link Tool's sneak+RMB two-corner flow (see ScreenSpan).

    private var spanCols: Int = 1
    private var spanRows: Int = 1
    private var coveredBy: BlockPos? = null

    fun spanCols(): Int = spanCols
    fun spanRows(): Int = spanRows
    fun coveredBy(): BlockPos? = coveredBy

    /** Make this BE the panel anchor spanning [cols]×[rows]. Server-side. */
    fun setSpan(cols: Int, rows: Int) {
        spanCols = cols.coerceIn(1, ScreenSpan.MAX)
        spanRows = rows.coerceIn(1, ScreenSpan.MAX)
        coveredBy = null
        pushSync()
    }

    /** Mark this BE covered by the panel anchored at [anchor] (null = free). */
    fun setCoveredBy(anchor: BlockPos?) {
        coveredBy = anchor
        if (anchor != null) {
            spanCols = 1
            spanRows = 1
        }
        pushSync()
    }

    /**
     * CLIENT: true while covered by a still-valid anchor. Lazily self-heals —
     * a stale pointer (anchor broken / no longer an anchor) clears itself so
     * the block resumes rendering its own 1×1 face.
     */
    fun coveredByValidAnchor(): Boolean {
        val cb = coveredBy ?: return false
        val anchor = level?.getBlockEntity(cb) as? ScreenBlockEntity
        if (anchor == null || anchor.coveredBy != null) {
            coveredBy = null
            return false
        }
        return true
    }

    /**
     * SERVER (anchor, right after a panel commit): adopt the panel cells'
     * existing screen state so the merged panel behaves as ONE screen —
     * a video bound to any cell BEFORE the merge (incl. one-shot camera
     * binds, which never re-deliver) keeps playing on the whole panel.
     * The anchor's own handle wins; else the first non-nil cell handle.
     */
    fun consolidatePanel(lvl: Level, cells: List<BlockPos>) {
        if (videoHandle() == null) {
            for (cell in cells) {
                if (cell == blockPos) continue
                val cellBe = lvl.getBlockEntity(cell) as? ScreenBlockEntity ?: continue
                val h = cellBe.videoHandle() ?: continue
                if (videoSourcePos == null) videoSourcePos = cellBe.videoSourcePos
                writeChannelInput(SCREEN_CHANNEL, PinValue.Video(h))
                break
            }
        }
        if (videoSourcePos == null) {
            videoSourcePos = cells.asSequence()
                .mapNotNull { (lvl.getBlockEntity(it) as? ScreenBlockEntity)?.videoSourcePos }
                .firstOrNull()
        }
    }

    /**
     * SERVER (anchor only, on block removal): free every covered block of this
     * panel so the survivors resume their own 1×1 rendering.
     */
    fun releaseSpan(lvl: Level) {
        if (coveredBy != null || (spanCols == 1 && spanRows == 1)) return
        val facing = blockState.getValue(ScreenBlock.FACING)
        for (cell in ScreenSpan.cells(facing, ScreenSpan.Rect(blockPos, spanCols, spanRows))) {
            if (cell == blockPos) continue
            (lvl.getBlockEntity(cell) as? ScreenBlockEntity)
                ?.takeIf { it.coveredBy() == blockPos }
                ?.setCoveredBy(null)
        }
    }

    private fun pushSync() {
        setChanged()
        val lvl = level ?: return
        if (!lvl.isClientSide) {
            lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
        }
    }

    /** The LogicBlock that delivered the current video (server, transient) —
     *  re-learned every delivery tick; the touch back-path routes taps here. */
    private var videoSourcePos: BlockPos? = null

    override fun writeChannelInputFrom(name: String, value: PinValue, sourcePos: BlockPos) {
        val cb = coveredBy
        if (cb != null) {
            val anchor = level?.getBlockEntity(cb) as? ScreenBlockEntity
            if (anchor != null && anchor.coveredBy() == null) {
                anchor.writeChannelInputFrom(name, value, sourcePos)
                return
            }
        }
        if (name == SCREEN_CHANNEL) videoSourcePos = sourcePos
        writeChannelInput(name, value)
    }

    // ── touch source (per-anchor, transient) ─────────────────────────────
    // The latest tap on this PANEL in surface px + its gameTime. Exposed as
    // the `touch`/`touch_down` output pins — sinks holding a PinLink on this
    // screen pull it; the screen never pushes anywhere itself.

    private var lastTapPx: IntArray? = null
    private var lastTapTime: Long = -1L

    /** The newest tap as `(px, gameTime)`, or null if never tapped. */
    fun latestTap(): Pair<IntArray, Long>? = lastTapPx?.let { it to lastTapTime }

    /**
     * Player tap on this panel (any cell — covered cells route through their
     * anchor). Converts the face hit into PANEL-surface px (the same pixel
     * space the producing script draws in) and records it on the anchor for
     * bound LogicBlocks to pull. Server-side. Returns true when consumed.
     */
    fun handleTouch(clicked: BlockPos, hitX: Double, hitY: Double, hitZ: Double): Boolean =
        handleTouchDiag(clicked, hitX, hitY, hitZ) {} != null

    /** [handleTouch] with a rejection-reason callback (touch diagnostics).
     *  Returns the surface px on success, null on rejection. */
    fun handleTouchDiag(
        clicked: BlockPos,
        hitX: Double,
        hitY: Double,
        hitZ: Double,
        reject: (String) -> Unit,
    ): IntArray? {
        val lvl = level ?: return null
        // Resolve the panel anchor (self, or through coveredBy).
        val anchor: ScreenBlockEntity = coveredBy?.let { lvl.getBlockEntity(it) as? ScreenBlockEntity } ?: this
        if (anchor.coveredBy() != null) {
            reject("panel anchor is itself covered (stale panel — re-drag the corners)")
            return null
        }
        val facing = anchor.blockState.getValue(ScreenBlock.FACING)
        val px = ScreenSpan.touchPx(
            facing,
            anchor.blockPos,
            anchor.spanCols(),
            anchor.spanRows(),
            clicked,
            hitX,
            hitY,
            hitZ,
        )
        if (px == null) {
            reject("hit outside the panel grid (anchor=${anchor.blockPos.toShortString()}, span=${anchor.spanCols()}×${anchor.spanRows()})")
            return null
        }
        anchor.lastTapPx = px
        anchor.lastTapTime = lvl.gameTime
        return px
    }

    /**
     * [ChannelInputSink] entry point. Cross-block delivery writes the VIDEO
     * handle here. On the client, retarget the [VideoManager] refcount when the
     * handle changes. A covered panel block forwards to its anchor (one hop),
     * so binding ANY block of a panel feeds the whole panel.
     */
    override fun writeChannelInput(name: String, value: PinValue) {
        val cb = coveredBy
        if (cb != null) {
            val anchor = level?.getBlockEntity(cb) as? ScreenBlockEntity
            if (anchor != null && anchor.coveredBy() == null) {
                anchor.writeChannelInput(name, value)
                return
            }
        }
        val changed = channelInputs[name] != value
        channelInputs[name] = value
        setChanged()
        if (name != SCREEN_CHANNEL) return
        val lvl = level
        if (lvl != null && !lvl.isClientSide) {
            // The handle the BER reads lives on the CLIENT, but delivery runs on
            // the SERVER — push a BE update so the client Screen learns the handle.
            if (changed) lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
        } else {
            retargetClientRefcount()
        }
    }

    // ── client sync: the delivered VIDEO handle must reach the client BER ──
    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        videoHandle()?.let { tag.putUUID(SCREEN_CHANNEL, it) }
        if (spanCols != 1 || spanRows != 1) {
            tag.putInt(TAG_SPAN_COLS, spanCols)
            tag.putInt(TAG_SPAN_ROWS, spanRows)
        }
        coveredBy?.let { tag.putLong(TAG_COVERED_BY, it.asLong()) }
        if (pinLinks.isNotEmpty()) {
            dev.nitka.nodewire.link.PinLink.CODEC.listOf()
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, pinLinks.toList())
                .result().ifPresent { tag.put(TAG_PIN_LINKS, it) }
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.hasUUID(SCREEN_CHANNEL)) {
            channelInputs[SCREEN_CHANNEL] = PinValue.Video(tag.getUUID(SCREEN_CHANNEL))
        } else {
            channelInputs.remove(SCREEN_CHANNEL)
        }
        spanCols = if (tag.contains(TAG_SPAN_COLS)) tag.getInt(TAG_SPAN_COLS).coerceIn(1, ScreenSpan.MAX) else 1
        spanRows = if (tag.contains(TAG_SPAN_ROWS)) tag.getInt(TAG_SPAN_ROWS).coerceIn(1, ScreenSpan.MAX) else 1
        coveredBy = if (tag.contains(TAG_COVERED_BY)) BlockPos.of(tag.getLong(TAG_COVERED_BY)) else null
        pinLinks.clear()
        if (tag.contains(TAG_PIN_LINKS)) {
            dev.nitka.nodewire.link.PinLink.CODEC.listOf()
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag.get(TAG_PIN_LINKS))
                .result().ifPresent { pinLinks.addAll(it) }
        }
        if (level?.isClientSide == true) retargetClientRefcount()
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().also { saveAdditional(it, registries) }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun onLoad() {
        super.onLoad()
        if (level?.isClientSide == true) {
            retargetClientRefcount()
        }
    }

    override fun setRemoved() {
        if (level?.isClientSide == true) {
            tracker.onUnload()
        }
        super.setRemoved()
    }

    /** Reconcile the client refcount to the currently-delivered handle. */
    private fun retargetClientRefcount() {
        tracker.onHandle(videoHandle())
        // Anchor panels drive the (client-local) surface ASPECT: ask for dims
        // proportional to cols×rows so producers draw at the panel's aspect
        // instead of being stretched from a square. Last-loaded panel wins
        // when one handle feeds panels of different sizes.
        val h = videoHandle() ?: return
        if (coveredBy == null) {
            val d = dev.nitka.nodewire.client.video.VideoManager.sizeForSpan(spanCols, spanRows)
            dev.nitka.nodewire.client.video.VideoManager.requestSurfaceSize(h, d[0], d[1])
        }
    }

    companion object {
        /** The single named channel slot a Screen accepts. */
        const val SCREEN_CHANNEL = "screen"

        /** Output pins: panel-surface tap position + its 1-tick pulse. */
        const val TOUCH_PIN = "touch"
        const val TOUCH_DOWN_PIN = "touch_down"

        private const val TAG_SPAN_COLS = "span_cols"
        private const val TAG_SPAN_ROWS = "span_rows"
        private const val TAG_COVERED_BY = "covered_by"
        private const val TAG_PIN_LINKS = "pin_links"

        /** Default/empty VIDEO sentinel (see `PinValue.default(VIDEO)`). */
        val NIL_HANDLE: UUID = UUID(0L, 0L)

        /**
         * Pure decode of a delivered channel value to the live handle, or `null`
         * for a non-VIDEO / nil value. Headless-testable (no BE instance); this is
         * exactly what [videoHandle] returns and what the net invariant guards —
         * a `PinValue.Video` carries only the UUID, never a frame.
         */
        fun decodeHandle(value: PinValue?): UUID? =
            (value as? PinValue.Video)?.handle?.takeIf { it != NIL_HANDLE }
    }
}
