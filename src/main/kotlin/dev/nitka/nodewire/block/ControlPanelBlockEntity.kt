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

    /** Momentary buttons: pin id → gameTime when the press auto-releases. */
    private val pressedUntil: MutableMap<String, Long> = mutableMapOf()

    /** Joystick deflections (pin id → [x, y] in -1..1) + spring-back deadline.
     *  Synced to the client (drives the stick tilt render). */
    private val joyStates: MutableMap<String, FloatArray> = mutableMapOf()
    private val joyExpiry: MutableMap<String, Long> = mutableMapOf()

    /** Joystick trigger taps: pin id → gameTime the trigger window closes. */
    private val triggerUntil: MutableMap<String, Long> = mutableMapOf()

    /** CLIENT (BER): current joystick deflection for an element, or null. */
    fun joyState(pinId: String): FloatArray? = joyStates[pinId]

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
        val e = store.elementAt(cell)
        val changed = if (e?.typeId == "push_button") {
            // Footprint follows the configured button count/gap (validated in
            // the store; on a grid conflict only the config applies).
            val buttons = config.getInt("buttons").coerceAtLeast(1)
            val gap = config.getInt("gap").coerceAtLeast(0)
            val (cols, rows) = PanelElements.pushButtonFootprint(buttons, gap)
            store.setConfigAndSize(cell, config, cols, rows)
        } else {
            store.setConfig(cell, config)
        }
        if (changed) {
            shapeCache = null
            pushSync()
        }
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
        val lvl = level
        val xCells = (cell.x - anchor.x) + uFrac // click offset within the footprint, cells
        val yCells = (cell.y - anchor.y) + vFrac
        fun sound(event: net.minecraft.sounds.SoundEvent, volume: Float, pitch: Float) {
            lvl?.playSound(null, blockPos, event, net.minecraft.sounds.SoundSource.BLOCKS, volume, pitch)
        }
        when (e.typeId) {
            "switch" -> {
                val next = e.value == 0.0
                setElementValue(anchor, if (next) 1.0 else 0.0)
                sound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 0.1f, if (next) 0.6f else 0.5f)
            }
            "momentary" -> {
                // Click = a short press window (Dashpanels holds while the mouse
                // is down; our click model presses for a few ticks instead).
                setElementValue(anchor, 1.0)
                pressedUntil[e.pinId()] = gameTime + MOMENTARY_PRESS_TICKS
                sound(net.minecraft.sounds.SoundEvents.STONE_BUTTON_CLICK_ON, 0.1f, 1f)
            }
            "push_button" -> {
                // Radio buttons: click selects, clicking the active one clears.
                val buttons = (if (cfg.contains("buttons")) cfg.getInt("buttons") else 1).coerceIn(1, 8)
                val idx = kotlin.math.floor(xCells / e.cols * buttons).toInt().coerceIn(0, buttons - 1)
                val current = e.value.toInt() - 1
                if (current == idx) {
                    setElementValue(anchor, 0.0)
                    sound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 0.1f, 0.5f)
                } else {
                    setElementValue(anchor, (idx + 1).toDouble())
                    sound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 0.1f, 1f)
                }
            }
            "key_switch" -> setElementValue(anchor, if (e.value == 0.0) 1.0 else 0.0)
            "emergency" -> {
                // Only usable while the cover is OPEN (`open` pin). Click
                // presses; sneak-click resets — Dashpanels semantics.
                val bits = e.value.toInt()
                val open = bits and 2 != 0
                val pressed = bits and 1 != 0
                if (!open) return true
                if (pressed && sneak) setElementValue(anchor, (bits and 1.inv()).toDouble())
                else if (!pressed && !sneak) setElementValue(anchor, (bits or 1).toDouble())
            }
            "lever" -> {
                // Positional: click along the 5-cell track sets 0..15. The
                // module frame's 180° flip puts high signal at the TOP, so the
                // click fraction is inverted to land the handle where clicked.
                val signal = kotlin.math.round((1.0 - yCells / e.rows) * 15.0).toInt().coerceIn(0, 15)
                setElementValue(anchor, signal.toDouble())
                sound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 0.1f, (signal + 15) / 15f)
            }
            "knob" -> {
                // Angular: click angle about the centre → 0..1 over 360°.
                val frac = PanelGrid.knobValue(
                    xCells - e.cols / 2.0, yCells - e.rows / 2.0,
                    0.0, 1.0, 360.0, 0.0,
                )
                setElementValue(anchor, frac)
                sound(net.minecraft.sounds.SoundEvents.STONE_BUTTON_CLICK_ON, 0.1f, 1f + frac.toFloat())
            }
            "joystick", "joystick_ctrl" -> {
                // Handled by the client HOLD session (Dashpanels model): RMB
                // starts it client-side, PanelJoystickPacket streams the state.
                // The server-side operate only consumes the click so a bare
                // RMB doesn't fall through to other interactions.
            }
            else -> {
                // Mini touch-screens: record the tap as a 0..1 fraction within
                // the element window (`:touch` / `:touch_down`). A powered-down
                // screen (enable=0, the default) ignores taps.
                if (!PanelElements.isScreen(e.typeId)) return false
                if (e.value == 0.0) return false
                taps[e.pinId()] = Tap(xCells / e.cols, yCells / e.rows, gameTime)
            }
        }
        return true
    }

    /**
     * Live joystick state from a client hold session ([PanelJoystickPacket]).
     * Each update re-arms the expiry, so the stick springs back to centre in
     * [serverTick] as soon as the stream stops (release, disconnect, lag-out).
     * The trigger rides the same keep-alive through [triggerUntil].
     */
    fun setJoystick(pinId: String, x: Float, y: Float, trigger: Boolean, gameTime: Long) {
        val e = store.all().firstOrNull { it.pinId() == pinId && it.typeId.startsWith("joystick") } ?: return
        val pin = e.pinId()
        if (x == 0f && y == 0f && !trigger) {
            joyStates.remove(pin)
            joyExpiry.remove(pin)
            triggerUntil.remove(pin)
        } else {
            joyStates[pin] = floatArrayOf(x, y)
            joyExpiry[pin] = gameTime + JOYSTICK_HOLD_TICKS
            if (trigger) triggerUntil[pin] = gameTime + JOYSTICK_HOLD_TICKS
            else triggerUntil.remove(pin)
        }
        pushSync()
    }

    /** Server tick (from the block ticker): timed releases — the momentary
     *  button pops back up, the joystick springs to centre. */
    fun serverTick(gameTime: Long) {
        var changed = false
        val pressIt = pressedUntil.entries.iterator()
        while (pressIt.hasNext()) {
            val (pin, until) = pressIt.next()
            if (gameTime >= until) {
                pressIt.remove()
                val base = PanelPins.baseId(pin)
                store.all().firstOrNull { it.pinId() == base }?.let {
                    if (store.setValue(PanelGrid.Cell(it.cellX, it.cellY), 0.0)) changed = true
                }
                level?.playSound(null, blockPos, net.minecraft.sounds.SoundEvents.STONE_BUTTON_CLICK_OFF, net.minecraft.sounds.SoundSource.BLOCKS, 0.1f, 1f)
            }
        }
        val joyIt = joyExpiry.entries.iterator()
        while (joyIt.hasNext()) {
            val (pin, until) = joyIt.next()
            if (gameTime >= until) {
                joyIt.remove()
                joyStates.remove(pin)
                changed = true
            }
        }
        if (changed) pushSync()
    }

    // ── unified pin links ─────────────────────────────────────────────────
    override fun pinLinks(): MutableList<PinLink> = pinLinks

    override fun onPinLinksChanged() = pushSync()

    override fun pinOutputs(ctx: LinkContext): List<LinkPin> = PanelPins.outputs(store.all())

    override fun pinInputs(ctx: LinkContext): List<LinkPin> = PanelPins.inputs(store.all())

    /** Resolve an incoming wire id (`"type@x,y"` / `"type@x,y:name"`) to the
     *  element + the pin spec it addresses (incl. dynamic push_button pins). */
    private fun pinAt(id: String): Pair<PlacedElement, ElementPin>? {
        val base = PanelPins.baseId(id)
        val name = PanelPins.pinName(id)
        val e = store.all().firstOrNull { it.pinId() == base } ?: return null
        val spec = PanelPins.pinsFor(e).firstOrNull { it.name == name } ?: return null
        return e to spec
    }

    override fun readPin(id: String): PinReading? {
        val (e, spec) = pinAt(id) ?: return null
        if (spec.dir != PanelPinDir.OUTPUT) return null
        val name = spec.name
        val now = level?.gameTime ?: 0L
        return when {
            name == "" -> when (e.typeId) {
                "switch", "key_switch", "momentary" -> PinReading(PinValue.Bool(e.value != 0.0))
                "push_button", "lever" -> PinReading(PinValue.Int(e.value.toInt()))
                "emergency" -> PinReading(PinValue.Bool(e.value.toInt() and 1 != 0))
                "knob" -> {
                    val cfg = e.config
                    val min = if (cfg.contains("min")) cfg.getDouble("min") else 0.0
                    val max = if (cfg.contains("max")) cfg.getDouble("max") else 1.0
                    PinReading(PinValue.Float((min + e.value.coerceIn(0.0, 1.0) * (max - min)).toFloat()))
                }
                "joystick", "joystick_ctrl" -> {
                    val joy = joyStates[e.pinId()]
                    PinReading(PinValue.Vec2((joy?.get(0) ?: 0f).toDouble(), (joy?.get(1) ?: 0f).toDouble()))
                }
                else -> null
            }
            name == "trigger" -> PinReading(PinValue.Bool(now < (triggerUntil[e.pinId()] ?: Long.MIN_VALUE)))
            name.startsWith("b") && e.typeId == "push_button" ->
                name.drop(1).toIntOrNull()?.let { PinReading(PinValue.Bool(e.value.toInt() == it)) }
            name == "touch" -> taps[e.pinId()]?.let { PinReading(PinValue.Vec2(it.u, it.v)) }
            name == "touch_down" -> taps[e.pinId()]?.let { PinReading(PinValue.Bool(true), pulseStamp = it.time) }
            else -> null
        }
    }

    override fun writePin(id: String, value: PinValue) {
        val (e, spec) = pinAt(id) ?: return
        if (spec.dir != PanelPinDir.INPUT) return
        val anchor = PanelGrid.Cell(e.cellX, e.cellY)
        when (spec.name) {
            "" -> when {
                PanelElements.isScreen(e.typeId) -> {
                    if (value is PinValue.Video) writeVideoHandle(e.pinId(), ScreenBlockEntity.decodeHandle(value))
                }
                e.typeId == "buzzer" -> {
                    // Rising edge → one-shot buzz (Dashpanels' setAnalog).
                    val v = numeric(value) ?: return
                    if (e.value == 0.0 && v != 0.0) {
                        level?.playSound(null, blockPos, Registry.BUZZ.get(), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f)
                    }
                    if (store.setValue(anchor, v)) setChanged()
                }
                else -> numeric(value)?.let { setElementValue(anchor, it) } // bulb / seven_segment
            }
            // Remote drive: the graph sets the control's state; the visual and
            // the primary output follow (same slot as a player click).
            "set" -> numeric(value)?.let { v ->
                when (e.typeId) {
                    "switch" -> setElementValue(anchor, if (v != 0.0) 1.0 else 0.0)
                    "lever" -> setElementValue(anchor, v.coerceIn(0.0, 15.0))
                    "knob" -> {
                        val cfg = e.config
                        val min = if (cfg.contains("min")) cfg.getDouble("min") else 0.0
                        val max = if (cfg.contains("max")) cfg.getDouble("max") else 1.0
                        val frac = if (max == min) 0.0 else ((v - min) / (max - min)).coerceIn(0.0, 1.0)
                        setElementValue(anchor, frac)
                    }
                    else -> setElementValue(anchor, v)
                }
            }
            // Cover control: open = raise the translucent dome; closing it also
            // resets the pressed state (Dashpanels closes → un-press).
            "open" -> numeric(value)?.let { v ->
                val bits = e.value.toInt()
                val next = if (v != 0.0) bits or 2 else 0
                setElementValue(anchor, next.toDouble())
            }
            // Screen power: e.value doubles as the enabled flag.
            "enable" -> numeric(value)?.let {
                setElementValue(anchor, if (it != 0.0) 1.0 else 0.0)
            }
        }
    }

    override fun clearPin(id: String) {
        val (e, spec) = pinAt(id) ?: return
        if (spec.dir != PanelPinDir.INPUT) return
        val anchor = PanelGrid.Cell(e.cellX, e.cellY)
        when (spec.name) {
            "" -> when {
                PanelElements.isScreen(e.typeId) -> writeVideoHandle(e.pinId(), null)
                e.typeId == "seven_segment" -> {} // keeps the last shown value
                else -> setElementValue(anchor, 0.0)
            }
            // A silenced `set` keeps the last state — the control latches.
            "set" -> {}
            // Cover input gone → cover closes (and un-presses).
            "open" -> setElementValue(anchor, 0.0)
            // A silenced `enable` powers the screen DOWN.
            "enable" -> setElementValue(anchor, 0.0)
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
        if (joyStates.isNotEmpty()) {
            val j = CompoundTag()
            for ((pin, xy) in joyStates) {
                val c = CompoundTag()
                c.putFloat("x", xy[0]); c.putFloat("y", xy[1])
                j.put(pin, c)
            }
            tag.put(TAG_JOY, j)
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
        joyStates.clear()
        if (tag.contains(TAG_JOY)) {
            val j = tag.getCompound(TAG_JOY)
            for (key in j.allKeys) {
                val c = j.getCompound(key)
                joyStates[key] = floatArrayOf(c.getFloat("x"), c.getFloat("y"))
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
        private const val TAG_JOY = "joy_states"

        /** Ticks a momentary press / joystick trigger tap stays active. */
        private const val MOMENTARY_PRESS_TICKS = 6L

        /** Ticks a joystick click-deflection holds before springing back. */
        private const val JOYSTICK_HOLD_TICKS = 10L
    }
}
