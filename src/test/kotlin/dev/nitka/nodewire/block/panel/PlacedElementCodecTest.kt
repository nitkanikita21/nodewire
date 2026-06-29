package dev.nitka.nodewire.block.panel

import com.mojang.serialization.JsonOps
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlacedElementCodecTest {
    private fun sample(): PlacedElement {
        val cfg = CompoundTag().apply { putInt("positions", 4) }
        return PlacedElement("selector", 3, 5, 2, 2, cfg, 0.0)
    }

    @Test fun `pin id encodes type and anchor cell`() {
        assertEquals("selector@3,5", sample().pinId())
    }

    @Test fun `round-trips through NbtOps`() {
        val original = sample()
        val tag = PlacedElement.CODEC.encodeStart(NbtOps.INSTANCE, original).result().orElseThrow()
        val decoded = PlacedElement.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow()
        assertEquals(original, decoded)
    }

    @Test fun `round-trips through JsonOps with a float value`() {
        val original = sample().copy(typeId = "slider", value = 0.42)
        val json = PlacedElement.CODEC.encodeStart(JsonOps.INSTANCE, original).result().orElseThrow()
        val decoded = PlacedElement.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow()
        assertEquals(original, decoded)
    }
}
