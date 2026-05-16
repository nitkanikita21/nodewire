package dev.nitka.nodewire.block

import com.mojang.serialization.JsonOps
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SideBindingCodecTest {

    @org.junit.jupiter.api.BeforeEach fun resetBackends() {
        dev.nitka.nodewire.endpoint.EndpointBackends.clearForTests()
        dev.nitka.nodewire.endpoint.EndpointBackends.register(
            dev.nitka.nodewire.endpoint.WorldBackend
        )
    }

    @Test
    fun roundtripsWithName() {
        val ref = dev.nitka.nodewire.endpoint.EndpointRef(
            dev.nitka.nodewire.endpoint.WorldBackend.id,
            dev.nitka.nodewire.endpoint.WorldPayload(BlockPos(1, 2, 3)),
        )
        val original = SideBinding("chA", ref, Direction.NORTH, "my label")
        val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, original).result().get()
        val decoded = SideBinding.CODEC.parse(JsonOps.INSTANCE, encoded).result().get()
        assertEquals(original, decoded)
    }

    @Test
    fun roundtripsWithEmptyName() {
        val ref = dev.nitka.nodewire.endpoint.EndpointRef(
            dev.nitka.nodewire.endpoint.WorldBackend.id,
            dev.nitka.nodewire.endpoint.WorldPayload(BlockPos(1, 2, 3)),
        )
        val original = SideBinding("chA", ref, Direction.NORTH)
        val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, original).result().get()
        val decoded = SideBinding.CODEC.parse(JsonOps.INSTANCE, encoded).result().get()
        assertEquals(original, decoded)
        assertEquals("", decoded.name)
    }

    @Test
    fun decodesLegacyNbtWithoutNameField() {
        // Encode a full SideBinding then strip the "name" key to simulate a legacy save.
        val ref = dev.nitka.nodewire.endpoint.EndpointRef(
            dev.nitka.nodewire.endpoint.WorldBackend.id,
            dev.nitka.nodewire.endpoint.WorldPayload(BlockPos(1, 2, 3)),
        )
        val full = SideBinding("chA", ref, Direction.NORTH, "anything")
        val encoded = SideBinding.CODEC.encodeStart(JsonOps.INSTANCE, full).result().get().asJsonObject
        encoded.remove("name")
        val decoded = SideBinding.CODEC.parse(JsonOps.INSTANCE, encoded).result().get()
        assertEquals("", decoded.name)
    }

    @org.junit.jupiter.api.Test fun `side-binding round-trip with EndpointRef target`() {
        val ref = dev.nitka.nodewire.endpoint.EndpointRef(
            dev.nitka.nodewire.endpoint.WorldBackend.id,
            dev.nitka.nodewire.endpoint.WorldPayload(net.minecraft.core.BlockPos(7, 8, 9)),
        )
        val sb = SideBinding("ch", ref, net.minecraft.core.Direction.UP, "label")
        val tag = SideBinding.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, sb).result().orElseThrow()
        val decoded = SideBinding.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals(sb, decoded)
    }

    @org.junit.jupiter.api.Test fun `legacy side-binding pos field migrates to World ref`() {
        val legacy = com.google.gson.JsonParser.parseString(
            """{"src":"out","pos":[3,4,5],"side":"up","name":"x"}"""
        )
        val decoded = SideBinding.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, legacy)
            .result().orElseThrow()
        org.junit.jupiter.api.Assertions.assertEquals(
            net.minecraft.core.BlockPos(3, 4, 5), decoded.target.payload.blockPos
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            net.minecraft.core.Direction.UP, decoded.targetSide
        )
        org.junit.jupiter.api.Assertions.assertEquals("x", decoded.name)
    }
}
