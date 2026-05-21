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
    val pos: CanvasPos,
    val inputs: List<Pin>,
    val outputs: List<Pin>,
    val config: CompoundTag = CompoundTag(),
    val label: String? = null,
) {
    companion object {
        val CODEC: Codec<Node> = RecordCodecBuilder.create { i ->
            i.group(
                GraphCodecs.UUID_CODEC.fieldOf("id").forGetter(Node::id),
                ResourceLocation.CODEC.fieldOf("type").forGetter(Node::typeKey),
                CanvasPos.CODEC.fieldOf("pos").forGetter(Node::pos),
                Pin.CODEC.listOf().fieldOf("inputs").forGetter(Node::inputs),
                Pin.CODEC.listOf().fieldOf("outputs").forGetter(Node::outputs),
                CompoundTag.CODEC.fieldOf("config").forGetter(Node::config),
                Codec.STRING.optionalFieldOf("label")
                    .forGetter { java.util.Optional.ofNullable(it.label) },
            ).apply(i) { id, type, pos, inputs, outputs, config, label ->
                Node(id, type, pos, inputs, outputs, config, label.orElse(null))
            }
        }

        fun newId(): NodeId = UUID.randomUUID()
    }
}

/**
 * Inline pin-default lookup. Returns the value stored under
 * `config["pinDefaults"][pinId]` or null if no default has been set
 * for this pin. Decode is via [PinValue.CODEC]; corrupt entries return
 * null (caller falls back to [PinValue.default]).
 */
fun Node.getPinDefault(pinId: String): PinValue? {
    val defaults = config.get("pinDefaults") as? net.minecraft.nbt.CompoundTag ?: return null
    val tag = defaults.get(pinId) ?: return null
    return PinValue.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElse(null)
}

/**
 * Return a copy of this node with the inline default for [pinId] set
 * to [value], or cleared if [value] is null. Mutates only the
 * `pinDefaults` sub-tag of [config]; all other config keys are
 * preserved.
 */
fun Node.withPinDefault(pinId: String, value: PinValue?): Node {
    val cfg = config.copy()
    val existing = cfg.get("pinDefaults") as? net.minecraft.nbt.CompoundTag
    val defaults = existing?.copy() ?: net.minecraft.nbt.CompoundTag()
    if (value == null) {
        defaults.remove(pinId)
    } else {
        PinValue.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, value)
            .result().ifPresent { defaults.put(pinId, it) }
    }
    cfg.put("pinDefaults", defaults)
    return copy(config = cfg)
}
