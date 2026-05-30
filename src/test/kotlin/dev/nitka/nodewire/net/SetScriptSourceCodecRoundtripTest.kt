package dev.nitka.nodewire.net

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * STREAM_CODEC round-trip for [SetScriptSourcePacket]. We exercise the same
 * Codec the registry-aware stream codec wraps (`fromCodecWithRegistries`)
 * via FriendlyByteBuf.writeCodec/readCodec — no RegistryAccess needed for
 * these registry-free fields.
 */
class SetScriptSourceCodecRoundtripTest {

    @Test
    fun `packet roundtrips through CODEC`() {
        val original = SetScriptSourcePacket(
            blockPos = BlockPos(12, -3, 47),
            nodeId = UUID.fromString("00000000-0000-0000-0000-0000000000ab"),
            src = "val a = input<Int>(\"a\")\noutput<Redstone>(\"out\")\ntick { out.set(a.get()) }",
        )

        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeCodec(SetScriptSourcePacket.CODEC, original)
        val decoded = buf.readCodec(SetScriptSourcePacket.CODEC)

        assertEquals(original.blockPos, decoded.blockPos)
        assertEquals(original.nodeId, decoded.nodeId)
        assertEquals(original.src, decoded.src)
        assertEquals(original, decoded)
    }
}
