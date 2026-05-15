package dev.nitka.nodewire.block

import com.mojang.serialization.JsonOps
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SideBindingCodecTest {

    @Test
    fun roundtripsWithName() {
        val original = SideBinding("chA", BlockPos(1, 2, 3), Direction.NORTH, "my label")
        val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, original).result().get()
        val decoded = SideBinding.CODEC.parse(JsonOps.INSTANCE, encoded).result().get()
        assertEquals(original, decoded)
    }

    @Test
    fun roundtripsWithEmptyName() {
        val original = SideBinding("chA", BlockPos(1, 2, 3), Direction.NORTH)
        val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, original).result().get()
        val decoded = SideBinding.CODEC.parse(JsonOps.INSTANCE, encoded).result().get()
        assertEquals(original, decoded)
        assertEquals("", decoded.name)
    }

    @Test
    fun decodesLegacyNbtWithoutNameField() {
        // Encode a full SideBinding then strip the "name" key to simulate a legacy save.
        val full = SideBinding("chA", BlockPos(1, 2, 3), Direction.NORTH, "anything")
        val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, full).result().get().asJsonObject
        encoded.remove("name")
        val decoded = SideBinding.CODEC.parse(JsonOps.INSTANCE, encoded).result().get()
        assertEquals("", decoded.name)
    }
}
