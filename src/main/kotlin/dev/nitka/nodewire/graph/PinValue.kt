package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag

/**
 * A concrete value that can flow through a pin. Each subclass corresponds
 * 1:1 with a [PinType] and owns its own NBT serialization.
 *
 * Why a sealed hierarchy and not a single `Any` blob:
 *   * Per-type fields give compile-time safety to evaluators.
 *   * Each subclass declares its own NBT keys — no central `when` switch
 *     to remember to update when adding a new type.
 *
 * NBT format: a `PinValue` writes its scalar field(s) into the supplied
 * tag using short keys (`v`, `x`, `y`, `z`, `w`). The owner is expected
 * to also store the [type] separately so the right subclass can be
 * reconstructed via [fromNbt].
 */
sealed class PinValue {
    abstract val type: PinType
    abstract fun writeTo(tag: CompoundTag)

    data class Bool(val value: Boolean) : PinValue() {
        override val type = PinType.BOOL
        override fun writeTo(tag: CompoundTag) { tag.putBoolean("v", value) }
    }

    data class Int(val value: kotlin.Int) : PinValue() {
        override val type = PinType.INT
        override fun writeTo(tag: CompoundTag) { tag.putInt("v", value) }
    }

    /** Redstone power 0..15. Constructor clamps so out-of-range upstream values fail gracefully. */
    data class Redstone(val value: kotlin.Int) : PinValue() {
        override val type = PinType.REDSTONE
        override fun writeTo(tag: CompoundTag) { tag.putInt("v", value.coerceIn(0, 15)) }
    }

    data class Float(val value: kotlin.Float) : PinValue() {
        override val type = PinType.FLOAT
        override fun writeTo(tag: CompoundTag) { tag.putFloat("v", value) }
    }

    data class Str(val value: String) : PinValue() {
        override val type = PinType.STRING
        override fun writeTo(tag: CompoundTag) { tag.putString("v", value) }
    }

    data class Vec2(val x: kotlin.Float, val y: kotlin.Float) : PinValue() {
        override val type = PinType.VEC2
        override fun writeTo(tag: CompoundTag) {
            tag.putFloat("x", x); tag.putFloat("y", y)
        }
    }

    data class Vec3(val x: kotlin.Float, val y: kotlin.Float, val z: kotlin.Float) : PinValue() {
        override val type = PinType.VEC3
        override fun writeTo(tag: CompoundTag) {
            tag.putFloat("x", x); tag.putFloat("y", y); tag.putFloat("z", z)
        }
    }

    data class Quat(
        val x: kotlin.Float,
        val y: kotlin.Float,
        val z: kotlin.Float,
        val w: kotlin.Float,
    ) : PinValue() {
        override val type = PinType.QUAT
        override fun writeTo(tag: CompoundTag) {
            tag.putFloat("x", x); tag.putFloat("y", y); tag.putFloat("z", z); tag.putFloat("w", w)
        }
    }

    companion object {
        /** Zero-value for [type] — used as the default for a freshly-spawned node's input pins. */
        fun default(type: PinType): PinValue = when (type) {
            PinType.BOOL -> Bool(false)
            PinType.INT -> Int(0)
            PinType.FLOAT -> Float(0f)
            PinType.REDSTONE -> Redstone(0)
            PinType.STRING -> Str("")
            PinType.VEC2 -> Vec2(0f, 0f)
            PinType.VEC3 -> Vec3(0f, 0f, 0f)
            PinType.QUAT -> Quat(0f, 0f, 0f, 1f)
        }

        /** Reconstructs a [PinValue] of [type] from a tag previously filled by [writeTo]. */
        fun fromNbt(type: PinType, tag: CompoundTag): PinValue = when (type) {
            PinType.BOOL -> Bool(tag.getBoolean("v"))
            PinType.INT -> Int(tag.getInt("v"))
            PinType.FLOAT -> Float(tag.getFloat("v"))
            PinType.REDSTONE -> Redstone(tag.getInt("v"))
            PinType.STRING -> Str(tag.getString("v"))
            PinType.VEC2 -> Vec2(tag.getFloat("x"), tag.getFloat("y"))
            PinType.VEC3 -> Vec3(tag.getFloat("x"), tag.getFloat("y"), tag.getFloat("z"))
            PinType.QUAT -> Quat(tag.getFloat("x"), tag.getFloat("y"), tag.getFloat("z"), tag.getFloat("w"))
        }

        // ── Per-variant codecs ────────────────────────────────────────
        private val BoolCodec: com.mojang.serialization.Codec<Bool> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.BOOL.fieldOf("v").forGetter(Bool::value))
                    .apply(i, ::Bool)
            }
        private val IntCodec: com.mojang.serialization.Codec<Int> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.INT.fieldOf("v").forGetter(Int::value))
                    .apply(i, ::Int)
            }
        private val RedstoneCodec: com.mojang.serialization.Codec<Redstone> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.INT.fieldOf("v").forGetter(Redstone::value))
                    .apply(i, ::Redstone)
            }
        private val FloatCodec: com.mojang.serialization.Codec<Float> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.FLOAT.fieldOf("v").forGetter(Float::value))
                    .apply(i, ::Float)
            }
        private val StrCodec: com.mojang.serialization.Codec<Str> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(com.mojang.serialization.Codec.STRING.fieldOf("v").forGetter(Str::value))
                    .apply(i, ::Str)
            }
        private val Vec2Codec: com.mojang.serialization.Codec<Vec2> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    com.mojang.serialization.Codec.FLOAT.fieldOf("x").forGetter(Vec2::x),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("y").forGetter(Vec2::y),
                ).apply(i, ::Vec2)
            }
        private val Vec3Codec: com.mojang.serialization.Codec<Vec3> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    com.mojang.serialization.Codec.FLOAT.fieldOf("x").forGetter(Vec3::x),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("y").forGetter(Vec3::y),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("z").forGetter(Vec3::z),
                ).apply(i, ::Vec3)
            }
        private val QuatCodec: com.mojang.serialization.Codec<Quat> =
            com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
                i.group(
                    com.mojang.serialization.Codec.FLOAT.fieldOf("x").forGetter(Quat::x),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("y").forGetter(Quat::y),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("z").forGetter(Quat::z),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("w").forGetter(Quat::w),
                ).apply(i, ::Quat)
            }

        /**
         * Sealed dispatch: emits a `type` field plus the variant's fields
         * inline. Decode looks at `type` and routes to the per-variant
         * codec. Adding a new variant means: new data class, new per-
         * variant codec, two more entries here.
         */
        val CODEC: com.mojang.serialization.Codec<PinValue> = com.mojang.serialization.Codec.STRING.dispatch(
            "type",
            { pv -> typeKey(pv) },
            { key -> codecFor(key) },
        )

        private fun typeKey(pv: PinValue): String = when (pv) {
            is Bool     -> "bool"
            is Int      -> "int"
            is Redstone -> "redstone"
            is Float    -> "float"
            is Str      -> "str"
            is Vec2     -> "vec2"
            is Vec3     -> "vec3"
            is Quat     -> "quat"
        }

        private fun codecFor(key: String): com.mojang.serialization.Codec<out PinValue> = when (key) {
            "bool"     -> BoolCodec
            "int"      -> IntCodec
            "redstone" -> RedstoneCodec
            "float"    -> FloatCodec
            "str"      -> StrCodec
            "vec2"     -> Vec2Codec
            "vec3"     -> Vec3Codec
            "quat"     -> QuatCodec
            else       -> error("Unknown PinValue type key: $key")
        }
    }
}
