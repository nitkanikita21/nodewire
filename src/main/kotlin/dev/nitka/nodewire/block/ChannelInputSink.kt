package dev.nitka.nodewire.block

import dev.nitka.nodewire.graph.PinValue

/**
 * A block entity that can receive cross-block channel delivery into a named
 * runtime input slot. [LogicBlockEntity] is the primary implementor (its
 * `channel_input` nodes read the slot on the next tick); endpoint consumers
 * that own no graph — e.g. the video Screen — implement this to receive a
 * `PinValue` (such as `PinValue.Video(handle)`) by channel name **without**
 * the graph layer needing to know their concrete type.
 *
 * The cross-block delivery loop in [LogicBlockEntity.serverTick] resolves the
 * target as a [LogicBlockEntity] first (byte-identical legacy path) and only
 * falls back to this interface for non-Logic targets — so this is purely
 * additive: it adds a new kind of consumer, it does not change how two logic
 * blocks talk to each other.
 *
 * Only the channel *value* crosses — for VIDEO that is a bare `UUID` handle,
 * never a frame (the net invariant of the video subsystem).
 */
interface ChannelInputSink {
    /** Deliver [value] into the runtime slot named [name]. Last-writer-wins. */
    fun writeChannelInput(name: String, value: PinValue)

    /**
     * Delivery variant that also names the SOURCE block. Default delegates to
     * [writeChannelInput]; a sink that needs a back-path (the touch-screen
     * routes taps back to whoever feeds its video) overrides this and records
     * [sourcePos]. Purely additive.
     */
    fun writeChannelInputFrom(name: String, value: PinValue, sourcePos: net.minecraft.core.BlockPos) =
        writeChannelInput(name, value)
}
