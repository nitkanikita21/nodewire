package dev.nitka.nodewire.integration.cctweaked

import dan200.computercraft.api.lua.LuaException
import dev.nitka.nodewire.graph.PinType
import dev.nitka.nodewire.graph.PinValue

/**
 * Convert [PinValue] to a Lua-safe Java Object and back. Throws
 * [LuaException] on decode of an incompatible Lua value — message starts
 * with "type mismatch: expected <type>" so Lua callers see a clear cause.
 */
object NwChannelLuaCodec {

    fun toLua(value: PinValue): Any = when (value) {
        is PinValue.Bool     -> value.value
        is PinValue.Int      -> value.value.toLong()
        is PinValue.Redstone -> value.value.toLong()
        is PinValue.Float    -> value.value.toDouble()
        is PinValue.Str      -> value.value
        is PinValue.Vec2     -> mapOf("x" to value.x.toDouble(), "y" to value.y.toDouble())
        is PinValue.Vec3     -> mapOf(
            "x" to value.x.toDouble(),
            "y" to value.y.toDouble(),
            "z" to value.z.toDouble(),
        )
        is PinValue.Quat     -> mapOf(
            "x" to value.x.toDouble(),
            "y" to value.y.toDouble(),
            "z" to value.z.toDouble(),
            "w" to value.w.toDouble(),
        )
    }

    fun fromLua(lua: Any?, type: PinType): PinValue = when (type) {
        PinType.BOOL     -> PinValue.Bool(asBool(lua, "bool"))
        PinType.INT      -> PinValue.Int(asFiniteNumber(lua, "int").toInt())
        PinType.FLOAT    -> PinValue.Float(asFiniteNumber(lua, "float").toFloat())
        PinType.REDSTONE -> PinValue.Redstone(
            asFiniteNumber(lua, "redstone").toInt().coerceIn(0, 15)
        )
        PinType.STRING   -> PinValue.Str(asString(lua, "string"))
        PinType.VEC2     -> {
            val m = asMap(lua, "vec2")
            PinValue.Vec2(
                asFiniteNumber(m["x"], "vec2").toFloat(),
                asFiniteNumber(m["y"], "vec2").toFloat(),
            )
        }
        PinType.VEC3     -> {
            val m = asMap(lua, "vec3")
            PinValue.Vec3(
                asFiniteNumber(m["x"], "vec3").toFloat(),
                asFiniteNumber(m["y"], "vec3").toFloat(),
                asFiniteNumber(m["z"], "vec3").toFloat(),
            )
        }
        PinType.QUAT     -> {
            val m = asMap(lua, "quat")
            PinValue.Quat(
                asFiniteNumber(m["x"], "quat").toFloat(),
                asFiniteNumber(m["y"], "quat").toFloat(),
                asFiniteNumber(m["z"], "quat").toFloat(),
                asFiniteNumber(m["w"], "quat").toFloat(),
            )
        }
    }

    private fun asBool(v: Any?, label: String): Boolean =
        v as? Boolean ?: throw LuaException("type mismatch: expected $label")

    private fun asString(v: Any?, label: String): String =
        v as? String ?: throw LuaException("type mismatch: expected $label")

    private fun asMap(v: Any?, label: String): Map<*, *> =
        v as? Map<*, *> ?: throw LuaException("type mismatch: expected $label")

    private fun asFiniteNumber(v: Any?, label: String): Double {
        val d = (v as? Number)?.toDouble()
            ?: throw LuaException("type mismatch: expected $label")
        if (d.isNaN() || d.isInfinite()) throw LuaException("type mismatch: expected $label")
        return d
    }
}
