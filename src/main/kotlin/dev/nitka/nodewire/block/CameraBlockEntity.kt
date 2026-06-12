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
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * BlockEntity for [CameraBlock] — the *producer* end of the handle->channel
 * video pipeline. Mints a persisted, stable [handle] (a `randomUUID` on first
 * load) which it `acquire()`s on the client and publishes as
 * `PinValue.Video(handle)` into a bound channel; a client capture loop renders
 * the world from this block's POV into that handle's surface (see
 * `VideoCameraCapture`), and a [ScreenBlock] on the other end blits it.
 *
 * Mirror of [ScreenBlockEntity], inverted: the Screen *consumes* a handle
 * delivered over a channel, the Camera *owns* a handle and feeds it out.
 *
 * Camera parameters (fov / enable / yaw / pitch) arrive through the unified
 * pin-link surface: the BE exposes four typed input pins, so the Channel Link
 * Tool can drive them from any source. Each delivered value lands in
 * [channelInputs] via [writeChannelInput]; the param readers clamp+default.
 *
 * Client lifecycle (gated by `level?.isClientSide == true`):
 *   - `onLoad`     -> `VideoManager.acquire(handle)` + `CameraFeedRegistry.register`
 *   - `setRemoved` -> `VideoManager.release(handle)` + `CameraFeedRegistry.unregister`
 */
class CameraBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.CAMERA_BLOCK_BE.get(), pos, state),
    ChannelInputSink,
    dev.nitka.nodewire.link.PinLinkSink {

    // ── unified pin links ─────────────────────────────────────────────────
    // Output `video` = the stable handle, sampled per tick by linked sinks
    // (a Screen or Logic channel keeps tracking this camera continuously —
    // no more one-shot binds). Inputs = the four camera params.

    private val pinLinks: MutableList<dev.nitka.nodewire.link.PinLink> = mutableListOf()

    override val pinLinkScratch = dev.nitka.nodewire.link.PinLinkScratch()

    override fun pinLinks(): MutableList<dev.nitka.nodewire.link.PinLink> = pinLinks

    fun pinLinksSnapshot(): List<dev.nitka.nodewire.link.PinLink> = pinLinks.toList()

    override fun onPinLinksChanged() {
        setChanged()
    }

    override fun pinOutputs(ctx: dev.nitka.nodewire.link.LinkContext): List<dev.nitka.nodewire.link.LinkPin> =
        listOf(dev.nitka.nodewire.link.LinkPin(VIDEO_PIN, PinType.VIDEO))

    override fun pinInputs(ctx: dev.nitka.nodewire.link.LinkContext): List<dev.nitka.nodewire.link.LinkPin> =
        listOf(
            dev.nitka.nodewire.link.LinkPin(FOV_CHANNEL, PinType.FLOAT),
            dev.nitka.nodewire.link.LinkPin(ENABLE_CHANNEL, PinType.BOOL),
            dev.nitka.nodewire.link.LinkPin(YAW_CHANNEL, PinType.FLOAT),
            dev.nitka.nodewire.link.LinkPin(PITCH_CHANNEL, PinType.FLOAT),
        )

    override fun readPin(id: String): dev.nitka.nodewire.link.PinReading? =
        if (id == VIDEO_PIN) dev.nitka.nodewire.link.PinReading(videoValue()) else null

    override fun writePin(id: String, value: PinValue) = writeChannelInput(id, value)

    override fun clearPin(id: String) {
        if (channelInputs.remove(id) != null) {
            setChanged()
            val lvl = level
            if (lvl != null && !lvl.isClientSide) {
                lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
            }
        }
    }

    /** Stable, persisted video handle. Lazily minted on first client/server load. */
    private var handle: UUID = UUID(0L, 0L)

    /**
     * Runtime channel-input slots (transient, last-writer-wins) — carry the
     * camera parameters delivered over the channel pipeline.
     */
    private val channelInputs: MutableMap<String, PinValue> = mutableMapOf()

    /** Whether this client BE has registered its feed (idempotency guard). */
    private var clientRegistered = false

    /** The stable handle this camera produces into. */
    fun videoHandle(): UUID = handle

    /**
     * The Camera's *emitting value* — the producer end of the handle->channel
     * video pipeline. This is the exact `PinValue` a Screen consumes: it carries
     * only the stable [handle] UUID (never a frame — the net invariant), so it
     * crosses the channel pipeline like any other VIDEO value.
     *
     * v1 routing (no new packet): a source feeds this into a consumer through the
     * existing cross-block path the [ScreenBlockEntity] already reads from — a
     * [LogicBlockEntity] receives it via [LogicBlockEntity.nwWriteChannelInput]
     * (its `externalChannelInputs` slot), or a graphless [ChannelInputSink] (the
     * Screen) receives it via [ChannelInputSink.writeChannelInput]. The Camera
     * itself stays graphless; only the UUID ever moves server-side.
     */
    fun videoValue(): PinValue.Video = PinValue.Video(handle)

    // ── camera parameters (channel-delivered, defaulted + clamped) ──

    /** Field of view in degrees. Default 90, clamped 30..110. */
    fun fovDeg(): Double {
        val raw = (channelInputs[FOV_CHANNEL] as? PinValue.Float)?.value?.toDouble() ?: 90.0
        return Mth.clamp(raw, 30.0, 110.0)
    }

    /** Whether the camera is actively capturing. Default true. */
    fun enabled(): Boolean = (channelInputs[ENABLE_CHANNEL] as? PinValue.Bool)?.value ?: true

    /** Yaw offset in degrees. Default 0. */
    fun yawDeg(): Float = (channelInputs[YAW_CHANNEL] as? PinValue.Float)?.value ?: 0f

    /** Pitch offset in degrees. Default 0. */
    fun pitchDeg(): Float = (channelInputs[PITCH_CHANNEL] as? PinValue.Float)?.value ?: 0f

    /**
     * [ChannelInputSink] entry point — cross-block delivery of a camera param.
     * On the server, push a BE update so the client learns the new value; on
     * the client, the readers pick it up directly.
     */
    override fun writeChannelInput(name: String, value: PinValue) {
        val changed = channelInputs[name] != value
        channelInputs[name] = value
        setChanged()
        val lvl = level
        if (changed && lvl != null && !lvl.isClientSide) {
            lvl.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
        }
    }

    // ── persistence + client sync ──

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putUUID(HANDLE_KEY, handle)
        for ((name, value) in channelInputs) {
            (value as? PinValue.Float)?.let { tag.putFloat(name, it.value) }
            (value as? PinValue.Bool)?.let { tag.putBoolean(name, it.value) }
        }
        if (pinLinks.isNotEmpty()) {
            dev.nitka.nodewire.link.PinLink.CODEC.listOf()
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, pinLinks.toList())
                .result().ifPresent { tag.put(TAG_PIN_LINKS, it) }
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.hasUUID(HANDLE_KEY)) handle = tag.getUUID(HANDLE_KEY)
        readParam(tag, FOV_CHANNEL, numeric = true)
        readParam(tag, YAW_CHANNEL, numeric = true)
        readParam(tag, PITCH_CHANNEL, numeric = true)
        if (tag.contains(ENABLE_CHANNEL)) channelInputs[ENABLE_CHANNEL] = PinValue.Bool(tag.getBoolean(ENABLE_CHANNEL))
        pinLinks.clear()
        if (tag.contains(TAG_PIN_LINKS)) {
            dev.nitka.nodewire.link.PinLink.CODEC.listOf()
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag.get(TAG_PIN_LINKS))
                .result().ifPresent { pinLinks.addAll(it) }
        }
        // The synced handle may arrive here AFTER the client's onLoad (fresh
        // placement), so (re)try registration now that it's known.
        tryClientRegister()
    }

    private fun readParam(tag: CompoundTag, key: String, numeric: Boolean) {
        if (numeric && tag.contains(key)) channelInputs[key] = PinValue.Float(tag.getFloat(key))
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().also { saveAdditional(it, registries) }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    // ── lifecycle ──

    override fun onLoad() {
        super.onLoad()
        // The handle is SERVER-AUTHORITATIVE — only the server mints it. On a
        // fresh placement the client's onLoad fires BEFORE the BE data packet
        // arrives, so minting here would diverge from the server's handle: the
        // capture would render into one surface while the Screen (bound to the
        // server handle) blits another → white. The client waits for the sync.
        if (level?.isClientSide == false && handle == UUID(0L, 0L)) {
            handle = UUID.randomUUID()
            setChanged()
            // Push so nearby clients learn the freshly-minted handle promptly.
            level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
        }
        tryClientRegister()
    }

    /**
     * Register this camera's client capture feed — but only once the
     * server-authoritative [handle] is known (non-nil). Safe to call repeatedly
     * (from [onLoad] and every [loadAdditional] sync); registers exactly once.
     */
    private fun tryClientRegister() {
        if (level?.isClientSide != true || clientRegistered) return
        if (handle == UUID(0L, 0L)) return // handle not synced from the server yet
        dev.nitka.nodewire.client.video.VideoManager.acquire(handle)
        dev.nitka.nodewire.client.camera.CameraFeedRegistry.register(
            dev.nitka.nodewire.client.camera.CameraFeed(this),
        )
        clientRegistered = true
    }

    override fun setRemoved() {
        if (level?.isClientSide == true && clientRegistered) {
            dev.nitka.nodewire.client.video.VideoManager.release(handle)
            dev.nitka.nodewire.client.camera.CameraFeedRegistry.unregister(handle)
            clientRegistered = false
        }
        super.setRemoved()
    }

    companion object {
        /** NBT key for the persisted stable handle. */
        const val HANDLE_KEY = "video_handle"

        /** The camera's single output pin. */
        const val VIDEO_PIN = "video"

        const val FOV_CHANNEL = "fov"
        const val ENABLE_CHANNEL = "enable"
        const val YAW_CHANNEL = "yaw"
        const val PITCH_CHANNEL = "pitch"

        private const val TAG_PIN_LINKS = "pin_links"
    }
}
