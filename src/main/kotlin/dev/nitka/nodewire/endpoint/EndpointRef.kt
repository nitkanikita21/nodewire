// src/main/kotlin/dev/nitka/nodewire/endpoint/EndpointRef.kt
package dev.nitka.nodewire.endpoint

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3

/**
 * Placeholder payload retained when a binding references a backend that
 * isn't registered in the current session (e.g. mod removed). Preserves
 * the raw NBT wrapper so re-adding the mod restores the binding. `resolve()`
 * and `worldCenter()` always return null, so signal flow and rendering skip
 * these silently.
 *
 * [wrapper] is the outer [CompoundTag] with key "v" holding the actual tag,
 * exactly as stored on disk — preserved verbatim for forward-compatibility.
 */
data class UnknownPayload(val wrapper: CompoundTag) : EndpointPayload {
    override val blockPos: BlockPos = BlockPos.ZERO
}

/**
 * Tagged reference to one endpoint. The pair [backendId] + [payload] is
 * the persisted identity of a binding target.
 */
data class EndpointRef(val backendId: ResourceLocation, val payload: EndpointPayload) {

    fun resolve(level: Level): BlockEntity? =
        EndpointBackends.get(backendId)?.resolveBlockEntity(level, payload)

    fun worldCenter(level: Level): Vec3? =
        EndpointBackends.get(backendId)?.worldCenter(level, payload)

    companion object {
        /**
         * Resolve the right backend for a raycast hit. The world backend
         * (last in registration order) always claims, so this never throws
         * once the world backend is registered.
         */
        fun from(level: Level, hitPos: BlockPos): EndpointRef {
            for (b in EndpointBackends.all()) {
                b.claims(level, hitPos)?.let { return EndpointRef(b.id, it) }
            }
            error("no backend claimed pos $hitPos — was WorldBackend registered?")
        }

        /**
         * Wraps an arbitrary [net.minecraft.nbt.Tag] inside a [CompoundTag]
         * under key "v", so the outer codec field is always an NBT compound
         * regardless of what shape the backend's payload codec produces (e.g.
         * [BlockPos.CODEC] encodes to [net.minecraft.nbt.IntArrayTag], not a
         * compound).
         */
        private fun wrapTag(tag: net.minecraft.nbt.Tag): CompoundTag =
            CompoundTag().also { it.put("v", tag) }

        val CODEC: Codec<EndpointRef> = RecordCodecBuilder.create { i ->
            i.group(
                ResourceLocation.CODEC.fieldOf("backend").forGetter(EndpointRef::backendId),
                CompoundTag.CODEC.fieldOf("payload").forGetter { ref ->
                    val backend = EndpointBackends.get(ref.backendId)
                    when {
                        ref.payload is UnknownPayload -> ref.payload.wrapper
                        backend == null -> CompoundTag()
                        else -> {
                            val rawTag = @Suppress("UNCHECKED_CAST") (backend.payloadCodec as Codec<EndpointPayload>)
                                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, ref.payload)
                                .result().orElse(CompoundTag())
                            wrapTag(rawTag)
                        }
                    }
                },
            ).apply(i) { id, payloadWrapper ->
                val backend = EndpointBackends.get(id)
                val innerTag = payloadWrapper.get("v") ?: payloadWrapper
                val payload: EndpointPayload = if (backend != null) {
                    @Suppress("UNCHECKED_CAST") val codec = backend.payloadCodec as Codec<EndpointPayload>
                    codec
                        .parse(net.minecraft.nbt.NbtOps.INSTANCE, innerTag)
                        .result().orElse(UnknownPayload(payloadWrapper))
                } else {
                    UnknownPayload(payloadWrapper)
                }
                EndpointRef(id, payload)
            }
        }
    }
}
