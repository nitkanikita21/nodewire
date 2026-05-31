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
 * Camera parameters (fov / enable / yaw / pitch) arrive through the existing
 * channel pipeline: the BE is registered as a [ChannelTargetRegistry] target
 * exposing four typed [TargetSlot.Channel] slots, so the Channel Link Tool can
 * drive them with zero new packet code. Each delivered value lands in
 * [channelInputs] via [writeChannelInput]; the param readers clamp+default.
 *
 * Client lifecycle (gated by `level?.isClientSide == true`):
 *   - `onLoad`     -> `VideoManager.acquire(handle)` + `CameraFeedRegistry.register`
 *   - `setRemoved` -> `VideoManager.release(handle)` + `CameraFeedRegistry.unregister`
 */
class CameraBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Registry.CAMERA_BLOCK_BE.get(), pos, state), ChannelInputSink {

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
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.hasUUID(HANDLE_KEY)) handle = tag.getUUID(HANDLE_KEY)
        readParam(tag, FOV_CHANNEL, numeric = true)
        readParam(tag, YAW_CHANNEL, numeric = true)
        readParam(tag, PITCH_CHANNEL, numeric = true)
        if (tag.contains(ENABLE_CHANNEL)) channelInputs[ENABLE_CHANNEL] = PinValue.Bool(tag.getBoolean(ENABLE_CHANNEL))
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
        // Mint a stable handle on first load (either side); persist it.
        if (handle == UUID(0L, 0L)) {
            handle = UUID.randomUUID()
            setChanged()
        }
        if (level?.isClientSide == true && !clientRegistered) {
            dev.nitka.nodewire.client.video.VideoManager.acquire(handle)
            dev.nitka.nodewire.client.camera.CameraFeedRegistry.register(
                dev.nitka.nodewire.client.camera.CameraFeed(this),
            )
            clientRegistered = true
        }
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

        const val FOV_CHANNEL = "fov"
        const val ENABLE_CHANNEL = "enable"
        const val YAW_CHANNEL = "yaw"
        const val PITCH_CHANNEL = "pitch"

        /**
         * Channel target so the Channel Link Tool offers the four typed camera
         * parameter inputs on this block. Registered in [Registry].
         */
        val CHANNEL_TARGET: ChannelTargetProvider = object : ChannelTargetProvider {
            override fun slotsFor(
                level: Level,
                pos: BlockPos,
                state: BlockState,
                clickedFace: Direction,
            ): List<TargetSlot> = listOf(
                TargetSlot.Channel(FOV_CHANNEL, PinType.FLOAT),
                TargetSlot.Channel(ENABLE_CHANNEL, PinType.BOOL),
                TargetSlot.Channel(YAW_CHANNEL, PinType.FLOAT),
                TargetSlot.Channel(PITCH_CHANNEL, PinType.FLOAT),
            )
        }
    }
}
