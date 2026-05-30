package dev.nitka.nodewire.ui.render

import net.minecraft.ChatFormatting

/**
 * Lightweight syntax-highlight token kinds for the in-world script editor.
 *
 * Lives in the `ui` layer (not `script.lexer`) so [dev.nitka.nodewire.ui.components.TextArea]
 * can reference [HlSpan] in its public signature without importing any `script.*`
 * type — the highlighter produces these, the editor (in `script.*`) wires them in.
 *
 * The conceptual palette is a [ChatFormatting] colour per kind; at the draw site
 * the renderer needs a packed-ARGB [Color], so each kind also exposes [color].
 */
enum class HlKind(val formatting: ChatFormatting) {
    KEYWORD(ChatFormatting.LIGHT_PURPLE),
    DSL(ChatFormatting.AQUA),
    STRING(ChatFormatting.GREEN),
    NUMBER(ChatFormatting.GOLD),
    COMMENT(ChatFormatting.DARK_GRAY),
    PLAIN(ChatFormatting.WHITE);

    /** Packed-ARGB colour for this kind, derived from its [ChatFormatting]. */
    val color: Color by lazy {
        val rgb = formatting.color ?: 0xFFFFFF
        Color(0xFF000000.toInt() or rgb)
    }
}

/** A classified, half-open `[start, end)` span of source in [HlKind]. */
data class HlSpan(val start: Int, val end: Int, val kind: HlKind)
