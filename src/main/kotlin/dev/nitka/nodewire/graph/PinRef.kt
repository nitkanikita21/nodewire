package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import java.util.UUID

typealias NodeId = UUID

/**
 * Stable reference to a specific pin: which node owns it (by [NodeId]) and
 * which pin on that node (by [Pin.id]). Used as the endpoints of an [Edge].
 */
data class PinRef(val node: NodeId, val pin: String) {

    fun writeTo(tag: CompoundTag) {
        tag.putUUID("node", node)
        tag.putString("pin", pin)
    }

    companion object {
        fun fromNbt(tag: CompoundTag) = PinRef(tag.getUUID("node"), tag.getString("pin"))
    }
}
