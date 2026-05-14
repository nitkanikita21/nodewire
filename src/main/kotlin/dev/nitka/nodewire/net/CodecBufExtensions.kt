package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.FriendlyByteBuf

/**
 * Bridge between Mojang Codec and the network buffer. Codecs encode to a
 * [net.minecraft.nbt.Tag], so we wrap the result as a [CompoundTag] (every
 * record-codec produces a CompoundTag) and ship it via the buffer's
 * built-in NBT writer.
 *
 * One serialization layer: the same codec drives both BlockEntity NBT and
 * SimpleChannel packets.
 */
fun <T> FriendlyByteBuf.writeCodec(codec: Codec<T>, value: T) {
    val encoded = codec.encodeStart(NbtOps.INSTANCE, value).result()
        .orElseThrow { IllegalStateException("Codec encode failed for $value") }
    val tag = encoded as? CompoundTag
        ?: error("Top-level codec must produce a CompoundTag, got ${encoded::class.simpleName}")
    writeNbt(tag)
}

fun <T> FriendlyByteBuf.readCodec(codec: Codec<T>): T {
    val tag = readNbt() ?: error("readNbt() returned null — buffer empty?")
    return codec.parse(NbtOps.INSTANCE, tag).result()
        .orElseThrow { IllegalStateException("Codec decode failed for tag $tag") }
}
