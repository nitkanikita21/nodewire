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
            PinType.STRING -> Str(tag.getString("v"))
            PinType.VEC2 -> Vec2(tag.getFloat("x"), tag.getFloat("y"))
            PinType.VEC3 -> Vec3(tag.getFloat("x"), tag.getFloat("y"), tag.getFloat("z"))
            PinType.QUAT -> Quat(tag.getFloat("x"), tag.getFloat("y"), tag.getFloat("z"), tag.getFloat("w"))
        }
    }
}
