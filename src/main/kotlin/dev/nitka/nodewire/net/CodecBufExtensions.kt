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
        .orElseThrow { error("Codec encode failed for $value") }
    val tag = encoded as? CompoundTag
        ?: error("Expected CompoundTag from top-level codec, got ${encoded::class.qualifiedName}: $encoded")
    writeNbt(tag)
}

fun <T> FriendlyByteBuf.readCodec(codec: Codec<T>): T {
    val tag = readNbt() ?: error("NBT read failed — buffer corrupted or incomplete")
    return codec.parse(NbtOps.INSTANCE, tag).result()
        .orElseThrow { error("Codec decode failed for tag $tag") }
}
