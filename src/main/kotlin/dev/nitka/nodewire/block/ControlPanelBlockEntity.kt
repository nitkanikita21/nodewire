package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.block.panel.ElementPin
import dev.nitka.nodewire.block.panel.PanelElementStore
import dev.nitka.nodewire.block.panel.PanelElements
import dev.nitka.nodewire.block.panel.PanelGrid
import dev.nitka.nodewire.block.panel.PanelPinDir
import dev.nitka.nodewire.block.panel.PanelPins
import dev.nitka.nodewire.block.panel.PlacedElement
import dev.nitka.nodewire.graph.PinValue
import dev.nitka.nodewire.link.LinkContext
import dev.nitka.nodewire.link.LinkPin
import dev.nitka.nodewire.link.PinLink
import dev.nitka.nodewire.link.PinLinkScratch
import dev.nitka.nodewire.link.PinLinkSink
import dev.nitka.nodewire.link.PinReading
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * BlockEntity for [ControlPanelBlock]. Holds the panel's [PlacedElement]s on a
 * 16×16 grid (via [PanelElementStore]) and exposes each interactive/indicator
 * element as one named pin on the unified [PinLinkSink] surface:
 *
 *  * OUTPUT elements (toggle/momentary/selector/slider/knob) → [readPin] samples
 *    their operated value (momentary carries a 1-tick `pulseStamp`).
 *  * INPUT elements (lamp/bar/numeric/screen) → [writePin] drives their display
 *    value; [clearPin] resets it.
 *
 * Pin ids are the element's cell-anchor id (`"type@x,y"`, see [PlacedElement.pinId]),
 * so a link survives as long as the element exists. Placement / removal / operate
 * are committed by [ControlPanelBlock] and the panel packets.
 */
class ControlPanelBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.CONTROL_PANEL_BE.get(), pos, state),
    PinLinkSink {

    private val store = PanelElementStore()
    private val pinLinks: MutableList<PinLink> = mutableListOf()
    override val pinLinkScratch = PinLinkScratch()

    /** Transient per-momentary-pin "fired at gameTime" stamps (operate flow). */
    private val pulseStamps: MutableMap<String, Long> = mutableMapOf()

    /** Mini-screen taps: element base pin id → last tap (0..1 within the
     *  element window + gameTime). Surfaces via `:touch` / `:touch_down`. */
    private data class Tap(val u: Double, val v: Double, val time: Long)
    private val taps: MutableMap<String, Tap> = mutableMapOf()

    // ── mini-screen video (per "screen" element) ──────────────────────────
    // Only the bare UUID handle crosses the wire (the net invariant); the BER
    // blits the handle's client-local VideoManager surface into the element
    // rect. Client refcounts via one ScreenHandleTracker per element pin.
    private val videoHandles: MutableMap<String, java.util.UUID> = mutableMapOf()
    private val videoTrackers: MutableMap<String, ScreenHandleTracker> = mutableMapOf()

    /** CLIENT (BER): the live video handle for a screen element's pin, or null. */
    fun videoHandle(pinId: String): java.util.UUID? = videoHandles[pinId]

    // ── dynamic raycast shape (plate + raised element boxes) ──────────────
    // Rebuilt lazily after any element mutation; lets the vanilla raycast (and
    // therefore the selection outline, operate, place, bind) target individual
    // element bodies from any approach angle.
    @Volatile
    private var shapeCache: net.minecraft.world.phys.shapes.VoxelShape? = null

    fun blockShape(): net.minecraft.world.phys.shapes.VoxelShape {
        shapeCache?.let { return it }
        val face = blockState.getValue(ControlPanelBlock.FACE)
        val spin = blockState.getValue(ControlPanelBlock.SPIN)
        var shape = ControlPanelBlock.plateShape(face)
        for (e in store.all()) {
            shape = net.minecraft.world.phys.shapes.Shapes.or(
                shape,
                net.minecraft.world.phys.shapes.Shapes.create(
                    dev.nitka.nodewire.block.panel.PanelSpace.elementBox(e, face, spin),
                ),
            )
        }
        shapeCache = shape
        return shape
    }

    private fun writeVideoHandle(pinId: String, handle: java.util.UUID?) {
        val changed = if (handle == null) videoHandles.remove(pinId) != null
        else videoHandles.put(pinId, handle) != handle
        if (!changed) return
        setChanged()
        val lvl = level
        if (lvl != null && !lvl.isClientSide) {
            lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
        } else {
            retargetClientRefcounts()
        }
    }

    /** CLIENT: reconcile every element tracker to the current handle map. */
    private fun retargetClientRefcounts() {
        // Retarget live pins, then release trackers whose pin vanished.
        for ((pin, handle) in videoHandles) trackerFor(pin).onHandle(handle)
        val stale = videoTrackers.keys - videoHandles.keys
        for (pin in stale) videoTrackers.remove(pin)?.onUnload()
    }

    private fun trackerFor(pin: String): ScreenHandleTracker = videoTrackers.getOrPut(pin) {
        ScreenHandleTracker(object : ScreenHandleTracker.Refcounter {
            override fun acquire(handle: java.util.UUID) =
                dev.nitka.nodewire.client.video.VideoManager.acquire(handle)

            override fun release(handle: java.util.UUID) =
                dev.nitka.nodewire.client.video.VideoManager.release(handle)
        })
    }

    // ── element editing (server) ──────────────────────────────────────────
    fun elements(): List<PlacedElement> = store.all()

    fun occupiedCells(): Set<PanelGrid.Cell> = store.occupied()

    fun elementAt(cell: PanelGrid.Cell): PlacedElement? = store.elementAt(cell)

    /** Add [e] if it fits + overlaps nothing; sync on success. */
    fun addElement(e: PlacedElement): Boolean {
        val ok = store.add(e)
        if (ok) pushSync()
        return ok
    }

    /** Remove the element covering [cell]; drop its stale pulse stamp, taps,
     *  video handle and any links landing on ANY of its pins, then sync. */
    fun removeElementAt(cell: PanelGrid.Cell): PlacedElement? {
        val removed = store.removeAt(cell) ?: return null
        val base = removed.pinId()
        pulseStamps.remove(base)
        taps.remove(base)
        videoHandles.remove(base)
        pinLinks.removeAll { PanelPins.baseId(it.targetPin) == base }
        pushSync()
        return removed
    }

    /** Set an element's runtime value (operate / indicator drive). [pulse] stamps
     *  a momentary's 1-tick event. Syncs only when the stored value changes. */
    fun setElementValue(cell: PanelGrid.Cell, value: Double, pulse: Long? = null) {
        val e = store.elementAt(cell)
        if (pulse != null && e != null) pulseStamps[e.pinId()] = pulse
        if (store.setValue(cell, value)) pushSync()
    }

    fun setElementConfig(cell: PanelGrid.Cell, config: CompoundTag) {
        if (store.setConfig(cell, config)) pushSync()
    }

    /**
     * Apply a player operate (RMB) to the interactive element covering [cell].
     * [uFrac]/[vFrac] are the sub-cell hit fraction (slider/knob analog input);
     * [sneak] reverses a selector. Returns true if an interactive element was hit.
     */
    fun handleOperate(cell: PanelGrid.Cell, uFrac: Double, vFrac: Double, sneak: Boolean, gameTime: Long): Boolean {
        val e = store.elementAt(cell) ?: return false
        val type = PanelElements.byId(e.typeId) ?: return false
        if (!type.interactive) return false
        val anchor = PanelGrid.Cell(e.cellX, e.cellY)
        val cfg = e.config
        when (e.typeId) {
            "toggle" -> setElementValue(anchor, if (e.value != 0.0) 0.0 else 1.0)
            "momentary" -> setElementValue(anchor, 1.0, pulse = gameTime)
            "selector" -> {
                val positions = if (cfg.contains("positions")) cfg.getInt("positions").coerceAtLeast(1) else 2
                setElementValue(anchor, PanelGrid.selectorNext(e.value.toInt(), positions, sneak).toDouble())
            }
            "slider" -> {
                val min = if (cfg.contains("min")) cfg.getDouble("min") else 0.0
                val max = if (cfg.contains("max")) cfg.getDouble("max") else 1.0
                val step = if (cfg.contains("step")) cfg.getDouble("step") else 0.0
                val horizontal = e.cols >= e.rows
                val frac = if (horizontal) (cell.x - anchor.x + uFrac) / e.cols else (cell.y - anchor.y + vFrac) / e.rows
                setElementValue(anchor, PanelGrid.sliderValue(frac, min, max, step))
            }
            "knob" -> {
                val min = if (cfg.contains("min")) cfg.getDouble("min") else 0.0
                val max = if (cfg.contains("max")) cfg.getDouble("max") else 1.0
                val step = if (cfg.contains("step")) cfg.getDouble("step") else 0.0
                val sweep = if (cfg.contains("sweep")) cfg.getDouble("sweep") else 270.0
                val hx = (cell.x - anchor.x) + uFrac
                val hy = (cell.y - anchor.y) + vFrac
                setElementValue(anchor, PanelGrid.knobValue(hx - e.cols / 2.0, hy - e.rows / 2.0, min, max, sweep, step))
            }
            else -> {
                // Mini touch-screens (any size): record the tap as a 0..1
                // fraction within the element window; surfaces via `:touch` /
                // `:touch_down`. A powered-down screen (enable=0, the default)
                // ignores taps.
                if (!PanelElements.isScreen(e.typeId)) return false
                if (e.value == 0.0) return false
                val tu = ((cell.x - anchor.x) + uFrac) / e.cols
                val tv = ((cell.y - anchor.y) + vFrac) / e.rows
                taps[e.pinId()] = Tap(tu, tv, gameTime)
            }
        }
        return true
    }

    // ── unified pin links ─────────────────────────────────────────────────
    override fun pinLinks(): MutableList<PinLink> = pinLinks

    override fun onPinLinksChanged() = pushSync()

    override fun pinOutputs(ctx: LinkContext): List<LinkPin> = PanelPins.outputs(store.all())

    override fun pinInputs(ctx: LinkContext): List<LinkPin> = PanelPins.inputs(store.all())

    /** Resolve an incoming wire id (`"type@x,y"` / `"type@x,y:name"`) to the
     *  element + the catalog pin spec it addresses. */
    private fun pinAt(id: String): Pair<PlacedElement, ElementPin>? {
        val base = PanelPins.baseId(id)
        val name = PanelPins.pinName(id)
        val e = store.all().firstOrNull { it.pinId() == base } ?: return null
        val spec = PanelElements.byId(e.typeId)?.pins?.firstOrNull { it.name == name } ?: return null
        return e to spec
    }

    override fun readPin(id: String): PinReading? {
        val (e, spec) = pinAt(id) ?: return null
        if (spec.dir != PanelPinDir.OUTPUT) return null
        return when (spec.name) {
            "" -> when (e.typeId) {
                "toggle" -> PinReading(PinValue.Bool(e.value != 0.0))
                "momentary" -> PinReading(PinValue.Bool(e.value != 0.0), pulseStamp = pulseStamps[id] ?: -1L)
                "selector" -> PinReading(PinValue.Int(e.value.toInt()))
                "slider", "knob" -> PinReading(PinValue.Float(e.value.toFloat()))
                else -> null
            }
            "touch" -> taps[e.pinId()]?.let { PinReading(PinValue.Vec2(it.u, it.v)) }
            "touch_down" -> taps[e.pinId()]?.let { PinReading(PinValue.Bool(true), pulseStamp = it.time) }
            else -> null
        }
    }

    override fun writePin(id: String, value: PinValue) {
        val (e, spec) = pinAt(id) ?: return
        if (spec.dir != PanelPinDir.INPUT) return
        when (spec.name) {
            "" -> {
                if (PanelElements.isScreen(e.typeId)) {
                    if (value is PinValue.Video) writeVideoHandle(e.pinId(), ScreenBlockEntity.decodeHandle(value))
                    return
                }
                numeric(value)?.let { setElementValue(PanelGrid.Cell(e.cellX, e.cellY), it) }
            }
            // Remote drive: the graph sets the control's state; the visual and
            // the primary output follow (same slot as a player click).
            "set" -> numeric(value)?.let { setElementValue(PanelGrid.Cell(e.cellX, e.cellY), it) }
            // Screen power: e.value doubles as the enabled flag (fresh elements
            // are 0 → OFF by default).
            "enable" -> numeric(value)?.let {
                setElementValue(PanelGrid.Cell(e.cellX, e.cellY), if (it != 0.0) 1.0 else 0.0)
            }
        }
    }

    override fun clearPin(id: String) {
        val (e, spec) = pinAt(id) ?: return
        if (spec.dir != PanelPinDir.INPUT) return
        when (spec.name) {
            "" -> {
                if (PanelElements.isScreen(e.typeId)) writeVideoHandle(e.pinId(), null)
                else setElementValue(PanelGrid.Cell(e.cellX, e.cellY), 0.0)
            }
            // A silenced `set` keeps the last state — the control latches, the
            // player can still operate it by hand.
            "set" -> {}
            // A silenced `enable` powers the screen DOWN (screens default off).
            "enable" -> setElementValue(PanelGrid.Cell(e.cellX, e.cellY), 0.0)
        }
    }

    private fun numeric(value: PinValue): Double? = when (value) {
        is PinValue.Bool -> if (value.value) 1.0 else 0.0
        is PinValue.Int -> value.value.toDouble()
        is PinValue.Float -> value.value.toDouble()
        is PinValue.Redstone -> value.value.toDouble()
        else -> null
    }

    // ── persistence + client sync ─────────────────────────────────────────
    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        if (!store.isEmpty()) {
            PanelElementStore.LIST_CODEC.encodeStart(NbtOps.INSTANCE, store.all())
                .result().ifPresent { tag.put(TAG_ELEMENTS, it) }
        }
        if (pinLinks.isNotEmpty()) {
            PinLink.CODEC.listOf().encodeStart(NbtOps.INSTANCE, pinLinks.toList())
                .result().ifPresent { tag.put(TAG_PIN_LINKS, it) }
        }
        if (videoHandles.isNotEmpty()) {
            val v = CompoundTag()
            for ((pin, handle) in videoHandles) v.putUUID(pin, handle)
            tag.put(TAG_VIDEO, v)
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        store.clear()
        if (tag.contains(TAG_ELEMENTS)) {
            PanelElementStore.LIST_CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_ELEMENTS))
                .result().ifPresent { store.load(it) }
        }
        pinLinks.clear()
        if (tag.contains(TAG_PIN_LINKS)) {
            PinLink.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get(TAG_PIN_LINKS))
                .result().ifPresent { pinLinks.addAll(it) }
        }
        videoHandles.clear()
        if (tag.contains(TAG_VIDEO)) {
            val v = tag.getCompound(TAG_VIDEO)
            for (key in v.allKeys) {
                if (v.hasUUID(key)) videoHandles[key] = v.getUUID(key)
            }
        }
        shapeCache = null
        if (level?.isClientSide == true) retargetClientRefcounts()
    }

    override fun onLoad() {
        super.onLoad()
        if (level?.isClientSide == true) retargetClientRefcounts()
    }

    override fun setRemoved() {
        if (level?.isClientSide == true) {
            for (t in videoTrackers.values) t.onUnload()
            videoTrackers.clear()
        }
        super.setRemoved()
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().also { saveAdditional(it, registries) }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    private fun pushSync() {
        shapeCache = null
        setChanged()
        val lvl = level ?: return
        if (!lvl.isClientSide) {
            lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
        }
    }

    companion object {
        private const val TAG_ELEMENTS = "elements"
        private const val TAG_PIN_LINKS = "pin_links"
        private const val TAG_VIDEO = "video_handles"
    }
}
