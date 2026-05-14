package dev.nitka.nodewire.graph

import com.mojang.serialization.Codec
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.TagParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodecRoundTripTest {

    /** Encode → decode through NbtOps. Asserts result equals input. */
    private fun <T> roundTripNbt(codec: Codec<T>, value: T) {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).result().orElseThrow()
        val decoded = codec.parse(NbtOps.INSTANCE, encoded).result().orElseThrow()
        assertEquals(value, decoded)
    }

    /** Encode → SNBT → parse → decode. Asserts result equals input. */
    private fun <T> roundTripSnbt(codec: Codec<T>, value: T) {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).result().orElseThrow()
        val snbt = NbtUtils.structureToSnbt(encoded as net.minecraft.nbt.CompoundTag)
        val parsed = TagParser.parseTag(snbt)
        val decoded = codec.parse(NbtOps.INSTANCE, parsed).result().orElseThrow()
        assertEquals(value, decoded)
    }

    @Test fun pinTypeBoolNbt()  = roundTripNbt(PinType.CODEC.wrappedInRecord(), PinType.BOOL)
    @Test fun pinTypeIntNbt()   = roundTripNbt(PinType.CODEC.wrappedInRecord(), PinType.INT)
    @Test fun pinTypeQuatSnbt() = roundTripSnbt(PinType.CODEC.wrappedInRecord(), PinType.QUAT)

    @Test fun pinNbt() = roundTripNbt(Pin.CODEC, Pin("out", "Output", PinType.FLOAT))
    @Test fun pinSnbt() = roundTripSnbt(Pin.CODEC, Pin("a", "A", PinType.VEC3))

    @Test fun pinValueBoolNbt()      = roundTripNbt(PinValue.CODEC, PinValue.Bool(true))
    @Test fun pinValueBoolFalseNbt() = roundTripNbt(PinValue.CODEC, PinValue.Bool(false))
    @Test fun pinValueIntNbt()       = roundTripNbt(PinValue.CODEC, PinValue.Int(-42))
    @Test fun pinValueRedstoneNbt()  = roundTripNbt(PinValue.CODEC, PinValue.Redstone(11))
    @Test fun pinValueFloatNbt()     = roundTripNbt(PinValue.CODEC, PinValue.Float(3.14f))
    @Test fun pinValueStrNbt()       = roundTripNbt(PinValue.CODEC, PinValue.Str("hello"))
    @Test fun pinValueVec2Nbt()      = roundTripNbt(PinValue.CODEC, PinValue.Vec2(1f, 2f))
    @Test fun pinValueVec3Nbt()      = roundTripNbt(PinValue.CODEC, PinValue.Vec3(1f, 2f, 3f))
    @Test fun pinValueQuatNbt()      = roundTripNbt(PinValue.CODEC, PinValue.Quat(0f, 0f, 0f, 1f))

    @Test fun pinValueBoolSnbt()  = roundTripSnbt(PinValue.CODEC, PinValue.Bool(true))
    @Test fun pinValueVec3Snbt()  = roundTripSnbt(PinValue.CODEC, PinValue.Vec3(1f, 2f, 3f))
    @Test fun pinValueStrSnbt()   = roundTripSnbt(PinValue.CODEC, PinValue.Str("with spaces"))

    /**
     * PinType.CODEC is a primitive-string codec, which encodes to a
     * StringTag — but our roundTrip helpers and SNBT path require a
     * CompoundTag. Wrap any primitive codec in a 1-field record for the
     * test.
     */
    private fun Codec<PinType>.wrappedInRecord(): Codec<PinType> =
        com.mojang.serialization.codecs.RecordCodecBuilder.create { i ->
            i.group(this.fieldOf("v").forGetter { it }).apply(i) { it }
        }
}
