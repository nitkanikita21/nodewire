package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.lua.LuaException
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NwChannelLuaCodecTest {

    @Test fun `bool round trips`() {
        assertEquals(true, NwChannelLuaCodec.toLua(PinValue.Bool(true)))
        assertEquals(PinValue.Bool(true), NwChannelLuaCodec.fromLua(true, PinType.BOOL))
    }

    @Test fun `int floors fractional numbers`() {
        assertEquals(PinValue.Int(3), NwChannelLuaCodec.fromLua(3.7, PinType.INT))
    }

    @Test fun `redstone clamps to 0_15`() {
        assertEquals(PinValue.Redstone(15), NwChannelLuaCodec.fromLua(99.0, PinType.REDSTONE))
        assertEquals(PinValue.Redstone(0), NwChannelLuaCodec.fromLua(-5.0, PinType.REDSTONE))
    }

    @Test fun `vec3 requires x y z`() {
        val v = NwChannelLuaCodec.fromLua(mapOf("x" to 1.0, "y" to 2.0, "z" to 3.0), PinType.VEC3)
        assertEquals(PinValue.Vec3(1f, 2f, 3f), v)
    }

    @Test fun `vec3 missing field throws`() {
        val ex = assertThrows(LuaException::class.java) {
            NwChannelLuaCodec.fromLua(mapOf("x" to 1.0, "y" to 2.0), PinType.VEC3)
        }
        assert(ex.message!!.contains("type mismatch")) { "got: ${ex.message}" }
    }

    @Test fun `wrong primitive class throws`() {
        assertThrows(LuaException::class.java) {
            NwChannelLuaCodec.fromLua("hello", PinType.INT)
        }
    }

    @Test fun `nan rejected`() {
        assertThrows(LuaException::class.java) {
            NwChannelLuaCodec.fromLua(Double.NaN, PinType.FLOAT)
        }
    }

    @Test fun `quat round trip`() {
        val src = PinValue.Quat(0.1f, 0.2f, 0.3f, 0.9f)
        val lua = NwChannelLuaCodec.toLua(src) as Map<*, *>
        assertEquals(0.1f.toDouble(), (lua["x"] as Number).toDouble(), 1e-6)
        val back = NwChannelLuaCodec.fromLua(lua, PinType.QUAT) as PinValue.Quat
        assertEquals(0.9, back.w, 1e-6)
    }
}
