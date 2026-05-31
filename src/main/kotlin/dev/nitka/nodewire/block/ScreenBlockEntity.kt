package dev.nitka.nodewire.block

import dev.nitka.nodewire.Registry
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
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
    BlockEntity(Registry.SCREEN_BLOCK_BE.get(), pos, state), ChannelInputSink {

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

    /**
     * [ChannelInputSink] entry point. Cross-block delivery writes the VIDEO
     * handle here. On the client, retarget the [VideoManager] refcount when the
     * handle changes.
     */
    override fun writeChannelInput(name: String, value: PinValue) {
        channelInputs[name] = value
        setChanged()
        if (level?.isClientSide == true && name == SCREEN_CHANNEL) {
            retargetClientRefcount()
        }
    }

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
    }

    companion object {
        /** The single named channel slot a Screen accepts. */
        const val SCREEN_CHANNEL = "screen"

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

        /**
         * Channel target so the Channel Link Tool offers a single VIDEO `"screen"`
         * input on this block. Registered in [Registry].
         */
        val CHANNEL_TARGET: ChannelTargetProvider = object : ChannelTargetProvider {
            override fun slotsFor(
                level: Level,
                pos: BlockPos,
                state: BlockState,
                clickedFace: Direction,
            ): List<TargetSlot> = listOf(TargetSlot.Channel(SCREEN_CHANNEL, PinType.VIDEO))
        }
    }
}
