package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag

/**
 * Directed connection from an output pin ([from]) to an input pin ([to]).
 * The graph is a DAG — server-side validation in [SaveGraphPacket] rejects
 * cycles when execution is added in a later slice.
 *
 * Type compatibility (`from.type == to.type`) is enforced at edit time
 * (UI shows red drag-preview on mismatch) and re-checked on save.
 */
data class Edge(val from: PinRef, val to: PinRef) {

    fun toNbt(): CompoundTag {
        val tag = CompoundTag()
        val fromTag = CompoundTag().also { from.writeTo(it) }
        val toTag = CompoundTag().also { to.writeTo(it) }
        tag.put("from", fromTag)
        tag.put("to", toTag)
        return tag
    }

    companion object {
        fun fromNbt(tag: CompoundTag) = Edge(
            from = PinRef.fromNbt(tag.getCompound("from")),
            to = PinRef.fromNbt(tag.getCompound("to")),
        )
    }
}
