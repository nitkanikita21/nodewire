package dev.nitka.nodewire.block

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.endpoint.WorldBackend
import dev.nitka.nodewire.endpoint.WorldPayload
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Drive-by-wire binding from a named channel on a source LogicBlock to an
 * arbitrary block face. Target identity is an [EndpointRef], so the face
 * can sit on a VS ship or Create contraption block as well as a world
 * block. Signals reach the target via [dev.nitka.nodewire.signal.VirtualSignalMap].
 *
 * Legacy NBT (pre-2026-05-16) stored `pos: BlockPos`; the legacy codec
 * arm wraps it as `EndpointRef.World(pos)`.
 */
data class SideBinding(
    val sourceChannelName: String,
    val target: EndpointRef,
    val targetSide: Direction,
    val name: String = "",
) {
    companion object {
        private val NEW_CODEC: Codec<SideBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(SideBinding::sourceChannelName),
                EndpointRef.CODEC.fieldOf("target").forGetter(SideBinding::target),
                Direction.CODEC.fieldOf("side").forGetter(SideBinding::targetSide),
                Codec.STRING.optionalFieldOf("name", "").forGetter(SideBinding::name),
            ).apply(i, ::SideBinding)
        }

        private val LEGACY_CODEC: Codec<SideBinding> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("src").forGetter(SideBinding::sourceChannelName),
                BlockPos.CODEC.fieldOf("pos").forGetter { it.target.payload.blockPos },
                Direction.CODEC.fieldOf("side").forGetter(SideBinding::targetSide),
                Codec.STRING.optionalFieldOf("name", "").forGetter(SideBinding::name),
            ).apply(i) { src, pos, side, name ->
                SideBinding(src, EndpointRef(WorldBackend.id, WorldPayload(pos)), side, name)
            }
        }

        val CODEC: Codec<SideBinding> = Codec.either(NEW_CODEC, LEGACY_CODEC)
            .xmap(
                { e -> e.map({ it }, { it }) },
                { com.mojang.datafixers.util.Either.left(it) },
            )
    }
}
