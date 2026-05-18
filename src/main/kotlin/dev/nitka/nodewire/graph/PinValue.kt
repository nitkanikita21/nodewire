package dev.nitka.nodewire.graph

/**
 * A concrete value that can flow through a pin. Each subclass corresponds
 * 1:1 with a [PinType] and carries the matching scalar fields.
 *
 * NBT (and SNBT) serialization happens via [CODEC] — a sealed-class
 * dispatch codec keyed on a string discriminator field `type`. Adding a
 * new variant means: new data class, new per-variant codec in the
 * companion, two more entries in `typeKey`/`codecFor`.
 */
sealed class PinValue {
    abstract val type: PinType

    data class Bool(val value: Boolean) : PinValue() {
        override val type = PinType.BOOL
    }

    data class Int(val value: kotlin.Int) : PinValue() {
        override val type = PinType.INT
    }

    /** Redstone power 0..15. Constructor clamps so out-of-range upstream values fail gracefully. */
    data class Redstone(val value: kotlin.Int) : PinValue() {
        override val type = PinType.REDSTONE
    }

    data class Float(val value: kotlin.Float) : PinValue() {
        override val type = PinType.FLOAT
    }

    data class Str(val value: String) : PinValue() {
        override val type = PinType.STRING
    }

    data class Vec2(val x: kotlin.Float, val y: kotlin.Float) : PinValue() {
        override val type = PinType.VEC2
    }

    data class Vec3(val x: kotlin.Float, val y: kotlin.Float, val z: kotlin.Float) : PinValue() {
        override val type = PinType.VEC3
    }

    data class Quat(
        val x: kotlin.Float,
        val y: kotlin.Float,
        val z: kotlin.Float,
        val w: kotlin.Float,
    ) : PinValue() {
        override val type = PinType.QUAT
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

        // ── Per-variant codecs ────────────────────────────────────────
        private val BoolCodec: com.mojang.serialization.MapCodec<Bool> =
            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec { i ->
                i.group(com.mojang.serialization.Codec.BOOL.fieldOf("v").forGetter(Bool::value))
                    .apply(i, ::Bool)
            }
        private val IntCodec: com.mojang.serialization.MapCodec<Int> =
            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec { i ->
                i.group(com.mojang.serialization.Codec.INT.fieldOf("v").forGetter(Int::value))
                    .apply(i, ::Int)
            }
        private val RedstoneCodec: com.mojang.serialization.MapCodec<Redstone> =
            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec { i ->
                i.group(com.mojang.serialization.Codec.INT.fieldOf("v").forGetter(Redstone::value))
                    .apply(i, ::Redstone)
            }
        private val FloatCodec: com.mojang.serialization.MapCodec<Float> =
            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec { i ->
                i.group(com.mojang.serialization.Codec.FLOAT.fieldOf("v").forGetter(Float::value))
                    .apply(i, ::Float)
            }
        private val StrCodec: com.mojang.serialization.MapCodec<Str> =
            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec { i ->
                i.group(com.mojang.serialization.Codec.STRING.fieldOf("v").forGetter(Str::value))
                    .apply(i, ::Str)
            }
        private val Vec2Codec: com.mojang.serialization.MapCodec<Vec2> =
            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec { i ->
                i.group(
                    com.mojang.serialization.Codec.FLOAT.fieldOf("x").forGetter(Vec2::x),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("y").forGetter(Vec2::y),
                ).apply(i, ::Vec2)
            }
        private val Vec3Codec: com.mojang.serialization.MapCodec<Vec3> =
            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec { i ->
                i.group(
                    com.mojang.serialization.Codec.FLOAT.fieldOf("x").forGetter(Vec3::x),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("y").forGetter(Vec3::y),
                    com.mojang.serialization.Codec.FLOAT.fieldOf("z").forGetter(Vec3::z),
                ).apply(i, ::Vec3)
            }
        private val QuatCodec: com.mojang.serialization.MapCodec<Quat> =
            com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec { i ->
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
         * codec.
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

        private fun codecFor(key: String): com.mojang.serialization.MapCodec<out PinValue> = when (key) {
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
