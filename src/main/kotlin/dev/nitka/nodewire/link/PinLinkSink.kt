package dev.nitka.nodewire.link

/**
 * A [PinPort] that also STORES the [PinLink]s feeding its input pins (and
 * persists them to NBT). [PinLinkEngine.tick] runs against this from the
 * block's server ticker: prune stale links, pull each source pin, write the
 * value into the matching input pin.
 *
 * Implemented by LogicBlockEntity, ScreenBlockEntity, CameraBlockEntity.
 */
interface PinLinkSink : PinPort {
    /** Live, mutable link list. Engine + bind/remove packets mutate it. */
    fun pinLinks(): MutableList<PinLink>

    /** Persist + replicate after a mutation (setChanged + block update). */
    fun onPinLinksChanged()

    /** Engine scratch (per-instance, transient): which input pins the last
     *  engine tick actually delivered into — diffed to [PinPort.clearPin]
     *  pins that went silent. */
    val pinLinkScratch: PinLinkScratch
}

/** Transient per-sink engine state. */
class PinLinkScratch {
    var lastDelivered: Set<String> = emptySet()
}
