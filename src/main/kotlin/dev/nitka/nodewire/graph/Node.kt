package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/**
 * Canvas-space position of a [Node]. Unbounded floats; the editor pans /
 * zooms over this coordinate space freely. Z-order is purely insertion-
 * based — there's no explicit layer field.
 */
data class CanvasPos(val x: Float, val y: Float) {
    companion object {
        val Zero = CanvasPos(0f, 0f)
        val CODEC: Codec<CanvasPos> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.FLOAT.fieldOf("x").forGetter(CanvasPos::x),
                Codec.FLOAT.fieldOf("y").forGetter(CanvasPos::y),
            ).apply(i, ::CanvasPos)
        }
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
    var inputs: List<Pin>,
    var outputs: List<Pin>,
    val config: CompoundTag = CompoundTag(),
) {
    companion object {
        private val UUID_CODEC: Codec<UUID> =
            Codec.STRING.xmap(UUID::fromString, UUID::toString)

        val CODEC: Codec<Node> = RecordCodecBuilder.create { i ->
            i.group(
                UUID_CODEC.fieldOf("id").forGetter(Node::id),
                ResourceLocation.CODEC.fieldOf("type").forGetter(Node::typeKey),
                CanvasPos.CODEC.fieldOf("pos").forGetter(Node::pos),
                Pin.CODEC.listOf().fieldOf("inputs").forGetter(Node::inputs),
                Pin.CODEC.listOf().fieldOf("outputs").forGetter(Node::outputs),
                CompoundTag.CODEC.fieldOf("config").forGetter(Node::config),
            ).apply(i, ::Node)
        }

        fun newId(): NodeId = UUID.randomUUID()
    }
}
