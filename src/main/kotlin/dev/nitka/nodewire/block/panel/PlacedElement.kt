package dev.nitka.nodewire.block.panel

import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.TagParser

data class PlacedElement(
    val typeId: String,
    val cellX: Int,
    val cellY: Int,
    val cols: Int,
    val rows: Int,
    val config: CompoundTag,
    val value: Double,
) {
    fun pinId(): String = "$typeId@$cellX,$cellY"

    companion object {
        /**
         * Config is stored as an SNBT string rather than via `CompoundTag.CODEC`.
         * `CompoundTag.CODEC` is lossy across `JsonOps` (JSON has no NBT
         * integer-width type info, so an `IntTag(4)` decodes back as `ByteTag(4)`),
         * which breaks the JSON round-trip. SNBT carries the type suffixes, so it
         * round-trips losslessly through both `NbtOps` and `JsonOps`. This mirrors
         * the repo's existing SNBT lossless path (`NbtUtils.structureToSnbt`).
         */
        private val CONFIG_CODEC: Codec<CompoundTag> = Codec.STRING.comapFlatMap(
            { snbt ->
                try {
                    DataResult.success(TagParser.parseTag(snbt))
                } catch (e: CommandSyntaxException) {
                    DataResult.error { "Invalid element config NBT: ${e.message}" }
                }
            },
            { tag -> NbtUtils.structureToSnbt(tag) },
        )

        val CODEC: Codec<PlacedElement> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("type").forGetter(PlacedElement::typeId),
                Codec.INT.fieldOf("x").forGetter(PlacedElement::cellX),
                Codec.INT.fieldOf("y").forGetter(PlacedElement::cellY),
                Codec.INT.fieldOf("cols").forGetter(PlacedElement::cols),
                Codec.INT.fieldOf("rows").forGetter(PlacedElement::rows),
                CONFIG_CODEC.optionalFieldOf("cfg", CompoundTag()).forGetter(PlacedElement::config),
                Codec.DOUBLE.optionalFieldOf("val", 0.0).forGetter(PlacedElement::value),
            ).apply(i, ::PlacedElement)
        }
    }
}
