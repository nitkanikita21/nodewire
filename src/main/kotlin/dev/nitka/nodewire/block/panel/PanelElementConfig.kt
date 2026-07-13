package dev.nitka.nodewire.block.panel

import net.minecraft.nbt.CompoundTag

/**
 * The per-element-type config schema edited by the Panel Key's config screen and
 * read by the renderer / operate logic. Each [Field] is a single editable value;
 * the screen renders one text input per field and round-trips through
 * [read]/[build]. Colours are stored as ARGB ints but edited as 8-digit hex.
 *
 * Pure (no client / world deps) so the round-trip is unit-testable.
 */
object PanelElementConfig {
    enum class FieldKind { DOUBLE, INT, STRING, COLOR }

    data class Field(val key: String, val label: String, val kind: FieldKind, val default: String)

    /** Editable fields for [typeId] (empty = the element has no options). */
    fun fields(typeId: String): List<Field> = when (typeId) {
        "selector" -> listOf(Field("positions", "Positions", FieldKind.INT, "2"))
        "slider" -> listOf(
            Field("min", "Min", FieldKind.DOUBLE, "0"),
            Field("max", "Max", FieldKind.DOUBLE, "1"),
            Field("step", "Step (0=smooth)", FieldKind.DOUBLE, "0"),
        )
        "knob" -> listOf(
            Field("min", "Min", FieldKind.DOUBLE, "0"),
            Field("max", "Max", FieldKind.DOUBLE, "1"),
            Field("step", "Step", FieldKind.DOUBLE, "0"),
            Field("sweep", "Sweep deg", FieldKind.DOUBLE, "270"),
        )
        "lamp" -> listOf(
            Field("on_color", "On colour", FieldKind.COLOR, "FFFF3333"),
            Field("off_color", "Off colour", FieldKind.COLOR, "FF331111"),
        )
        "bar" -> listOf(
            Field("min", "Min", FieldKind.DOUBLE, "0"),
            Field("max", "Max", FieldKind.DOUBLE, "1"),
            Field("color", "Colour", FieldKind.COLOR, "FF33CCCC"),
        )
        "numeric" -> listOf(
            Field("min", "Min", FieldKind.DOUBLE, "0"),
            Field("max", "Max", FieldKind.DOUBLE, "1"),
            Field("decimals", "Decimals", FieldKind.INT, "1"),
            Field("suffix", "Suffix", FieldKind.STRING, ""),
            Field("label", "Label", FieldKind.STRING, ""),
        )
        "label" -> listOf(Field("text", "Text", FieldKind.STRING, "Label"))
        else -> emptyList() // toggle / momentary / screen: no options in v1
    }

    /** The current string value of [f] in [cfg], or its default when unset. */
    fun read(cfg: CompoundTag, f: Field): String = when (f.kind) {
        FieldKind.DOUBLE -> if (cfg.contains(f.key)) trimNum(cfg.getDouble(f.key)) else f.default
        FieldKind.INT -> if (cfg.contains(f.key)) cfg.getInt(f.key).toString() else f.default
        FieldKind.STRING -> if (cfg.contains(f.key)) cfg.getString(f.key) else f.default
        FieldKind.COLOR -> if (cfg.contains(f.key)) "%08X".format(cfg.getInt(f.key)) else f.default
    }

    /** Build a config tag for [typeId] from edited string [values]; unparseable
     *  entries fall back to the field default. */
    fun build(typeId: String, values: Map<String, String>): CompoundTag {
        val tag = CompoundTag()
        for (f in fields(typeId)) {
            val s = values[f.key] ?: f.default
            when (f.kind) {
                FieldKind.DOUBLE -> (s.toDoubleOrNull() ?: f.default.toDoubleOrNull())?.let { tag.putDouble(f.key, it) }
                FieldKind.INT -> (s.toIntOrNull() ?: f.default.toIntOrNull())?.let { tag.putInt(f.key, it) }
                FieldKind.STRING -> tag.putString(f.key, s)
                FieldKind.COLOR -> {
                    val hex = s.trim().removePrefix("#").removePrefix("0x")
                    (hex.toLongOrNull(16) ?: f.default.toLongOrNull(16))?.let { tag.putInt(f.key, it.toInt()) }
                }
            }
        }
        return tag
    }

    /** Drop a trailing ".0" so whole numbers edit cleanly. */
    private fun trimNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
