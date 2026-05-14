package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/**
 * Canvas-space position of a [Node]. Unbounded floats; the editor pans /
 * zooms over this coordinate space freely. Z-order is purely insertion-
 * based — there's no explicit layer field.
 */
data class CanvasPos(val x: Float, val y: Float) {
    fun writeTo(tag: CompoundTag) { tag.putFloat("x", x); tag.putFloat("y", y) }
    companion object {
        val Zero = CanvasPos(0f, 0f)
        fun fromNbt(tag: CompoundTag) = CanvasPos(tag.getFloat("x"), tag.getFloat("y"))
    }
}

/**
 * One vertex in the graph. [typeKey] points to a `NodeType` registry entry
 * which provides the display name and pin layout factory; the layout is
 * also serialized per-instance so a graph loaded with an unregistered type
 * still round-trips (we can't render or evaluate it, but we don't lose it).
 *
 * [config] holds type-specific settings — a constant node stores its value
 * here; a timer stores its period.
 */
data class Node(
    val id: NodeId,
    val typeKey: ResourceLocation,
    var pos: CanvasPos,
    /**
     * Mutable to support configurable-pin nodes — e.g. a [NodeType] whose
     * pin type comes from a config field (Channel I/O, ConvertToRedstone)
     * needs to rebuild its pin list when the user picks a different type.
     * Edges touching changed pins are caller's responsibility (see
     * `EditorState.rebuildPinsAndDisconnect`).
     */
    var inputs: List<Pin>,
    var outputs: List<Pin>,
    val config: CompoundTag = CompoundTag(),
) {

    fun toNbt(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("id", id)
        tag.putString("type", typeKey.toString())
        tag.put("pos", CompoundTag().also { pos.writeTo(it) })
        tag.put("inputs", writePinList(inputs))
        tag.put("outputs", writePinList(outputs))
        tag.put("config", config)
        return tag
    }

    private fun writePinList(pins: List<Pin>): net.minecraft.nbt.ListTag {
        val list = net.minecraft.nbt.ListTag()
        for (pin in pins) {
            val pTag = CompoundTag()
            pTag.putString("id", pin.id)
            pTag.putString("name", pin.name)
            pTag.putString("type", pin.type.name)
            list.add(pTag)
        }
        return list
    }

    companion object {
        fun fromNbt(tag: CompoundTag): Node {
            val inputs = readPinList(tag.getList("inputs", 10))   // 10 = CompoundTag
            val outputs = readPinList(tag.getList("outputs", 10))
            return Node(
                id = tag.getUUID("id"),
                typeKey = ResourceLocation(tag.getString("type")),
                pos = CanvasPos.fromNbt(tag.getCompound("pos")),
                inputs = inputs,
                outputs = outputs,
                config = tag.getCompound("config"),
            )
        }

        private fun readPinList(list: net.minecraft.nbt.ListTag): List<Pin> =
            (0 until list.size).map { i ->
                val pTag = list.getCompound(i)
                Pin(
                    id = pTag.getString("id"),
                    name = pTag.getString("name"),
                    type = PinType.fromName(pTag.getString("type")),
                )
            }

        fun newId(): NodeId = UUID.randomUUID()
    }
}
