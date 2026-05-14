package dev.nitka.nodewire.net

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private data class TwoInts(val a: Int, val b: Int) {
    companion object {
        val CODEC: Codec<TwoInts> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("a").forGetter(TwoInts::a),
                Codec.INT.fieldOf("b").forGetter(TwoInts::b),
            ).apply(i, ::TwoInts)
        }
    }
}

class BufCodecTest {
    @Test
    fun roundTripThroughBuf() {
        val original = TwoInts(7, -3)
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeCodec(TwoInts.CODEC, original)
        val decoded = buf.readCodec(TwoInts.CODEC)
        assertEquals(original, decoded)
    }
}
